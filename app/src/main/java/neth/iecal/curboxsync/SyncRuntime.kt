package neth.iecal.curboxsync

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import neth.iecal.curbox.api.ICurboxListener
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
    /** Curbox keeps this app running, so there is no notification. */
    val hosted: Boolean = false,
    /** Why Curbox cannot host this app yet, or null. */
    val hostHint: String? = null,
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
    // With Curbox's change callback, reads are only a safety net.
    private const val PUSHED_POLL_MS = 5 * 60_000L

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    private var context: Context? = null
    private var scope: CoroutineScope? = null
    private var connection: CurboxConnection? = null
    private var bridge: FocusBridge? = null
    private var server: LanServer? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    /** Curbox calls back on focus changes (API 6), so the fast poll is not needed. */
    @Volatile private var listening = false
    @Volatile private var hostAsked = false
    private var pollJob: Job? = null
    @Volatile private var pairing: Pairing? = null
    private val seenIds = LinkedHashMap<String, Long>()
    @Volatile private var usageStore: UsageStore? = null
    @Volatile private var networks: Networks? = null
    @Volatile private var usageRead = false

    private val listener = object : ICurboxListener.Stub() {
        override fun onChanged(what: String?) = pollNow()
    }

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
        scope?.launch(Dispatchers.IO) { readPhoneUsage() }
    }

    private fun readPhoneUsage() {
        val ctx = context ?: return
        val api = connection?.api ?: return
        val phone = bridge?.state?.value ?: return
        if (phone.access != CurboxAccess.READY) return
        // Only the screens here show phone usage, so it is read when they open, not in the background.
        usageRead = true
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
        s.launch {
            b.state.collect { phone ->
                update { it.copy(phone = phone) }
                if (phone.access == CurboxAccess.READY) withContext(Dispatchers.IO) { onCurboxReady(ctx, phone) }
            }
        }
        s.launch {
            conn.connected.collect { up ->
                if (!up) {
                    listening = false
                    hostAsked = false
                }
                pollNow()
            }
        }
        pollJob = s.launch(Dispatchers.IO) {
            while (isActive) {
                b.refresh()
                if (!listening) refreshNetwork(ctx)
                val now = System.currentTimeMillis()
                val browserRecent = now - _status.value.lastBrowserAt < 120_000L
                val every = when {
                    listening -> PUSHED_POLL_MS
                    browserRecent -> 2_000L
                    else -> 15_000L
                }
                // Reaching the end time is not a change Curbox reports, so wake for it.
                val phone = b.state.value
                val untilEnd = if (phone.active) (phone.endsAt - now + 1_500L).coerceAtLeast(1_000L) else Long.MAX_VALUE
                delay(minOf(every, untilEnd))
            }
        }
        watchNetwork(ctx)
        loadNetworks(ctx)
    }

    /**
     * Stops the engine. [releaseHost] also tells Curbox to stop keeping this app running, which is
     * what turning sync off means; a hand over between Curbox and the foreground service does not.
     */
    @Synchronized
    fun stop(releaseHost: Boolean = false) {
        val api = connection?.api
        if (api != null) {
            // The proxy stays usable after unbinding, so these finish on their own.
            Thread {
                runCatching { if (listening) api.removeListener(listener) }
                if (releaseHost && _status.value.phone.apiVersion >= 6) {
                    runCatching { api.execute("HOST_COMPANION", Bundle().apply { putBoolean("enable", false) }) }
                }
            }.start()
        }
        listening = false
        hostAsked = false
        context?.let { ctx ->
            netCallback?.let { cb -> runCatching { ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        }
        netCallback = null
        server?.stop()
        server = null
        connection?.disconnect()
        connection = null
        bridge = null
        scope?.cancel()
        scope = null
        pollJob = null
        update { it.copy(running = false) }
    }

    fun setHosted(on: Boolean) = update { it.copy(hosted = on, hostHint = if (on) null else it.hostHint) }

    /** Subscribes to Curbox's changes and asks it to host this app, once per connection. */
    private fun onCurboxReady(ctx: Context, phone: PhoneFocus) {
        val api = connection?.api ?: return
        if (!usageRead) readPhoneUsage()
        if (phone.apiVersion < 6) {
            update { it.copy(hostHint = "Update Curbox to run without a notification") }
            return
        }
        if (!listening) {
            listening = runCatching { api.addListener(listener) }.isSuccess
        }
        if (hostAsked || SyncService.hosted) return
        val power = ctx.getSystemService(PowerManager::class.java)
        if (power?.isIgnoringBatteryOptimizations(ctx.packageName) != true) {
            // Without it the hosted process loses WiFi in Doze and cannot fall back on its own.
            update { it.copy(hostHint = "Allow background use so Curbox can run sync without a notification") }
            return
        }
        hostAsked = true
        val result = runCatching { api.execute("HOST_COMPANION", Bundle().apply { putBoolean("enable", true) }) }.getOrNull()
        update {
            it.copy(hostHint = if (result == "OK" && !it.hosted) "Turn on Curbox's accessibility service to run without a notification" else null)
        }
    }

    private fun refreshNetwork(ctx: Context) {
        update { it.copy(addresses = wifiAddresses(), network = Networks.current(ctx)) }
    }

    /** Network changes arrive as callbacks instead of being polled. */
    private fun watchNetwork(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = changed()
            private fun changed() {
                scope?.launch(Dispatchers.IO) { refreshNetwork(ctx) }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, cb)
            netCallback = cb
        }.onFailure { Log.w(TAG, "Could not watch the network", it) }
    }

    /** Re-discovers installations and re-reads access, for example after the approval screen closes. */
    fun recheck() {
        val ctx = context ?: return
        scope?.launch {
            selectInstall(ctx)
            // Back from the battery screen, for example; ask Curbox again.
            hostAsked = false
            withContext(Dispatchers.IO) {
                val phone = bridge?.refresh()
                if (phone?.access == CurboxAccess.READY) onCurboxReady(ctx, phone)
                readPhoneUsage()
                refreshNetwork(ctx)
            }
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
}
