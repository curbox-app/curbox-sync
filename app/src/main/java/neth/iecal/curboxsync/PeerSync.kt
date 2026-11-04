package neth.iecal.curboxsync

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** Another phone this one syncs with. This phone drives it, like a browser does. */
data class Peer(val id: String, val name: String, val address: String, val key: String, val places: Map<String, String> = emptyMap())

data class PeerStatus(
    val id: String,
    val name: String,
    /** This phone drives the sync; false for a phone that drives this one. */
    val outgoing: Boolean,
    val reachable: Boolean,
    val lastOkAt: Long,
    val message: String = "",
)

/** Where a peer's Tailscale address is kept among its saved places. */
private const val TAILNET = "tailnet"

/**
 * Phone to phone focus, Pomodoro and focus group sync. The phone that typed the other's code
 * reconciles against it with the same rules the extension uses (see PROTOCOL.md). Each phone is
 * reconciled against its own Curbox, so a browser paired with one phone reaches the other too.
 */
@SuppressLint("StaticFieldLeak") // Holds only the application context.
object PeerSync {
    private const val TAG = "PeerSync"
    private const val PREFS = "curbox_sync_peers"
    /** A phone that drove this one this recently is treated as connected. */
    private const val CLIENT_RECENT_MS = 2 * 60_000L

    private val _status = MutableStateFlow<List<PeerStatus>>(emptyList())
    val status: StateFlow<List<PeerStatus>> = _status

    private var context: Context? = null
    private var scope: CoroutineScope? = null
    private var bridge: FocusBridge? = null
    private val engines = LinkedHashMap<String, PeerEngine>()
    private val outgoing = LinkedHashMap<String, PeerStatus>()
    private val clients = LinkedHashMap<String, PeerStatus>()

    // ---- device identity, shared in every request so the other phone can show who it is

    fun deviceId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val id = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        prefs.edit { putString("device_id", id) }
        return id
    }

    fun deviceName(): String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim().take(40)

    // ---- saved peers

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun peers(ctx: Context): List<Peer> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("peers", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val places = o.optJSONObject("places")?.let { p -> p.keys().asSequence().associateWith { p.getString(it) } }.orEmpty()
            Peer(o.getString("id"), o.optString("name"), o.getString("address"), o.getString("key"), places)
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun savePeer(ctx: Context, peer: Peer?, removeId: String? = null) {
        val list = peers(ctx).filter { it.id != (peer?.id ?: removeId) } + listOfNotNull(peer)
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("address", p.address).put("key", p.key)
                .put("places", JSONObject(p.places)))
        }
        prefs(ctx).edit { putString("peers", arr.toString()) }
    }

    internal fun updatePeer(ctx: Context, peer: Peer) = savePeer(ctx, peer)

    internal fun loadState(ctx: Context, id: String): JSONObject =
        runCatching { JSONObject(prefs(ctx).getString("state_$id", "{}")!!) }.getOrDefault(JSONObject())

    internal fun saveState(ctx: Context, id: String, state: JSONObject) = prefs(ctx).edit { putString("state_$id", state.toString()) }

    // ---- short code pairing, as a browser does it (see PairCode)

    /** Pairs with the phone showing [code] and starts syncing with it. Returns a note for the user. */
    suspend fun pairWithCode(ctx: Context, code: String, ownAddresses: List<String>): String = withContext(Dispatchers.IO) {
        if (PairCode.decode(code) == null) return@withContext "The code has 6 letters and numbers."
        val found = firstPairing(PairCode.candidates(code, ownAddresses))
            ?: return@withContext "Could not find that phone. Open Curbox Sync on it, on the same WiFi."
        if (found.second == null) return@withContext "Open Curbox Sync on the other phone, then type the code again."
        val (address, phonePub) = found.first to found.second!!
        val kCode = PairCode.codeKey(code)
        val keys = PairCode.newKeyPair()
        val pub = PairCode.publicKey(keys)
        val body = JSONObject()
            .put("pub", pub)
            .put("proof", PairCode.proof(kCode, 'b', phonePub, pub))
            .put("from", JSONObject().put("id", deviceId(ctx)).put("name", deviceName()).put("kind", "phone"))
        val (status, text) = runCatching { LanClient.plain("POST", address, "/v1/pair", body.toString()) }.getOrNull()
            ?: return@withContext "That phone stopped answering. Try again."
        when (status) {
            200 -> {}
            401 -> return@withContext "That code did not match. Check it and try again."
            403 -> return@withContext "Open Curbox Sync on the other phone, then type the code again."
            else -> return@withContext "That phone answered in a way I did not expect."
        }
        val reply = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext "That phone answered in a way I did not expect."
        if (!PairCode.sameProof(reply.optString("proof"), PairCode.proof(kCode, 'p', phonePub, pub))) {
            return@withContext "That phone could not prove it showed this code."
        }
        val key = LanCrypto.toBase64Url(PairCode.sessionKey(PairCode.shared(keys, phonePub), kCode, phonePub, pub))
        val id = reply.optString("id").take(32).ifEmpty { ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) } }
        val name = reply.optString("name").take(40).ifEmpty { "Phone" }
        val old = peers(ctx).firstOrNull { it.id == id }
        val peer = Peer(id, name, address, key, old?.places.orEmpty())
        savePeer(ctx, peer)
        // A new key means a fresh start; the old agreed view no longer applies.
        prefs(ctx).edit { remove("state_$id") }
        withContext(Dispatchers.Main) { restartEngine(peer, listOf(address)) }
        "Paired with $name. Focus, Pomodoro and focus groups now stay in step."
    }

    /**
     * Asks every candidate at once; returns the first that answered with its public key, or a
     * candidate with a null key when a phone answered but its app is closed.
     */
    private suspend fun firstPairing(candidates: List<String>): Pair<String, String?>? = coroutineScope {
        val answers = Channel<Pair<String, String?>?>(Channel.UNLIMITED)
        // Detached: a blocking socket ignores cancel, and the first answer should not wait for the rest.
        val probes = CoroutineScope(Dispatchers.IO)
        candidates.forEach { address ->
            probes.launch {
                val r = runCatching { LanClient.plain("GET", address, "/v1/pair", null, 3_000) }.getOrNull()
                answers.send(
                    when (r?.first) {
                        200 -> runCatching { JSONObject(r.second).optString("pub") }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { address to it }
                        403 -> address to null
                        else -> null
                    }
                )
            }
        }
        var closed: Pair<String, String?>? = null
        repeat(candidates.size) {
            val a = answers.receive() ?: return@repeat
            if (a.second != null) {
                probes.cancel()
                return@coroutineScope a
            }
            closed = a
        }
        closed
    }

    /**
     * Stops syncing with a phone, whichever side paired: drops its saved address, the key it used
     * to reach this phone, and its usage. With [notify] the other phone is told, so it forgets this
     * one too instead of trying with a key that no longer opens.
     */
    fun forget(ctx: Context, id: String, notify: Boolean = true) {
        val app = ctx.applicationContext
        val peer = peers(app).firstOrNull { it.id == id }
        synchronized(engines) { engines.remove(id) }?.stop()
        savePeer(app, null, removeId = id)
        prefs(app).edit { remove("state_$id") }
        synchronized(outgoing) { outgoing.remove(id) }
        synchronized(clients) { clients.remove(id) }
        Prefs(app).run {
            removeDevice(id)
            setRelayed(id, emptyList())
        }
        SyncRuntime.reloadPairing(app)
        SyncRuntime.usage(app).forgetPhone(id)
        publish()
        if (notify && peer != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    LanClient.rpc(peer.address, LanCrypto.fromBase64Url(peer.key), JSONObject().put("op", "unpair")
                        .put("from", JSONObject().put("id", deviceId(app)).put("name", deviceName()).put("kind", "phone")), 4_000)
                }.onFailure { Log.i(TAG, "Could not tell ${peer.name} about the unpair: ${it.message}") }
            }
        }
    }

    /** Phones that reached this one stop showing, for Unpair all. */
    fun forgetClients() {
        synchronized(clients) { clients.clear() }
        publish()
    }

    /** Asks every engine to swap usage now, for example when the usage screen opens. */
    fun exchangeUsageSoon() = synchronized(engines) { engines.values.toList() }.forEach { it.exchangeUsageSoon() }

    // ---- lifecycle, tied to SyncRuntime

    fun start(ctx: Context, s: CoroutineScope, b: FocusBridge) {
        context = ctx.applicationContext
        scope = s
        bridge = b
        peers(ctx).forEach { restartEngine(it, emptyList()) }
    }

    fun stop() {
        synchronized(engines) {
            engines.values.forEach { it.stop() }
            engines.clear()
        }
        scope = null
        bridge = null
        synchronized(outgoing) { outgoing.clear() }
        publish()
    }

    private fun restartEngine(peer: Peer, hints: List<String>) {
        val ctx = context ?: return
        val s = scope ?: return
        val b = bridge ?: return
        val engine = PeerEngine(ctx, s, b, peer, hints)
        synchronized(engines) {
            engines.remove(peer.id)?.stop()
            engines[peer.id] = engine
        }
        engine.start()
    }

    /** Called by the server for each request another phone sent. */
    fun noteClient(from: JSONObject?) {
        val id = from?.optString("id")?.take(32)?.takeIf { it.isNotEmpty() } ?: return
        if (from.optString("kind") != "phone") return
        synchronized(clients) {
            clients[id] = PeerStatus(id, from.optString("name").take(40).ifEmpty { "Phone" }, outgoing = false, reachable = true, lastOkAt = System.currentTimeMillis())
        }
        publish()
    }

    /**
     * When both phones paired with each other, only the one with the smaller id drives, so the pair is
     * not reconciled twice.
     */
    internal fun yieldsTo(ctx: Context, peerId: String): Boolean {
        val seen = synchronized(clients) { clients[peerId] } ?: return false
        return System.currentTimeMillis() - seen.lastOkAt < CLIENT_RECENT_MS && deviceId(ctx) > peerId
    }

    internal fun report(status: PeerStatus) {
        // A stopped engine's blocking call can return late; it must not bring a forgotten phone back.
        if (synchronized(engines) { status.id !in engines }) return
        synchronized(outgoing) { outgoing[status.id] = status }
        publish()
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        val mine = synchronized(outgoing) { outgoing.values.toList() }
        val theirs = synchronized(clients) { clients.values.filter { c -> now - c.lastOkAt < 24 * 60 * 60_000L && mine.none { it.id == c.id } } }
        _status.value = mine + theirs.map { it.copy(reachable = now - it.lastOkAt < CLIENT_RECENT_MS) }
    }

    internal fun tag() = TAG
}

/** The other phone's state from a reply, as [FocusBridge] would have read it there. */
fun phoneFromJson(o: JSONObject): PhoneFocus = PhoneFocus(
    access = if (o.optBoolean("connected")) CurboxAccess.READY else CurboxAccess.CONNECTING,
    apiVersion = o.optInt("apiVersion"),
    active = o.optBoolean("active"),
    groupId = o.optString("groupId"),
    name = o.optString("name"),
    endsAt = o.optLong("endsAt"),
    exitable = o.optBoolean("exitable", true),
    groups = o.optJSONArray("groups")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(PhoneGroup::fromJson) } }.orEmpty(),
    pomodoro = Pomodoro.parse(o.optJSONObject("pomodoro")),
    changedAt = o.optLong("changedAt"),
    rev = o.optLong("rev"),
)

private fun FocusView.toJson(): JSONObject = JSONObject()
    .put("active", active).put("groupId", groupId).put("name", name).put("endsAt", endsAt)
    .apply { phase?.let { put("phase", it) } }
    .apply {
        cycle?.let {
            put("pomodoro", JSONObject().put("phase", phase ?: "focus").put("focusMinutes", it.focusMinutes)
                .put("breakMinutes", it.breakMinutes).put("rounds", it.rounds).put("completed", it.completed))
        }
    }

private fun viewFromJson(o: JSONObject?): FocusView? {
    if (o == null) return null
    val c = o.optJSONObject("pomodoro")
    return FocusView(
        active = o.optBoolean("active"),
        groupId = o.optString("groupId"),
        name = o.optString("name"),
        endsAt = o.optLong("endsAt"),
        phase = if (o.has("phase")) o.optString("phase") else null,
        cycle = c?.let { Cycle(it.optInt("focusMinutes"), it.optInt("breakMinutes"), it.optInt("rounds"), it.optInt("completed")) },
    )
}

private fun SharedGroup.toJson(): JSONObject = JSONObject()
    .put("name", name).put("blockMode", blockMode).put("exitable", exitable).put("websites", JSONArray(websites))
    .apply { apps?.let { put("apps", JSONArray(it)) } }

private fun sharedFromJson(o: JSONObject) = SharedGroup(
    name = o.optString("name"),
    blockMode = o.optString("blockMode"),
    exitable = o.optBoolean("exitable", true),
    websites = o.optJSONArray("websites")?.strings().orEmpty(),
    apps = o.optJSONArray("apps")?.strings(),
)

private fun GroupEdit.toJson(): JSONObject = JSONObject()
    .put("id", id).apply { name?.let { put("name", it) } }
    .apply { websites?.let { put("websites", JSONArray(it)) } }
    .apply { blockMode?.let { put("blockMode", it) } }
    .apply { exitable?.let { put("exitable", it) } }
    .apply { apps?.let { put("apps", JSONArray(it)) } }

private const val USAGE_EVERY_MS = 10 * 60_000L
private const val USAGE_RETRY_MS = 2 * 60_000L
private const val USAGE_UNSUPPORTED_MS = 60 * 60_000L

/** Syncs this phone with one other phone. */
private class PeerEngine(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val bridge: FocusBridge,
    private var peer: Peer,
    hints: List<String>,
) {
    private val lock = Mutex()
    private var job: Job? = null
    private val key = LanCrypto.fromBase64Url(peer.key)
    private val hints = hints.toMutableList()
    private var lastSweepAt = 0L
    private var lastOkAt = 0L
    @Volatile private var stopped = false
    private val usageNow = Channel<Unit>(Channel.CONFLATED)

    // Persisted: the last agreed views, the phone's revision, and the group links.
    private val state = PeerSync.loadState(ctx, peer.id)

    fun start() {
        lastOkAt = state.optLong("lastOkAt")
        report(reachable = false, message = "Looking for ${peer.name}")
        job = scope.launch(Dispatchers.IO) {
            launch { pollLoop() }
            launch { usageLoop() }
            launch {
                // This phone's own Curbox changed: share it right away.
                bridge.state.map { it.rev }.distinctUntilChanged().drop(1).collectLatest {
                    delay(300)
                    if (!PeerSync.yieldsTo(ctx, peer.id)) runCatching { reconcile(call(JSONObject().put("op", "state"))) }
                }
            }
        }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        job = null
    }

    fun exchangeUsageSoon() {
        usageNow.trySend(Unit)
    }

    /**
     * Swaps usage with the other phone: this one's recent days go there and its days come back, so
     * both usage screens show both phones. Every few minutes, or right away when a screen opens.
     */
    private suspend fun usageLoop() {
        delay(5_000)
        while (true) {
            // Shares the saved state with reconcile, so both take the lock.
            val wait = runCatching { lock.withLock { exchangeUsage().also { PeerSync.saveState(ctx, peer.id, state) } } }.fold(
                onSuccess = { if (it) USAGE_EVERY_MS else USAGE_UNSUPPORTED_MS },
                onFailure = { e -> if (e is kotlinx.coroutines.CancellationException) throw e; USAGE_RETRY_MS },
            )
            withTimeoutOrNull(wait) { usageNow.receive() }
        }
    }

    /** False when the other phone runs a version without usage sharing. */
    private fun exchangeUsage(): Boolean {
        shareBrowsers()
        val store = SyncRuntime.usage(ctx)
        val days = SyncRuntime.ownUsageJson(ctx)
        val reply = call(JSONObject().put("op", "phoneUsage").put("days", days).put("browsers", store.ownBrowsersJson()), 15_000)
        if (reply.optString("error") == "unknown_op") return false
        if (!reply.has("days") || stopped) return true
        store.putPhone(peer.id, peer.name, UsageStore.parsePeerDays(reply.optJSONArray("days")))
        store.putRelayedBrowsers(peer.id, UsageStore.parseBrowsers(reply.optJSONArray("browsers")))
        return true
    }

    /** Gives the other phone this one's browser keys and takes its own, so browsers reach both. */
    private fun shareBrowsers() {
        val prefs = Prefs(ctx)
        val reply = call(JSONObject().put("op", "browsers").put("keys", JSONArray(prefs.browserKeysB64())))
        val keys = reply.optJSONArray("keys") ?: return
        if (stopped) return
        prefs.setRelayed(peer.id, (0 until keys.length()).map { keys.optString(it).take(64) })
        SyncRuntime.reloadPairing(ctx)
        state.put("browsersHash", reply.optString("browsersHash"))
    }

    private fun report(reachable: Boolean, message: String = "") =
        if (stopped) Unit else PeerSync.report(PeerStatus(peer.id, peer.name, outgoing = true, reachable = reachable, lastOkAt = lastOkAt, message = message))

    private suspend fun pollLoop() = coroutineScope {
        var failures = 0
        while (isActive) {
            if (PeerSync.yieldsTo(ctx, peer.id)) {
                // The other phone drives this pair.
                lastOkAt = System.currentTimeMillis()
                report(reachable = true)
                delay(30_000)
                continue
            }
            try {
                val reply = call(JSONObject().put("op", "wait").put("since", state.optLong("rev", -1)).put("wait", 25_000), 35_000)
                reconcile(reply)
                failures = 0
            } catch (e: LanClient.Rejected) {
                report(reachable = false, message = "${peer.name} unpaired this phone. Forget it, or pair again with its code.")
                delay(60_000)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failures++
                report(reachable = false, message = "Looking for ${peer.name}. Both phones need the same WiFi.")
                if (!find()) delay(minOf(5_000L * failures, 60_000L))
            }
        }
    }

    private fun request(body: JSONObject): JSONObject =
        body.put("from", JSONObject().put("id", PeerSync.deviceId(ctx)).put("name", PeerSync.deviceName()).put("kind", "phone"))

    private fun call(body: JSONObject, timeoutMs: Int = 10_000): JSONObject {
        val reply = LanClient.rpc(peer.address, key, request(body), timeoutMs)
        notePlace(reply)
        return reply
    }

    /** Remembers the other phone's address on each network, so it is found again after a move. */
    private fun notePlace(reply: JSONObject) {
        val tailnet = reply.optString("tailnet").takeIf { it.isNotEmpty() }
        if (tailnet != null && peer.places[TAILNET] != tailnet) {
            peer = peer.copy(places = peer.places + (TAILNET to tailnet))
            PeerSync.updatePeer(ctx, peer)
        }
        val netId = reply.optJSONObject("net")?.optString("id")?.takeIf { it.isNotEmpty() } ?: return
        // Reached over Tailscale: the WiFi address is the other phone's own, not the one used here.
        val address = if (peer.address == peer.places[TAILNET]) reply.optJSONObject("net")?.optString("address").orEmpty() else peer.address
        if (address.isEmpty() || peer.places[netId] == address) return
        peer = peer.copy(places = (peer.places + (netId to address)).entries.toList().takeLast(12).associate { it.key to it.value })
        PeerSync.updatePeer(ctx, peer)
    }

    /** The other phone stopped answering: try its saved addresses, then sweep this phone's ranges. */
    private suspend fun find(): Boolean {
        val tried = mutableSetOf(peer.address)
        val candidates = (hints + peer.places.values).filter { tried.add(it) }
        hints.clear()
        for (address in candidates) if (tryAt(address)) return true
        val now = System.currentTimeMillis()
        if (now - lastSweepAt < 2 * 60_000L) return false
        lastSweepAt = now
        val ranges = SyncRuntime.wifiAddresses().map { it.substringBefore(':').substringBeforeLast('.') }.distinct()
        for (range in ranges) {
            val found = sweep(range)
            for (address in found) if (address != peer.address && tryAt(address)) return true
        }
        return false
    }

    private fun tryAt(address: String): Boolean = runCatching {
        LanClient.rpc(address, key, request(JSONObject().put("op", "state")), 2_500)
        peer = peer.copy(address = address)
        PeerSync.updatePeer(ctx, peer)
        true
    }.getOrDefault(false)

    private suspend fun sweep(range: String): List<String> = coroutineScope {
        val own = SyncRuntime.wifiAddresses().toSet()
        (1..254).map { "$range.$it:${LanServer.PORT}" }.filter { it !in own }.chunked(64).flatMap { chunk ->
            chunk.map { address -> async(Dispatchers.IO) { address.takeIf { LanClient.hello(it) } } }.awaitAll().filterNotNull()
        }
    }

    private suspend fun reconcile(reply: JSONObject) = lock.withLock {
        // The other phone paired or dropped a browser: swap keys now rather than at the next round.
        val hash = reply.optString("browsersHash")
        if (hash.isNotEmpty() && hash != state.optString("browsersHash")) {
            state.put("browsersHash", hash)
            exchangeUsageSoon()
        }
        val phoneJson = reply.getJSONObject("phone")
        var remote = phoneFromJson(phoneJson)
        var remoteJson = phoneJson
        val now = System.currentTimeMillis()
        lastOkAt = now
        state.put("lastOkAt", now)
        if (remote.access != CurboxAccess.READY) {
            saveRev(remote)
            return@withLock report(reachable = true, message = "Curbox on ${peer.name} is not ready yet")
        }
        var local = bridge.refresh()
        if (local.access != CurboxAccess.READY) return@withLock report(reachable = true, message = "Curbox on this phone is not ready yet")
        val caps = phoneJson.optJSONArray("caps")?.strings().orEmpty()

        var note = ""
        if (local.rich && "groups" in caps) {
            runCatching { shareGroups(remoteJson) }
                .onSuccess { remoteJson = it; remote = phoneFromJson(it) }
                .onFailure { Log.w(PeerSync.tag(), "Group sharing failed", it) }
            local = bridge.state.value
        }

        val cycles = local.rich && "pomodoro" in caps
        val skew = reply.optLong("now", now) - now
        val lv = PeerReconcile.view(local, now, cycles)
        val rv = PeerReconcile.view(remote, now, cycles)
        if (!PeerReconcile.sameFocus(lv, rv) && (PeerReconcile.settling(lv, now) || PeerReconcile.settling(rv, now))) {
            // A phase or session is ending on both phones; the change event wakes the next round.
            saveRev(remote)
            return@withLock report(reachable = true)
        }
        val last = viewFromJson(state.optJSONObject("lastLocal"))?.let { l -> viewFromJson(state.optJSONObject("lastRemote"))?.let { l to it } }
        when (PeerReconcile.decide(lv, rv, last, local.changedAt, remote.changedAt - skew)) {
            PeerReconcile.Action.PUSH -> {
                val res = call(JSONObject().put("op", "focus").put("focus", lv.toJson()))
                remote = phoneFromJson(res.getJSONObject("phone"))
                when (res.optString("result")) {
                    // The other phone cannot end early, so this one follows it.
                    "locked" -> bridge.apply(PeerReconcile.desired(PeerReconcile.view(remote, now, cycles)))
                    "no_group" -> note = "${peer.name} has no focus group named ${lv.name}."
                }
            }
            PeerReconcile.Action.PULL -> when (bridge.apply(PeerReconcile.desired(rv))) {
                "locked" -> {
                    val res = call(JSONObject().put("op", "focus").put("focus", lv.toJson()))
                    remote = phoneFromJson(res.getJSONObject("phone"))
                }
                "no_group" -> note = "This phone has no focus group named ${rv.name}."
            }
            PeerReconcile.Action.NONE -> Unit
        }
        val after = System.currentTimeMillis()
        state.put("lastLocal", PeerReconcile.view(bridge.state.value, after, cycles).toJson())
        state.put("lastRemote", PeerReconcile.view(remote, after, cycles).toJson())
        saveRev(remote)
        report(reachable = true, message = note)
    }

    private fun saveRev(remote: PhoneFocus) {
        state.put("rev", remote.rev)
        PeerSync.saveState(ctx, peer.id, state)
    }

    /** Shares focus groups, with their sites and apps, before focus. Returns the other phone's state after. */
    private suspend fun shareGroups(phoneJson: JSONObject): JSONObject {
        val local = bridge.state.value
        val remoteHash = phoneJson.optString("groupsHash")
        if (state.optString("localHash") == local.groupsHash && state.optString("remoteHash") == remoteHash && remoteHash.isNotEmpty()) return phoneJson

        val listed = call(JSONObject().put("op", "groups").put("apps", true))
        var remote = phoneFromJson(listed.getJSONObject("phone"))
        var remoteJson = listed.getJSONObject("phone")
        val remoteGroups = listed.optJSONArray("groups")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(PhoneGroup::fromJson) } }.orEmpty()
        val now = System.currentTimeMillis()
        val frozen = buildSet {
            if (local.active && !local.exitable && local.endsAt > now) add(local.groupId)
            if (remote.active && !remote.exitable && remote.endsAt > now) add(remote.groupId)
        }
        val links = state.optJSONArray("links")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { GroupLink(it.getString("l"), it.getString("r"), sharedFromJson(it.getJSONObject("b"))) }
        }
        val plans = GroupMerge.plan(local.groups, remoteGroups, links, frozen)

        val localSaves = plans.mapNotNull { it.localSave }
        val localDeletes = plans.mapNotNull { it.localDelete }
        val localResults = if (localSaves.isNotEmpty() || localDeletes.isNotEmpty()) bridge.saveGroups(localSaves, localDeletes) else emptyMap()
        val remoteSaves = plans.mapNotNull { it.remoteSave }
        val remoteDeletes = plans.mapNotNull { it.remoteDelete }
        var remoteResults = emptyMap<String, String>()
        if (remoteSaves.isNotEmpty() || remoteDeletes.isNotEmpty()) {
            val saved = call(JSONObject().put("op", "saveGroups").put("apps", true)
                .put("save", JSONArray().apply { remoteSaves.forEach { put(it.toJson()) } })
                .put("delete", JSONArray(remoteDeletes)))
            remoteResults = saved.optJSONObject("results")?.let { r -> r.keys().asSequence().associateWith { r.getString(it) } }.orEmpty()
            remoteJson = saved.getJSONObject("phone")
            remote = phoneFromJson(remoteJson)
        }
        val kept = GroupMerge.settle(plans, localResults, remoteResults)
        val retry = frozen.isNotEmpty() || GroupMerge.refused(plans, localResults, remoteResults)
        state.put("links", JSONArray().apply { kept.forEach { put(JSONObject().put("l", it.localId).put("r", it.remoteId).put("b", it.base.toJson())) } })
        state.put("localHash", if (retry) "" else bridge.state.value.groupsHash)
        state.put("remoteHash", if (retry) "" else remoteJson.optString("groupsHash"))
        PeerSync.saveState(ctx, peer.id, state)
        return remoteJson
    }
}
