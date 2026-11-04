package neth.iecal.curboxsync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Keeps the phone's own history (read from Curbox) and what each paired browser sent, so the
 * combined history still shows when one side is away.
 */
class UsageStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "usage.json")
    private val _snapshot = MutableStateFlow(load())
    val snapshot: StateFlow<UsageSnapshot> = _snapshot

    @Synchronized
    fun setPhone(days: List<PhoneDay>, detailed: Boolean) {
        val now = System.currentTimeMillis()
        val cur = _snapshot.value
        // An older Curbox only knows today, so keep earlier days it reported before.
        val merged = if (detailed) days.associateBy { it.date } else cur.phone + days.associateBy { it.date }
        save(cur.copy(phone = merged, phoneDetailed = detailed, phoneReadAt = now))
    }

    /** Stores days a browser sent and returns the totals now known for it, by date. */
    @Synchronized
    fun putBrowser(id: String, name: String, days: List<BrowserDay>): Map<String, Long> {
        val cur = _snapshot.value
        val old = cur.browsers.firstOrNull { it.id == id }
        val merged = UsageMath.prune((old?.days.orEmpty()) + days.associateBy { it.date })
        val updated = BrowserUsage(id, name.ifBlank { old?.name ?: "Browser" }, System.currentTimeMillis(), merged, via = null)
        val browsers = (listOf(updated) + cur.browsers.filter { it.id != id }).take(MAX_BROWSERS)
        if (days.isNotEmpty() || old == null || old.name != updated.name) save(cur.copy(browsers = browsers))
        else _snapshot.value = cur.copy(browsers = browsers)
        return merged.mapValues { it.value.totalMs }
    }

    @Synchronized
    fun forgetBrowsers() = save(_snapshot.value.copy(browsers = emptyList()))

    /** Stores the days another phone sent, replacing what it sent before for those dates. */
    @Synchronized
    fun putPhone(id: String, name: String, days: List<PhoneDay>) {
        val cur = _snapshot.value
        val old = cur.phones.firstOrNull { it.id == id }
        val merged = UsageMath.prune(old?.days.orEmpty() + days.associateBy { it.date })
        val updated = PeerPhoneUsage(id, name.ifBlank { old?.name ?: "Phone" }, System.currentTimeMillis(), merged)
        save(cur.copy(phones = (listOf(updated) + cur.phones.filter { it.id != id }).take(MAX_BROWSERS)))
    }

    @Synchronized
    fun forgetPhone(id: String) {
        val cur = _snapshot.value
        if (cur.phones.any { it.id == id } || cur.browsers.any { it.via == id }) {
            save(cur.copy(phones = cur.phones.filter { it.id != id }, browsers = cur.browsers.filter { it.via != id }))
        }
    }

    /**
     * Browser days another phone received. A day keeps the larger total, since a day's total only
     * grows and whichever phone heard from the browser last knows more.
     */
    @Synchronized
    fun putRelayedBrowsers(peerId: String, list: List<BrowserUsage>) {
        if (list.isEmpty()) return
        val cur = _snapshot.value
        var browsers = cur.browsers
        for (b in list) {
            val old = browsers.firstOrNull { it.id == b.id }
            val days = (old?.days.orEmpty().keys + b.days.keys).associateWith { d ->
                listOfNotNull(old?.days?.get(d), b.days[d]).maxBy { it.totalMs }
            }
            val updated = BrowserUsage(
                b.id, old?.name?.ifBlank { null } ?: b.name.ifBlank { "Browser" },
                maxOf(old?.lastSeen ?: 0L, b.lastSeen), UsageMath.prune(days),
                via = if (old != null && old.via == null) null else peerId,
            )
            browsers = listOf(updated) + browsers.filter { it.id != b.id }
        }
        save(cur.copy(browsers = browsers.take(MAX_BROWSERS)))
    }

    /** Browsers that sent their days to this phone itself, to pass on to paired phones. */
    fun ownBrowsersJson(): JSONArray = JSONArray().apply {
        _snapshot.value.browsers.filter { it.via == null }.forEach { put(browserJson(it)) }
    }

    /** This phone's own recent days, as sent to other phones. */
    fun ownDaysJson(limit: Int = SHARE_DAYS): JSONArray =
        phoneDaysJson(_snapshot.value.phone.values.sortedByDescending { it.date }.take(limit))

    private fun save(s: UsageSnapshot) {
        _snapshot.value = s
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(encode(s).toString())
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "Could not save usage", it) }
    }

    private fun load(): UsageSnapshot = runCatching { decode(JSONObject(file.readText())) }.getOrDefault(UsageSnapshot())

    companion object {
        private const val TAG = "UsageStore"
        private const val MAX_BROWSERS = 8
        const val MAX_SITES = 40
        /** Days a phone shares with another phone each time. */
        const val SHARE_DAYS = 14
        private const val MAX_APPS = 60
        private const val DAY_MS = 86_400_000L

        fun sitesJson(sites: List<SiteTime>) = JSONArray().apply {
            sites.forEach { put(JSONObject().put("domain", it.domain).put("ms", it.ms)) }
        }

        fun parseSites(arr: JSONArray?): List<SiteTime> = (0 until (arr?.length() ?: 0)).mapNotNull {
            val o = arr!!.optJSONObject(it) ?: return@mapNotNull null
            val d = o.optString("domain").take(120)
            val ms = o.optLong("ms").coerceIn(0, DAY_MS)
            if (d.isBlank() || ms == 0L) null else SiteTime(d, ms)
        }.sortedByDescending { it.ms }.take(MAX_SITES)

        /** Curbox's `USAGE_HISTORY` list (API 5). */
        fun parsePhoneHistory(raw: String): List<PhoneDay> {
            val arr = JSONArray(raw)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val date = o.optString("date")
                if (!UsageMath.isDate(date)) return@mapNotNull null
                val apps = o.optJSONArray("apps") ?: JSONArray()
                PhoneDay(
                    date = date,
                    screenMs = o.optLong("screenTimeMs"),
                    apps = (0 until apps.length()).map {
                        val a = apps.getJSONObject(it)
                        AppTime(a.optString("packageName"), a.optString("label"), a.optLong("ms"), a.optInt("launches"))
                    },
                    sites = parseSites(o.optJSONArray("websites")),
                )
            }
        }

        /** A browser's `usage` request days. Bad or future days are dropped. */
        fun parseBrowserDays(arr: JSONArray?, limit: Int = 14): List<BrowserDay> {
            val tomorrow = LocalDate.now().plusDays(1).toString()
            return (0 until (arr?.length() ?: 0)).mapNotNull {
                val o = arr!!.optJSONObject(it) ?: return@mapNotNull null
                val date = o.optString("date")
                if (!UsageMath.isDate(date) || date > tomorrow) return@mapNotNull null
                BrowserDay(date, o.optLong("totalMs").coerceIn(0, DAY_MS), parseSites(o.optJSONArray("sites")))
            }.take(limit)
        }

        private fun browserJson(b: BrowserUsage) = JSONObject().put("id", b.id).put("name", b.name).put("lastSeen", b.lastSeen)
            .put("days", JSONArray().apply {
                b.days.values.forEach { d ->
                    put(JSONObject().put("date", d.date).put("totalMs", d.totalMs).put("sites", sitesJson(d.sites)))
                }
            })

        /** Browsers another phone passed on. */
        fun parseBrowsers(arr: JSONArray?): List<BrowserUsage> = (0 until (arr?.length() ?: 0)).mapNotNull {
            val b = arr!!.optJSONObject(it) ?: return@mapNotNull null
            val id = b.optString("id").take(64).ifEmpty { return@mapNotNull null }
            BrowserUsage(id, b.optString("name").take(60), b.optLong("lastSeen"),
                UsageMath.prune(parseBrowserDays(b.optJSONArray("days"), UsageMath.RETAIN_DAYS).associateBy { d -> d.date }))
        }.take(MAX_BROWSERS)

        /** Days in Curbox's `USAGE_HISTORY` shape, so [parsePhoneHistory] reads them back. */
        fun phoneDaysJson(days: Collection<PhoneDay>) = JSONArray().apply {
            days.forEach { d ->
                put(JSONObject().put("date", d.date).put("screenTimeMs", d.screenMs)
                    .put("apps", JSONArray().apply {
                        d.apps.forEach { a ->
                            put(JSONObject().put("packageName", a.packageName).put("label", a.label)
                                .put("ms", a.ms).put("launches", a.launches))
                        }
                    })
                    .put("websites", sitesJson(d.sites)))
            }
        }

        /** Days another phone sent. Bad or future days are dropped and each day is capped. */
        fun parsePeerDays(arr: JSONArray?): List<PhoneDay> {
            val tomorrow = LocalDate.now().plusDays(1).toString()
            return runCatching { parsePhoneHistory(arr?.toString() ?: "[]") }.getOrDefault(emptyList())
                .filter { it.date <= tomorrow }
                .take(SHARE_DAYS)
                .map { d ->
                    d.copy(
                        screenMs = d.screenMs.coerceIn(0, DAY_MS),
                        apps = d.apps.filter { it.packageName.isNotBlank() && it.ms > 0 }
                            .map { it.copy(packageName = it.packageName.take(200), label = it.label.take(80), ms = it.ms.coerceIn(0, DAY_MS)) }
                            .sortedByDescending { it.ms }.take(MAX_APPS),
                    )
                }
        }

        private fun encode(s: UsageSnapshot): JSONObject = JSONObject()
            .put("v", 1)
            .put("phoneDetailed", s.phoneDetailed)
            .put("phoneReadAt", s.phoneReadAt)
            .put("phone", phoneDaysJson(s.phone.values))
            .put("phones", JSONArray().apply {
                s.phones.forEach { p ->
                    put(JSONObject().put("id", p.id).put("name", p.name).put("lastSeen", p.lastSeen)
                        .put("days", phoneDaysJson(p.days.values)))
                }
            })
            .put("browsers", JSONArray().apply {
                s.browsers.forEach { b -> put(browserJson(b).apply { b.via?.let { put("via", it) } }) }
            })

        private fun decode(o: JSONObject): UsageSnapshot {
            val phone = parsePhoneHistory(o.optJSONArray("phone")?.toString() ?: "[]")
            val arr = o.optJSONArray("browsers") ?: JSONArray()
            val browsers = (0 until arr.length()).map {
                val b = arr.getJSONObject(it)
                BrowserUsage(
                    b.optString("id"), b.optString("name"), b.optLong("lastSeen"),
                    UsageMath.prune(parseBrowserDays(b.optJSONArray("days"), Int.MAX_VALUE).associateBy { d -> d.date }),
                    via = b.optString("via").ifEmpty { null },
                )
            }
            val parr = o.optJSONArray("phones") ?: JSONArray()
            val phones = (0 until parr.length()).map {
                val p = parr.getJSONObject(it)
                PeerPhoneUsage(
                    p.optString("id"), p.optString("name"), p.optLong("lastSeen"),
                    UsageMath.prune(parsePhoneHistory(p.optJSONArray("days")?.toString() ?: "[]").associateBy { d -> d.date }),
                )
            }
            return UsageSnapshot(phone.associateBy { it.date }, o.optBoolean("phoneDetailed", true), o.optLong("phoneReadAt"), browsers, phones)
        }
    }
}
