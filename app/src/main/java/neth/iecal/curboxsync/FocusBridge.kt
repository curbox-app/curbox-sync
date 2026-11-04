package neth.iecal.curboxsync

import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

enum class CurboxAccess { MISSING, CONNECTING, NEEDS_APPROVAL, READY }

/** [websites] and [packages] are null when Curbox (before API 7) only reports counts. */
data class PhoneGroup(
    val id: String,
    val name: String,
    val exitable: Boolean,
    val blockMode: String = "",
    val apps: Int = 0,
    val websites: List<String>? = null,
    val packages: List<String>? = null,
) {
    /** [full] adds the website list; [withApps] also the app list, which only other phones ask for. */
    fun toJson(full: Boolean, withApps: Boolean = false): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("exitable", exitable).put("blockMode", blockMode).put("apps", apps)
        .apply { if (full && websites != null) put("websites", JSONArray(websites)) }
        .apply { if (full && withApps && packages != null) put("packages", JSONArray(packages)) }

    companion object {
        fun fromJson(g: JSONObject) = PhoneGroup(
            id = g.optString("id"),
            name = g.optString("name"),
            exitable = g.optBoolean("exitable", true),
            blockMode = g.optString("blockMode"),
            apps = g.optInt("apps"),
            websites = g.optJSONArray("websites")?.strings(),
            packages = g.optJSONArray("packages")?.strings(),
        )
    }
}

fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

/** A running Pomodoro cycle (Curbox API 7). [phase] is FOCUS or BREAK. */
data class Pomodoro(val phase: String, val focusMinutes: Int, val breakMinutes: Int, val rounds: Int, val completed: Int) {
    fun toJson(): JSONObject = JSONObject()
        .put("phase", phase).put("focusMinutes", focusMinutes).put("breakMinutes", breakMinutes)
        .put("rounds", rounds).put("completed", completed)

    companion object {
        fun parse(o: JSONObject?): Pomodoro? {
            if (o == null) return null
            val rounds = o.optInt("rounds")
            if (rounds <= 0) return null
            return Pomodoro(
                phase = if (o.optString("phase").equals("break", ignoreCase = true)) "BREAK" else "FOCUS",
                focusMinutes = o.optInt("focusMinutes", 25).coerceIn(1, 1_440),
                breakMinutes = o.optInt("breakMinutes", 5).coerceIn(1, 120),
                rounds = rounds.coerceIn(2, 12),
                completed = o.optInt("completed").coerceAtLeast(0),
            )
        }
    }
}

/** What Curbox on this phone is doing, as last read through the API. */
data class PhoneFocus(
    val access: CurboxAccess = CurboxAccess.CONNECTING,
    val apiVersion: Int = 0,
    val active: Boolean = false,
    val groupId: String = "",
    val name: String = "",
    val endsAt: Long = 0,
    val exitable: Boolean = true,
    val groups: List<PhoneGroup> = emptyList(),
    /** API 7: the cycle the session runs, or null. */
    val pomodoro: Pomodoro? = null,
    /** Phone clock time this state was first seen. */
    val changedAt: Long = 0,
    val rev: Long = 0,
) {
    /** Before API 4 the end is estimated from whole remaining minutes. */
    val exact: Boolean get() = apiVersion >= 4

    /** API 7 reports Pomodoro phases and full website lists, and takes group edits. */
    val rich: Boolean get() = apiVersion >= 7

    /** Changes whenever a group's shared fields change, so a browser knows when to fetch them again. */
    val groupsHash: String get() = groups.sortedBy { it.id }
        .joinToString("\n") {
            listOf(it.id, it.name, it.exitable, it.blockMode, it.websites?.sorted()?.joinToString("\u0001"), it.packages?.sorted()?.joinToString("\u0001"))
                .joinToString("\u0000")
        }
        .hashCode().toUInt().toString(16)

    val onBreak: Boolean get() = active && pomodoro?.phase == "BREAK"

    fun sameAs(other: PhoneFocus): Boolean =
        access == other.access && active == other.active && groupId == other.groupId &&
            name == other.name && exitable == other.exitable && groups == other.groups && pomodoro == other.pomodoro &&
            (!active || abs(endsAt - other.endsAt) <= if (exact) 0 else END_TOLERANCE_MS)

    fun toJson(): JSONObject = JSONObject()
        .put("connected", access == CurboxAccess.READY)
        .put("apiVersion", apiVersion)
        .put("active", active)
        .put("groupId", groupId)
        .put("name", name)
        .put("endsAt", endsAt)
        .put("exitable", exitable)
        .put("exact", exact)
        .put("changedAt", changedAt)
        .put("rev", rev)
        .put("groups", JSONArray().apply { groups.forEach { put(it.toJson(full = false)) } })
        .put("caps", JSONArray().apply { if (rich) { put("pomodoro"); put("groups") } })
        .put("groupsHash", groupsHash)
        .put("phase", if (!active) "" else if (onBreak) "break" else "focus")
        .apply { pomodoro?.takeIf { active }?.let { put("pomodoro", it.toJson()) } }

    companion object {
        const val END_TOLERANCE_MS = 90_000L
    }
}

/** The focus state the browser wants the phone to match. */
data class DesiredFocus(
    val active: Boolean,
    val groupId: String,
    val name: String,
    val endsAt: Long,
    val pomodoro: Pomodoro? = null,
) {
    companion object {
        fun parse(o: JSONObject) = DesiredFocus(
            active = o.optBoolean("active"),
            groupId = o.optString("groupId").take(200),
            name = o.optString("name").take(200),
            endsAt = o.optLong("endsAt"),
            pomodoro = Pomodoro.parse(o.optJSONObject("pomodoro")),
        )
    }
}

/** A focus group as the browser wants it on the phone. Null fields stay as they are. */
data class GroupEdit(
    val id: String,
    val name: String?,
    val websites: List<String>?,
    val blockMode: String?,
    val exitable: Boolean?,
    /** Only other phones send apps; a browser does not know them. */
    val apps: List<String>? = null,
) {
    companion object {
        fun parse(o: JSONObject): GroupEdit? {
            val id = o.optString("id").trim().take(200).ifEmpty { return null }
            val sites = o.optJSONArray("websites")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            return GroupEdit(
                id = id,
                name = o.optString("name").takeIf { o.has("name") }?.take(100),
                websites = sites,
                blockMode = o.optString("blockMode").takeIf { it == "BLOCK_SELECTED" || it == "BLOCK_ALL_EXCEPT_SELECTED" },
                exitable = if (o.has("exitable")) o.optBoolean("exitable") else null,
                apps = o.optJSONArray("apps")?.strings(),
            )
        }
    }
}

/** Reads and changes Curbox's focus mode through the public AIDL API. */
class FocusBridge(private val connection: CurboxConnection) {
    private val _state = MutableStateFlow(PhoneFocus())
    val state: StateFlow<PhoneFocus> = _state
    private val applyLock = Mutex()

    /** Re-reads Curbox and bumps the revision when anything visible changed. */
    fun refresh(): PhoneFocus {
        val next = read()
        synchronized(this) {
            val prev = _state.value
            if (next.sameAs(prev)) return prev
            // A minute estimate drifts every read; keep the earlier value so it does not look like a change.
            val endsAt = if (next.active && prev.active && next.groupId == prev.groupId &&
                abs(next.endsAt - prev.endsAt) <= PhoneFocus.END_TOLERANCE_MS && !next.exact) prev.endsAt else next.endsAt
            val updated = next.copy(endsAt = endsAt, changedAt = System.currentTimeMillis(), rev = prev.rev + 1)
            _state.value = updated
            return updated
        }
    }

    private fun read(): PhoneFocus {
        val api = connection.api ?: return PhoneFocus(
            access = if (connection.packageName == null) CurboxAccess.MISSING else CurboxAccess.CONNECTING
        )
        return try {
            val version = api.apiVersion()
            if (!api.isGranted) return PhoneFocus(access = CurboxAccess.NEEDS_APPROVAL, apiVersion = version)
            val rich = version >= 7
            val groupsJson = JSONArray(api.list(if (rich) "FOCUS_GROUP_DETAILS" else "FOCUS_GROUPS") ?: "[]")
            val groups = (0 until groupsJson.length()).map {
                val g = groupsJson.getJSONObject(it)
                val parsed = PhoneGroup.fromJson(g)
                if (rich) parsed else parsed.copy(websites = null, packages = null)
            }
            val q = JSONObject(api.query("FOCUS_ACTIVE") ?: return PhoneFocus(CurboxAccess.NEEDS_APPROVAL, version))
            val now = System.currentTimeMillis()
            val active = q.optString("active") == "true"
            if (!active) return PhoneFocus(CurboxAccess.READY, version, groups = groups)
            val name = q.optString("focus_group")
            val group = q.optString("focus_group_id").takeIf { it.isNotEmpty() }
                ?.let { id -> groups.firstOrNull { it.id == id } ?: PhoneGroup(id, name, true) }
                ?: groups.firstOrNull { it.name == name }
            val remaining = q.optString("focus_remaining").toLongOrNull() ?: 0L
            val endsAt = q.optString("focus_ends_at").toLongOrNull()?.takeIf { it > 0 }
                ?: (now + remaining * 60_000L + 30_000L)
            val phase = q.optString("focus_phase")
            val pomodoro = if (rich && (phase == "FOCUS" || phase == "BREAK")) Pomodoro(
                phase = phase,
                focusMinutes = q.optString("pomodoro_focus_minutes").toIntOrNull() ?: 25,
                breakMinutes = q.optString("pomodoro_break_minutes").toIntOrNull() ?: 5,
                rounds = q.optString("pomodoro_rounds").toIntOrNull() ?: 4,
                completed = q.optString("pomodoro_completed").toIntOrNull() ?: 0,
            ) else null
            PhoneFocus(
                access = CurboxAccess.READY,
                apiVersion = version,
                active = true,
                groupId = group?.id.orEmpty(),
                name = name.ifEmpty { group?.name.orEmpty() },
                endsAt = endsAt,
                exitable = group?.exitable ?: true,
                groups = groups,
                pomodoro = pomodoro,
            )
        } catch (e: RemoteException) {
            Log.w(TAG, "Curbox call failed", e)
            PhoneFocus(access = CurboxAccess.CONNECTING)
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected Curbox reply", e)
            PhoneFocus(access = CurboxAccess.CONNECTING)
        }
    }

    /**
     * Makes Curbox match [desired]. Returns one of
     * applied, noop, no_group, locked, unavailable or failed.
     */
    suspend fun apply(desired: DesiredFocus): String = applyLock.withLock {
        val current = refresh()
        val api = connection.api
        if (current.access != CurboxAccess.READY || api == null) return "unavailable"
        val now = System.currentTimeMillis()
        val result = try {
            // Curbox before API 7 cannot run a cycle, so it follows the focus phases only.
            val pomodoro = desired.pomodoro?.takeIf { current.rich }
            val wantsBlock = desired.active && (desired.pomodoro?.phase != "BREAK" || pomodoro != null)
            if (desired.active && desired.endsAt <= now + 30_000L) {
                // About to end or change phase on its own; both sides get there without help.
                "noop"
            } else if (wantsBlock) {
                val group = current.groups.firstOrNull { it.id == desired.groupId }
                    ?: current.groups.firstOrNull { it.name.trim().equals(desired.name.trim(), ignoreCase = true) }
                when {
                    group == null -> "no_group"
                    current.active && current.groupId == group.id && current.pomodoro == pomodoro &&
                        abs(current.endsAt - desired.endsAt) <= PhoneFocus.END_TOLERANCE_MS -> "noop"
                    else -> {
                        val minutes = ((desired.endsAt - now + 59_999L) / 60_000L).toInt().coerceIn(1, 24 * 60)
                        val status = api.execute("START_FOCUS", Bundle().apply {
                            putString("target", group.id)
                            putInt("minutes", minutes)
                            putLong("ends_at", desired.endsAt)
                            if (pomodoro != null) {
                                putString("pomodoro_phase", pomodoro.phase)
                                putInt("pomodoro_focus_minutes", pomodoro.focusMinutes)
                                putInt("pomodoro_break_minutes", pomodoro.breakMinutes)
                                putInt("pomodoro_rounds", pomodoro.rounds)
                                putInt("pomodoro_completed", pomodoro.completed)
                            }
                        })
                        statusResult(status, current)
                    }
                }
            } else if (current.active) {
                statusResult(api.execute("STOP_FOCUS", Bundle()), current)
            } else "noop"
        } catch (e: RemoteException) {
            Log.w(TAG, "Curbox call failed", e)
            "unavailable"
        }
        refresh()
        result
    }

    /**
     * Applies browser group edits (API 7). Returns a result per group id: applied, noop, locked,
     * unavailable or failed.
     */
    suspend fun saveGroups(edits: List<GroupEdit>, deletes: List<String>): Map<String, String> = applyLock.withLock {
        val current = refresh()
        val api = connection.api
        val results = LinkedHashMap<String, String>()
        if (current.access != CurboxAccess.READY || api == null || !current.rich) {
            (edits.map { it.id } + deletes).forEach { results[it] = "unavailable" }
            return@withLock results
        }
        val now = System.currentTimeMillis()
        fun locked(id: String) = current.active && current.endsAt > now && current.groupId == id && !current.exitable
        for (edit in edits.take(MAX_EDITS)) {
            val old = current.groups.firstOrNull { it.id == edit.id }
            val same = old != null && (edit.name == null || edit.name.trim() == old.name) &&
                (edit.websites == null || edit.websites.map { it.trim() }.filter { it.isNotEmpty() }.toSet() == old.websites.orEmpty().toSet()) &&
                (edit.blockMode == null || edit.blockMode == old.blockMode) &&
                (edit.exitable == null || edit.exitable == old.exitable) &&
                (edit.apps == null || edit.apps.map { it.trim() }.filter { it.isNotEmpty() }.toSet() == old.packages.orEmpty().toSet())
            results[edit.id] = when {
                same -> "noop"
                old != null && locked(edit.id) -> "locked"
                else -> try {
                    val status = api.execute("SAVE_FOCUS_GROUP", Bundle().apply {
                        putString("target", edit.id)
                        edit.name?.let { putString("name", it) }
                        edit.websites?.let { putStringArrayList("websites", ArrayList(it)) }
                        edit.blockMode?.let { putString("block_mode", it) }
                        edit.exitable?.let { putBoolean("exitable", it) }
                        edit.apps?.let { putStringArrayList("apps", ArrayList(it)) }
                    })
                    if (status == "OK") "applied" else "failed"
                } catch (e: RemoteException) {
                    Log.w(TAG, "Curbox call failed", e)
                    "unavailable"
                }
            }
        }
        for (id in deletes.take(MAX_EDITS)) {
            results[id] = when {
                current.groups.none { it.id == id } -> "noop"
                current.active && current.endsAt > now && current.groupId == id -> "locked"
                else -> try {
                    if (api.execute("DELETE_FOCUS_GROUP", Bundle().apply { putString("target", id) }) == "OK") "applied" else "failed"
                } catch (e: RemoteException) {
                    Log.w(TAG, "Curbox call failed", e)
                    "unavailable"
                }
            }
        }
        refresh()
        results
    }

    private fun statusResult(status: String?, before: PhoneFocus) = when (status) {
        "OK" -> "applied"
        "FAILED" -> if (before.active && !before.exitable) "locked" else "failed"
        "DENIED" -> "unavailable"
        else -> "failed"
    }

    companion object {
        private const val TAG = "FocusBridge"
        private const val MAX_EDITS = 50
    }
}
