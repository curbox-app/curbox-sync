package neth.iecal.curboxsync

import android.app.Application
import android.os.PowerManager
import android.text.format.DateUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import neth.iecal.curboxsync.ui.Bar
import neth.iecal.curboxsync.ui.Choice
import neth.iecal.curboxsync.ui.CurboxUi
import neth.iecal.curboxsync.ui.NetUi
import neth.iecal.curboxsync.ui.PhoneUi
import neth.iecal.curboxsync.ui.RowUi
import neth.iecal.curboxsync.ui.Series
import neth.iecal.curboxsync.ui.SeriesUi
import neth.iecal.curboxsync.ui.SourceUi
import neth.iecal.curboxsync.ui.StepAction
import neth.iecal.curboxsync.ui.StepState
import neth.iecal.curboxsync.ui.StepUi
import neth.iecal.curboxsync.ui.SyncUi
import neth.iecal.curboxsync.ui.TailscaleUi
import neth.iecal.curboxsync.ui.UsageUi
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Turns sync status and usage into ready to draw state, off the main thread. */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private data class Selection(val weeksBack: Int = 0, val picked: Int = LocalDate.now().dayOfWeek.value - 1)

    private val selection = MutableStateFlow(Selection())

    // "2m ago" labels and the current day move with time, not only with data.
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis() / TICK_MS)
            delay(TICK_MS)
        }
    }

    private val power = app.getSystemService(PowerManager::class.java)

    val sync: StateFlow<SyncUi> = combine(SyncRuntime.status, clock) { s, _ ->
        val batteryOk = power?.isIgnoringBatteryOptimizations(app.packageName) != false
        syncUi(s, System.currentTimeMillis(), batteryOk)
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUi())

    private val snapshot = flow { emitAll(withContext(Dispatchers.IO) { SyncRuntime.usage(app) }.snapshot) }

    val usage: StateFlow<UsageUi> = combine(snapshot, selection, clock) { snap, sel, _ -> usageUi(snap, sel, LocalDate.now()) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageUi())

    fun selectDay(index: Int) {
        val today = LocalDate.now()
        selection.update { sel ->
            if (UsageMath.week(sel.weeksBack, today)[index].isAfter(today)) sel else sel.copy(picked = index)
        }
    }

    fun previousWeek() = selection.update { if (it.weeksBack < MAX_WEEKS_BACK) Selection(it.weeksBack + 1, 6) else it }

    fun nextWeek() = selection.update {
        when {
            it.weeksBack == 0 -> it
            it.weeksBack == 1 -> Selection()
            else -> Selection(it.weeksBack - 1, 6)
        }
    }

    fun rename(id: String, label: String) = SyncRuntime.renameNetwork(getApplication(), id, label)
    fun forget(id: String) = SyncRuntime.forgetNetwork(getApplication(), id)
    fun unpairAll() = SyncRuntime.newCode()
    fun forgetPhone(id: String) {
        viewModelScope.launch(Dispatchers.IO) { PeerSync.forget(getApplication(), id) }
    }
    fun choose(pkg: String) = SyncRuntime.choose(pkg)
    fun tailscale() = SyncRuntime.turnOnTailscale(getApplication())

    companion object {
        private const val TICK_MS = 15_000L
        private const val MAX_WEEKS_BACK = 3
        private const val RECENT_MS = 90_000L
        private const val MAX_ROWS = 60
        private val DAY_TITLE = DateTimeFormatter.ofPattern("EEEE, d MMM")
        private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM")

        fun ago(at: Long, now: Long): String =
            if (now - at < 60_000L) "just now"
            else DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString().lowercase()

        private fun syncUi(s: SyncStatus, now: Long, batteryOk: Boolean): SyncUi {
            val phone = s.phone
            val browserRecent = s.lastBrowserAt > 0 && now - s.lastBrowserAt < RECENT_MS
            val curbox = when {
                s.installs.isEmpty() -> CurboxUi("Curbox not found", "Install Curbox, or update it to a version with its API")
                s.selectedPackage == null -> CurboxUi("Pick a Curbox", "More than one is installed")
                !s.running -> CurboxUi("Curbox", "Connects when sync is on")
                phone.access == CurboxAccess.NEEDS_APPROVAL -> CurboxUi("Curbox needs to allow this app", "To start and stop focus and read usage", approve = true)
                phone.access == CurboxAccess.READY -> CurboxUi(
                    "Curbox",
                    "${phone.groups.size} focus groups · API ${phone.apiVersion}" + when {
                        phone.apiVersion < 5 -> " · update for full usage"
                        phone.apiVersion < 7 -> " · update to share Pomodoro and site lists"
                        else -> ""
                    },
                )
                else -> CurboxUi("Curbox", "Connecting")
            }
            val networks = s.networks.map { n ->
                val current = n.id == s.network?.id
                NetUi(n.id, n.label, if (current) "Here now · tap to rename" else "Last used ${ago(n.lastSeen, now)} · tap to rename", current)
            }
            val currentNet = s.network?.let { net ->
                val saved = s.networks.firstOrNull { it.id == net.id }
                NetUi(net.id, saved?.label ?: net.defaultLabel, "Connected now · ${net.address.substringBefore(':')}", true)
            }
            val tailscale = when {
                s.tailnet != null -> TailscaleUi(
                    "Tailscale is on",
                    "Works anywhere, even off WiFi · ${s.tailnet.substringBefore(':')}. Use the same account on your computer.",
                    on = true,
                )
                s.tailscaleStarting -> TailscaleUi("Turning on Tailscale", "One moment")
                s.tailscaleInstalled -> TailscaleUi("Tailscale is off", "Turn it on to sync when you are away from this WiFi", "Turn on")
                else -> TailscaleUi("Sync from anywhere", "Free with Tailscale. Get it here and on your computer, sign in to both", "Get")
            }
            val phones = s.phones.map { p ->
                PhoneUi(
                    id = p.id,
                    title = p.name,
                    subtitle = when {
                        p.message.isNotEmpty() -> p.message
                        p.reachable -> "In step · synced ${ago(p.lastOkAt, now)}" + if (p.outgoing) "" else " · it syncs with this phone"
                        p.lastOkAt > 0 -> "Last synced ${ago(p.lastOkAt, now)}"
                        else -> "Waiting for it"
                    },
                    inStep = p.reachable && p.message.isEmpty(),
                    canForget = true,
                )
            }
            val ready = phone.access == CurboxAccess.READY
            val paired = s.browsers > 0 || s.phones.isNotEmpty()
            val steps = setupSteps(s, ready, paired)
            return SyncUi(
                loaded = true,
                setupDone = s.running && ready && paired,
                steps = steps,
                batteryTip = s.running && !batteryOk && !s.hosted,
                phones = phones,
                running = s.running,
                big = when {
                    !s.running -> "Off"
                    phone.access == CurboxAccess.NEEDS_APPROVAL -> "Needs access"
                    phone.access != CurboxAccess.READY -> "Not ready"
                    s.browsers == 0 && s.phones.isEmpty() -> "Not paired"
                    browserRecent || s.phones.any { it.reachable && it.message.isEmpty() } -> "In step"
                    else -> "Waiting"
                },
                sub = when {
                    !s.running -> "Three quick steps to share focus with your browser"
                    phone.access != CurboxAccess.READY -> "Finish the steps below"
                    s.browsers == 0 && s.phones.isEmpty() -> "One step left"
                    phone.onBreak -> "Break · ${UsageMath.format(phone.endsAt - now)} left · round ${phone.pomodoro!!.completed + 1} of ${phone.pomodoro.rounds} next"
                    phone.active && phone.pomodoro != null ->
                        "${phone.name.ifEmpty { "Focus" }} · round ${phone.pomodoro.completed + 1} of ${phone.pomodoro.rounds} · ${UsageMath.format(phone.endsAt - now)} left"
                    phone.active -> "${phone.name.ifEmpty { "Focus" }} · ${UsageMath.format(phone.endsAt - now)} left"
                    phone.access == CurboxAccess.READY -> "Focus is off"
                    else -> null
                },
                error = s.error,
                curbox = curbox,
                pick = if (s.selectedPackage == null && s.installs.size > 1) s.installs.map { Choice(it.label, it.packageName) } else emptyList(),
                alternatives = if (s.selectedPackage != null) s.installs.filter { it.packageName != s.selectedPackage }
                    .map { Choice("Use ${it.label} instead", it.packageName) } else emptyList(),
                browsersTitle = when (s.browsers + s.relayedBrowsers) {
                    0 -> "No browser paired"
                    1 -> "1 browser paired"
                    else -> "${s.browsers + s.relayedBrowsers} browsers paired"
                },
                browsersSub = listOfNotNull(
                    when {
                        s.lastBrowserAt != 0L -> "Last seen ${ago(s.lastBrowserAt, now)}"
                        s.relayedBrowsers == 0 -> "Open Reducers, then Phone sync, in the extension"
                        else -> null
                    },
                    when {
                        s.relayedBrowsers == 0 -> null
                        s.browsers == 0 -> "Paired through your other phone"
                        else -> "${s.relayedBrowsers} through your other phone"
                    },
                ).joinToString(" · "),
                currentNet = currentNet,
                tailscale = tailscale,
                networks = networks,
                addresses = if (s.running) s.addresses + listOfNotNull(s.tailnet) else emptyList(),
                code = s.code,
                canUnpair = s.browsers > 0 || s.phones.isNotEmpty(),
                lastEvent = s.lastEvent,
                runSub = when {
                    !s.running -> "Off"
                    s.hosted -> "Curbox keeps it running, so there is no notification"
                    else -> "Runs quietly while the screen is off"
                },
                batterySub = s.hostHint ?: "Let Curbox Sync run in the background",
            )
        }

        /**
         * Turn on, let Curbox allow it, then pair. Each step waits for the one before, so the code
         * only shows once Curbox has said yes: pairing first and allowing later is easy to forget.
         */
        private fun setupSteps(s: SyncStatus, ready: Boolean, paired: Boolean): List<StepUi> {
            val access = s.phone.access
            fun state(done: Boolean, previousDone: Boolean) = when {
                done -> StepState.DONE
                previousDone -> StepState.CURRENT
                else -> StepState.LATER
            }
            val on = StepUi(
                1,
                if (s.running) "Sync is on" else "Turn on sync",
                if (s.running) "Runs quietly in the background" else "Shares focus between this phone, your browser and other phones",
                state(s.running, true),
                button = "Turn on", action = StepAction.TURN_ON,
            )
            val curboxState = state(s.running && ready, s.running)
            val curbox = when {
                s.installs.isEmpty() -> StepUi(2, "Install Curbox", "Curbox Sync works through Curbox. Install or update it, then come back here.", if (s.running) StepState.CURRENT else StepState.LATER)
                s.selectedPackage == null -> StepUi(2, "Pick your Curbox", "More than one is installed. Tap the one you use.", if (s.running) StepState.CURRENT else StepState.LATER)
                curboxState == StepState.DONE -> StepUi(2, "Curbox allowed access", "${s.phone.groups.size} focus groups found", StepState.DONE)
                access == CurboxAccess.NEEDS_APPROVAL -> StepUi(
                    2, "Allow access in Curbox",
                    "Curbox asks once, so this app can start and stop focus and read usage. Nothing syncs until you allow it.",
                    curboxState, button = "Allow", action = StepAction.ALLOW_CURBOX,
                )
                s.running -> StepUi(2, "Connecting to Curbox", "Open Curbox once if this takes more than a few seconds", curboxState)
                else -> StepUi(2, "Allow access in Curbox", "Curbox asks once, right after you turn sync on", curboxState)
            }
            val pairState = state(paired, s.running && ready)
            val pair = StepUi(
                3,
                if (pairState == StepState.DONE) pairedTitle(s) else "Pair your browser",
                when (pairState) {
                    StepState.DONE -> "You can pair more later"
                    StepState.CURRENT -> "In the Curbox extension, open Reducers, then Phone sync, and type this code. Keep this screen open."
                    StepState.LATER -> "You get a code to type in the Curbox extension"
                },
                pairState,
                pairing = true,
            )
            return listOf(on, curbox, pair)
        }

        private fun pairedTitle(s: SyncStatus): String {
            val parts = listOfNotNull(
                s.browsers.takeIf { it > 0 }?.let { if (it == 1) "1 browser" else "$it browsers" },
                s.phones.size.takeIf { it > 0 }?.let { if (it == 1) "1 phone" else "$it phones" },
            )
            return "Paired with ${parts.joinToString(" and ")}"
        }

        private fun browserSubtitle(b: BrowserUsage, now: Long): String =
            (if (b.via != null) "Through your other phone · " else "Sent ") + "${ago(b.lastSeen, now)} · ${b.days.size} days"

        private fun usageUi(snap: UsageSnapshot, sel: Selection, today: LocalDate): UsageUi {
            val now = System.currentTimeMillis()
            val week = UsageMath.week(sel.weeksBack, today)
            val day = week[sel.picked].let { if (it.isAfter(today)) today else it }
            val combined = UsageMath.combine(snap, day.toString())
            val peers = UsageMath.peerOrder(snap)
            // A phone past the color slots shares the last phone color.
            fun slotOf(peerIndex: Int) = 1 + peerIndex.coerceAtMost(Series.PEERS - 1)
            fun slots(values: List<Long>): List<Long> {
                val out = LongArray(Series.SLOTS)
                out[Series.OWN] = values.first()
                values.subList(1, values.size - 1).forEachIndexed { i, v -> out[slotOf(i)] += v }
                out[Series.BROWSER] = values.last()
                return out.toList()
            }
            val bars = week.map { d ->
                Bar(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), slots(UsageMath.bySource(snap, d.toString())))
            }
            val picked = UsageMath.bySource(snap, day.toString())
            val series = buildList {
                add(SeriesUi("own", if (peers.isEmpty()) "Phone" else "This phone", UsageMath.format(picked.first()), Series.OWN))
                peers.forEachIndexed { i, p -> add(SeriesUi("p:${p.id}", p.name, UsageMath.format(picked[i + 1]), slotOf(i))) }
                if (snap.browsers.isNotEmpty() || picked.last() > 0) add(SeriesUi("browser", "Browser", UsageMath.format(picked.last()), Series.BROWSER))
            }
            val rows = combined.rows.take(MAX_ROWS).map { r ->
                RowUi(
                    key = r.key,
                    title = r.title,
                    subtitle = when {
                        r.isApp -> "App"
                        r.source == Source.BOTH -> "Website · phone and browser"
                        r.source == Source.PHONE -> "Website · phone"
                        else -> "Website · browser"
                    },
                    time = UsageMath.format(r.ms),
                )
            }
            val sources = buildList {
                add(
                    SourceUi(
                        "phone", if (snap.phones.isEmpty()) "This phone" else "This phone · ${PeerSync.deviceName()}",
                        when {
                            snap.phoneReadAt == 0L -> "Not read from Curbox yet"
                            !snap.phoneDetailed -> "Update Curbox to see apps and earlier days"
                            else -> "Read from Curbox ${ago(snap.phoneReadAt, now)}"
                        },
                        slot = Series.OWN,
                    )
                )
                peers.forEachIndexed { i, p -> add(SourceUi("p:${p.id}", p.name, "Sent ${ago(p.lastSeen, now)} · ${p.days.size} days", slotOf(i))) }
                if (snap.browsers.isEmpty()) add(SourceUi("none", "No browser yet", "Pair the Curbox extension to add its time here", Series.BROWSER))
                snap.browsers.forEach { add(SourceUi("b:${it.id}", it.name, browserSubtitle(it, now), Series.BROWSER)) }
            }
            return UsageUi(
                loaded = true,
                heroLabel = if (day == today) "Total today" else day.format(DAY_TITLE),
                total = UsageMath.format(combined.totalMs),
                series = series,
                bars = bars,
                selected = week.indexOf(day).coerceAtLeast(0),
                weekRange = "${week.first().format(SHORT_DATE)} to ${week.last().format(SHORT_DATE)}",
                canPrev = sel.weeksBack < MAX_WEEKS_BACK,
                canNext = sel.weeksBack > 0,
                rows = rows,
                sources = sources,
            )
        }
    }
}
