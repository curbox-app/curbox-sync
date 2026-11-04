package neth.iecal.curboxsync

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
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
    /** Browsers paired with another of this user's phones, which reach this one through it. */
    val relayedBrowsers: Int = 0,
    val lastBrowserAt: Long = 0,
    val lastEvent: String = "",
    val error: String? = null,
    val network: CurrentNetwork? = null,
    val networks: List<SavedNetwork> = emptyList(),
    /** Curbox keeps this app running, so there is no notification. */
    val hosted: Boolean = false,
    /** Why Curbox cannot host this app yet, or null. */
    val hostHint: String? = null,
    /** Other phones this one syncs with, or that sync with it. */
    val phones: List<PeerStatus> = emptyList(),
    /** This phone's Tailscale address, while Tailscale is on. */
    val tailnet: String? = null,
    val tailscaleInstalled: Boolean = false,
    /** A tap on Turn on is waiting for Tailscale to connect. */
    val tailscaleStarting: Boolean = false,
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
    private const val OWN_USAGE_MAX_AGE_MS = 5 * 60_000L

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    private var context: Context? = null
    private var scope: CoroutineScope? = null
    private var connection: CurboxConnection? = null
    private var bridge: FocusBridge? = null
    private var server: LanServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** True while the app is on screen; short codes are accepted only then. */
    @Volatile var pairingOpen = false
    private val pairLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var pairSecret = PairCode.newSecret()
    private var pairKeys = lazy { PairCode.newKeyPair() }
    private var pairFailures = 0
    private val netCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
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
            update {
                it.copy(
                    networks = n.all(), network = Networks.current(ctx),
                    tailnet = Tailscale.address(), tailscaleInstalled = Tailscale.installed(ctx),
                )
            }
        }
    }

    /**
     * One tap: asks Tailscale to connect in the background, and opens it only when it needs the user
     * (first sign in, or the VPN prompt).
     */
    fun turnOnTailscale(appContext: Context) {
        val ctx = appContext.applicationContext
        if (!Tailscale.installed(ctx)) return Tailscale.get(ctx)
        if (Tailscale.address() != null) return Tailscale.open(ctx)
        update { it.copy(tailscaleStarting = true) }
        Tailscale.connect(ctx)
        CoroutineScope(Dispatchers.IO).launch {
            val up = withTimeoutOrNull(4_000L) {
                while (Tailscale.address() == null) delay(250)
                true
            } ?: false
            update { it.copy(tailscaleStarting = false, tailnet = Tailscale.address()) }
            if (!up) withContext(Dispatchers.Main) { Tailscale.open(ctx) }
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

    /** Re-reads this phone's history from Curbox, and asks paired phones for theirs. */
    fun refreshUsage() {
        scope?.launch(Dispatchers.IO) { readPhoneUsage() }
        PeerSync.exchangeUsageSoon()
    }

    @Volatile private var usageReadAt = 0L

    /** This phone's recent days for another phone, re-read from Curbox when a few minutes old. */
    fun ownUsageJson(appContext: Context): JSONArray {
        if (System.currentTimeMillis() - usageReadAt > OWN_USAGE_MAX_AGE_MS) readPhoneUsage()
        return usage(appContext).ownDaysJson()
    }

    private fun readPhoneUsage() {
        val ctx = context ?: return
        val api = connection?.api ?: return
        val phone = bridge?.state?.value ?: return
        if (phone.access != CurboxAccess.READY) return
        // Read when the screens open, and when a paired phone asks for it.
        usageRead = true
        usageReadAt = System.currentTimeMillis()
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
        acquireWifiLock(ctx)

        s.launch {
            val saved = withContext(Dispatchers.IO) { Prefs(ctx).pairing() }
            // A browser code scanned while this read ran is newer; keep it.
            val p = pairing ?: saved
            pairing = p
            publishPairing(p)
            selectInstall(ctx)
            try {
                server = LanServer(LanServer.PORT, s, ::handleRpc, ::handlePair).also { it.start() }
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
        PeerSync.start(ctx, s, b)
        s.launch { PeerSync.status.collect { phones -> update { it.copy(phones = phones) } } }
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
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            netCallbacks.forEach { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        }
        netCallbacks.clear()
        PeerSync.stop()
        server?.stop()
        server = null
        runCatching { wifiLock?.release() }
        wifiLock = null
        connection?.disconnect()
        connection = null
        bridge = null
        scope?.cancel()
        scope = null
        pollJob = null
        update { it.copy(running = false) }
    }

    /**
     * Without this, WiFi power saving drops or delays requests to this phone by seconds, often
     * past the browser's timeouts, so pairing and sync sit at Waiting. Low latency mode only
     * applies while the screen is on, so it costs little battery.
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock(ctx: Context) {
        if (wifiLock != null) return
        val wifi = ctx.getSystemService(WifiManager::class.java) ?: return
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = runCatching {
            wifi.createWifiLock(mode, "curboxsync:lan").apply { setReferenceCounted(false); acquire() }
        }.onFailure { Log.w(TAG, "WiFi lock unavailable", it) }.getOrNull()
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
        val addresses = wifiAddresses()
        update {
            it.copy(
                addresses = addresses, code = shownCode(addresses), network = Networks.current(ctx),
                tailnet = Tailscale.address(), tailscaleInstalled = Tailscale.installed(ctx),
            )
        }
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
        // Tailscale is a VPN; watching it shows the "works anywhere" state the moment it connects.
        val vpn = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        for (r in listOf(request, vpn)) {
            val callback = if (r === request) cb else object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = cb.onAvailable(network)
                override fun onLost(network: Network) = cb.onLost(network)
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                    cb.onLinkPropertiesChanged(network, linkProperties)
            }
            runCatching {
                cm.registerNetworkCallback(r, callback)
                netCallbacks += callback
            }.onFailure { Log.w(TAG, "Could not watch the network", it) }
        }
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
                PeerSync.exchangeUsageSoon()
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
            val p = withContext(Dispatchers.IO) {
                // Phones this one paired with go too, and are told so.
                PeerSync.peers(ctx).forEach { PeerSync.forget(ctx, it.id) }
                PeerSync.forgetClients()
                Prefs(ctx).resetPairing()
            }
            pairing = p
            publishPairing(p)
            rotatePairCode()
            PeerSync.exchangeUsageSoon()
            update { it.copy(lastEvent = "All browsers and phones are unpaired. Pair again with the new code.") }
        }
    }

    /** The code this phone shows: its WiFi address and the current secret, or empty off WiFi. */
    private fun shownCode(addresses: List<String>): String =
        addresses.firstNotNullOfOrNull { PairCode.forAddress(it, pairSecret) }?.let(PairCode::format).orEmpty()

    /** A new secret and key pair: after each pairing, too many wrong tries, or Unpair all. */
    private fun rotatePairCode() {
        pairSecret = PairCode.newSecret()
        pairKeys = lazy { PairCode.newKeyPair() }
        pairFailures = 0
        update { it.copy(code = shownCode(it.addresses)) }
    }

    /** Pairs with another phone from the code it shows. Returns a note for the user. */
    suspend fun pairWithPhone(appContext: Context, code: String): String {
        val ctx = context ?: appContext.applicationContext
        val own = wifiAddresses()
        if (own.any { PairCode.forAddress(it, pairSecret) == PairCode.normalize(code) }) return "That is this phone's own code."
        val note = PeerSync.pairWithCode(ctx, code, own)
        update { it.copy(lastEvent = note) }
        return note
    }

    /**
     * `GET /v1/pair` hands out this pairing's public key; `POST /v1/pair` checks the typed code's
     * proof, agrees on a key and saves it. See [PairCode].
     */
    private suspend fun handlePair(method: String, body: String?): Pair<Int, String> = pairLock.withLock {
        val ctx = context ?: return@withLock 503 to ""
        if (!pairingOpen) return@withLock 403 to """{"error":"closed"}"""
        val keys = pairKeys.value
        val pub = PairCode.publicKey(keys)
        if (method == "GET") return@withLock 200 to JSONObject().put("pub", pub).toString()
        val req = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return@withLock 400 to ""
        val otherPub = req.optString("pub").take(512)
        val proof = req.optString("proof")
        val code = wifiAddresses().mapNotNull { PairCode.forAddress(it, pairSecret) }.firstOrNull { c ->
            PairCode.sameProof(proof, PairCode.proof(PairCode.codeKey(c), 'b', pub, otherPub))
        }
        if (code == null || otherPub.isEmpty()) {
            if (++pairFailures >= PairCode.MAX_FAILURES) {
                rotatePairCode()
                update { it.copy(lastEvent = "Too many wrong codes, so this phone shows a new one.") }
            }
            return@withLock 401 to ""
        }
        val kCode = PairCode.codeKey(code)
        val key = runCatching { PairCode.sessionKey(PairCode.shared(keys, otherPub), kCode, pub, otherPub) }.getOrNull()
            ?: return@withLock 400 to ""
        val from = req.optJSONObject("from")
        val isPhone = from?.optString("kind") == "phone"
        val p = withContext(Dispatchers.IO) {
            Prefs(ctx).run {
                addKey(key, phone = isPhone).also { _ -> from?.optString("id")?.take(32)?.takeIf { it.isNotEmpty() }?.let { linkKey(it, key) } }
            }
        }
        pairing = p
        publishPairing(p)
        if (isPhone) PeerSync.noteClient(from) else PeerSync.exchangeUsageSoon()
        val name = from?.optString("name")?.take(60)?.ifEmpty { null } ?: if (isPhone) "a phone" else "a browser"
        update { it.copy(lastEvent = "Paired with $name.") }
        rotatePairCode()
        200 to JSONObject()
            .put("proof", PairCode.proof(kCode, 'p', pub, otherPub))
            .put("id", PeerSync.deviceId(ctx))
            .put("name", PeerSync.deviceName())
            .toString()
    }

    private fun publishPairing(p: Pairing) {
        update { it.copy(browsers = p.browserCount, relayedBrowsers = p.relayedCount) }
    }

    /** Re-reads the keys after another phone shared or withdrew its browsers. */
    fun reloadPairing(appContext: Context) {
        if (pairing == null) return
        val p = Prefs(appContext.applicationContext).pairing()
        pairing = p
        publishPairing(p)
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
        val from = req.optJSONObject("from")
        val fromPhone = from?.optString("kind") == "phone"
        val fromId = from?.optString("id")?.take(32).orEmpty()
        if (!fromPhone) update { it.copy(lastBrowserAt = now) }
        val ctx = context
        if (ctx != null && fromPhone && fromId.isNotEmpty() && !key.contentEquals(pairing?.key)) {
            // Phones paired before keys were linked to devices get linked on their next request.
            withContext(Dispatchers.IO) { Prefs(ctx).run { if (!ownsKey(fromId)) linkKey(fromId, key) } }
        }
        if (req.optString("op") != "unpair") PeerSync.noteClient(from)
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
            // Proto 3: the full website lists, fetched when `groupsHash` changes.
            "groups" -> {
                val phone = withContext(Dispatchers.IO) { b.refresh() }
                val apps = req.optBoolean("apps")
                reply.put("groups", JSONArray().apply { phone.groups.forEach { put(it.toJson(full = true, withApps = apps)) } })
            }
            "saveGroups" -> {
                val edits = req.optJSONArray("save")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(GroupEdit::parse) } }.orEmpty()
                val deletes = req.optJSONArray("delete")?.let { a -> (0 until a.length()).map { a.optString(it).take(200) }.filter { it.isNotEmpty() } }.orEmpty()
                val results = withContext(Dispatchers.IO) { b.saveGroups(edits, deletes) }
                reply.put("results", JSONObject().apply { results.forEach { (id, r) -> put(id, r) } })
                val apps = req.optBoolean("apps")
                reply.put("groups", JSONArray().apply { b.state.value.groups.forEach { put(it.toJson(full = true, withApps = apps)) } })
                val changed = results.count { it.value == "applied" }
                if (changed > 0) update { it.copy(lastEvent = "Browser updated $changed focus group${if (changed == 1) "" else "s"}") }
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
            // Another phone swaps usage: it sends its own days and gets this phone's back.
            "phoneUsage" -> {
                if (ctx == null || !fromPhone || fromId.isEmpty()) {
                    reply.put("ok", false).put("error", "bad_client")
                } else {
                    val days = UsageStore.parsePeerDays(req.optJSONArray("days"))
                    withContext(Dispatchers.IO) {
                        val store = usage(ctx)
                        store.putPhone(fromId, from?.optString("name").orEmpty().take(40), days)
                        store.putRelayedBrowsers(fromId, UsageStore.parseBrowsers(req.optJSONArray("browsers")))
                        reply.put("days", ownUsageJson(ctx)).put("browsers", store.ownBrowsersJson())
                    }
                }
            }
            // Paired phones swap their browsers' keys, so a browser paired with one reaches both.
            "browsers" -> {
                if (ctx == null || !fromPhone || fromId.isEmpty()) {
                    reply.put("ok", false).put("error", "bad_client")
                } else {
                    val keys = req.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.optString(it).take(64) } }.orEmpty()
                    withContext(Dispatchers.IO) {
                        val prefs = Prefs(ctx)
                        prefs.setRelayed(fromId, keys)
                        reloadPairing(ctx)
                        reply.put("keys", JSONArray(prefs.browserKeysB64()))
                    }
                }
            }
            // The other phone forgot this one: drop its key and stop syncing with it here too.
            "unpair" -> if (ctx != null && fromPhone && fromId.isNotEmpty()) {
                val name = from?.optString("name").orEmpty().ifEmpty { "A phone" }
                withContext(Dispatchers.IO) { PeerSync.forget(ctx, fromId, notify = false) }
                update { it.copy(lastEvent = "$name unpaired from this phone.") }
            }
            else -> reply.put("ok", false).put("error", "unknown_op")
        }
        if (ctx != null && net != null) {
            reply.put("net", JSONObject().put("id", net.id).put("label", networks(ctx).labelFor(net)).put("address", net.address))
        }
        if (ctx != null) reply.put("device", JSONObject().put("id", PeerSync.deviceId(ctx)).put("name", PeerSync.deviceName()))
        if (fromPhone) pairing?.let { reply.put("browsersHash", it.browsersHash) }
        // Lets the browser keep reaching this phone away from the shared WiFi.
        Tailscale.address()?.let { reply.put("tailnet", it) }
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

    fun wifiAddresses(): List<String> = runCatching {
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
