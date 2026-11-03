package neth.iecal.curboxsync

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.abs

data class SyncStatus(
    val running: Boolean = false,
    val installs: List<CurboxInstall> = emptyList(),
    val selectedPackage: String? = null,
    val phone: PhoneFocus = PhoneFocus(),
    val addresses: List<String> = emptyList(),
    val code: String = "",
    val browsers: Int = 0,
    val lastBrowserAt: Long = 0,
    val lastEvent: String = "",
    val error: String? = null,
    val network: CurrentNetwork? = null,
    val networks: List<SavedNetwork> = emptyList(),
)

/**
 * Process wide sync engine. [SyncService] keeps the process alive while it runs; the activity only
 * observes [status] and forwards user choices.
 */
@SuppressLint("StaticFieldLeak") // Holds only the application context.
object SyncRuntime {
    private const val TAG = "SyncRuntime"
    private const val CLOCK_WINDOW_MS = 10 * 60_000L
    private const val MAX_WAIT_MS = 25_000L
    private const val USAGE_EVERY_MS = 5 * 60_000L

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    private var context: Context? = null
    private var scope: CoroutineScope? = null
    private var connection: CurboxConnection? = null
    private var bridge: FocusBridge? = null
    private var server: LanServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var pollJob: Job? = null
    @Volatile private var pairing: Pairing? = null
    private val seenIds = LinkedHashMap<String, Long>()
    @Volatile private var usageStore: UsageStore? = null
    @Volatile private var networks: Networks? = null
    @Volatile private var usageReadAt = 0L

    /** Combined usage is shown even while sync is off, so the store lives apart from the server. */
    @Synchronized
    fun usage(appContext: Context): UsageStore =
        usageStore ?: UsageStore(appContext.applicationContext).also { usageStore = it }

    private fun networks(ctx: Context): Networks = networks ?: Networks(ctx).also { networks = it }

    /** Shows saved networks before sync has started. */
    fun loadNetworks(appContext: Context) {
        val ctx = appContext.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val n = networks(ctx)
            update { it.copy(networks = n.all(), network = Networks.current(ctx)) }
        }
    }

    fun renameNetwork(appContext: Context, id: String, label: String) {
        val ctx = appContext.applicationContext
        CoroutineScope(Dispatchers.IO).launch { update { it.copy(networks = networks(ctx).rename(id, label)) } }
    }

    fun forgetNetwork(appContext: Context, id: String) {
        val ctx = appContext.applicationContext
        CoroutineScope(Dispatchers.IO).launch { update { it.copy(networks = networks(ctx).forget(id)) } }
    }

    /** Re-reads this phone's history from Curbox. */
    fun refreshUsage() {
        usageReadAt = 0L
        scope?.launch(Dispatchers.IO) { readPhoneUsage() }
    }

    private fun readPhoneUsage() {
        val ctx = context ?: return
        val api = connection?.api ?: return
        val phone = bridge?.state?.value ?: return
        if (phone.access != CurboxAccess.READY) return
        val now = System.currentTimeMillis()
        if (now - usageReadAt < USAGE_EVERY_MS) return
        usageReadAt = now
        runCatching {
            if (phone.apiVersion >= 5) {
                val raw = api.list("USAGE_HISTORY") ?: return
                usage(ctx).setPhone(UsageStore.parsePhoneHistory(raw), detailed = true)
            } else {
                val raw = api.query("SCREENTIME_TODAY") ?: return
                val minutes = JSONObject(raw).optString("screentime").toLongOrNull() ?: return
                val today = java.time.LocalDate.now().toString()
                usage(ctx).setPhone(listOf(PhoneDay(today, minutes * 60_000L, emptyList(), emptyList())), detailed = false)
            }
        }.onFailure { Log.w(TAG, "Could not read usage", it) }
    }

    @Synchronized
    fun start(appContext: Context) {
        if (scope != null) return
        val ctx = appContext.applicationContext
        context = ctx
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val conn = CurboxConnection(ctx)
        connection = conn
        val b = FocusBridge(conn)
        bridge = b
        update { it.copy(running = true, error = null) }

        s.launch {
            val saved = withContext(Dispatchers.IO) { Prefs(ctx).pairing() }
            // A browser code scanned while this read ran is newer; keep it.
            val p = pairing ?: saved
            pairing = p
            publishPairing(p)
            selectInstall(ctx)
            try {
                server = LanServer(LanServer.PORT, s, ::handleRpc).also { it.start() }
            } catch (e: Exception) {
                Log.e(TAG, "Server failed to start", e)
                update { it.copy(error = "Could not open port ${LanServer.PORT}: ${e.message}") }
            }
        }
        s.launch { b.state.collect { phone -> update { it.copy(phone = phone) } } }
        s.launch { conn.connected.collect { pollNow() } }
        pollJob = s.launch(Dispatchers.IO) {
            while (isActive) {
                b.refresh()
                readPhoneUsage()
                update { it.copy(addresses = wifiAddresses(), network = Networks.current(ctx)) }
                val browserRecent = System.currentTimeMillis() - _status.value.lastBrowserAt < 120_000L
                delay(if (browserRecent) 2_000L else 15_000L)
            }
        }
        acquireWifiLock(ctx)
        loadNetworks(ctx)
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        connection?.disconnect()
        connection = null
        bridge = null
        scope?.cancel()
        scope = null
        pollJob = null
        runCatching { wifiLock?.release() }
        wifiLock = null
        update { it.copy(running = false) }
    }

    /** Re-discovers installations and re-reads access, for example after the approval screen closes. */
    fun recheck() {
        val ctx = context ?: return
        scope?.launch {
            selectInstall(ctx)
            pollNow()
            refreshUsage()
        }
    }

    fun choose(packageName: String) {
        val ctx = context ?: return
        Prefs(ctx).curboxPackage = packageName
        recheck()
    }

    fun newCode() {
        val ctx = context ?: return
        scope?.launch {
            val p = withContext(Dispatchers.IO) { Prefs(ctx).resetPairing() }
            pairing = p
            publishPairing(p)
            update { it.copy(lastEvent = "All browsers unpaired. Scan a new code to pair again.") }
        }
    }

    /** A browser showed its QR code; trust the code it carries. Returns false if it is not a pairing link. */
    fun pairFromLink(appContext: Context, link: String): Boolean {
        val code = parsePairingCode(link) ?: return false
        val ctx = context ?: appContext.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val p = Prefs(ctx).addBrowserCode(code)
            pairing = p
            publishPairing(p)
            update { it.copy(lastEvent = "Paired with a browser. It finds this phone in a few seconds.") }
        }
        return true
    }

    /** `curboxsync://pair?c=CODE`, shown by the extension. */
    fun parsePairingCode(link: String): String? {
        val uri = runCatching { android.net.Uri.parse(link.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "curboxsync" || uri.host != "pair") return null
        val code = LanCrypto.normalize(uri.getQueryParameter("c").orEmpty())
        return code.takeIf { it.length == LanCrypto.CODE_LENGTH }
    }

    private fun publishPairing(p: Pairing) {
        update { it.copy(code = LanCrypto.format(p.code), browsers = p.browserCount) }
    }

    private fun selectInstall(ctx: Context) {
        val conn = connection ?: return
        val prefs = Prefs(ctx)
        val installs = conn.installs()
        val saved = prefs.curboxPackage?.takeIf { pkg -> installs.any { it.packageName == pkg } }
        val chosen = saved ?: installs.singleOrNull()?.packageName
        if (chosen != null && chosen != prefs.curboxPackage) prefs.curboxPackage = chosen
        update { it.copy(installs = installs, selectedPackage = chosen) }
        if (chosen == null) conn.disconnect() else conn.connect(chosen)
    }

    private fun pollNow() {
        val b = bridge ?: return
        scope?.launch(Dispatchers.IO) { b.refresh() }
    }

    private fun update(change: (SyncStatus) -> SyncStatus) {
        synchronized(_status) { _status.value = change(_status.value) }
    }

    /** Opens a sealed browser request and returns the sealed reply, or null to answer 401. */
    private suspend fun handleRpc(blob: String): String? {
        val keys = pairing?.allKeys ?: return null
        val b = bridge ?: return null
        var key: ByteArray? = null
        var req: JSONObject? = null
        for (candidate in keys) {
            req = runCatching { JSONObject(LanCrypto.open(candidate, LanCrypto.REQUEST_AAD, blob)) }.getOrNull()
            if (req != null) {
                key = candidate
                break
            }
        }
        if (req == null || key == null) return null
        val id = req.optString("id")
        val now = System.currentTimeMillis()
        if (id.isEmpty() || id.length > 64 || abs(now - req.optLong("ts")) > CLOCK_WINDOW_MS) return null
        if (!rememberId(id, now)) return null
        update { it.copy(lastBrowserAt = now) }
        val ctx = context
        val net = ctx?.let { withContext(Dispatchers.IO) { Networks.current(it) } }
        if (ctx != null && net != null) {
            val saved = withContext(Dispatchers.IO) { networks(ctx).touch(net, now) }
            update { it.copy(network = net, networks = saved) }
        }

        val reply = JSONObject().put("ok", true)
        when (req.optString("op")) {
            "state" -> withContext(Dispatchers.IO) { b.refresh() }
            "wait" -> {
                val since = req.optLong("since", -1)
                val wait = req.optLong("wait", MAX_WAIT_MS).coerceIn(0, MAX_WAIT_MS)
                withTimeoutOrNull(wait) { b.state.first { it.rev != since } }
            }
            "focus" -> {
                val desired = DesiredFocus.parse(req.optJSONObject("focus") ?: JSONObject())
                val result = withContext(Dispatchers.IO) { b.apply(desired) }
                reply.put("result", result)
                val what = if (desired.active) "start ${desired.name}" else "stop focus"
                update { it.copy(lastEvent = "Browser asked to $what: $result") }
            }
            "usage" -> {
                val store = ctx?.let { usage(it) }
                val client = req.optJSONObject("client") ?: JSONObject()
                val clientId = client.optString("id").take(64)
                if (store == null || clientId.isEmpty()) {
                    reply.put("ok", false).put("error", "bad_client")
                } else {
                    val days = UsageStore.parseBrowserDays(req.optJSONArray("days"))
                    val known = withContext(Dispatchers.IO) { store.putBrowser(clientId, client.optString("name").take(60), days) }
                    reply.put("known", JSONObject().apply { known.forEach { (d, ms) -> put(d, ms) } })
                }
            }
            else -> reply.put("ok", false).put("error", "unknown_op")
        }
        if (ctx != null && net != null) {
            reply.put("net", JSONObject().put("id", net.id).put("label", networks(ctx).labelFor(net)).put("address", net.address))
        }
        reply.put("phone", b.state.value.toJson()).put("now", System.currentTimeMillis())
        return LanCrypto.seal(key, LanCrypto.responseAad(id), reply.toString())
    }

    /** Each sealed request is accepted once, so a captured request cannot be replayed. */
    private fun rememberId(id: String, now: Long): Boolean = synchronized(seenIds) {
        val it = seenIds.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value > 2 * CLOCK_WINDOW_MS || seenIds.size > 4_000) it.remove() else break
        }
        seenIds.put(id, now) == null
    }

    private fun wifiAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filter { n -> listOf("wlan", "ap", "swlan", "eth").any { n.name.startsWith(it) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { "${it.hostAddress}:${LanServer.PORT}" }
            .distinct()
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun acquireWifiLock(ctx: Context) {
        val wifi = ctx.getSystemService(WifiManager::class.java) ?: return
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = runCatching {
            wifi.createWifiLock(mode, "curboxsync:lan").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
    }
}
