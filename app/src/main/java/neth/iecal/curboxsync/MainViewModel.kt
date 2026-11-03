package neth.iecal.curboxsync

import android.app.Application
import android.text.format.DateUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import neth.iecal.curboxsync.ui.RowUi
import neth.iecal.curboxsync.ui.SourceUi
import neth.iecal.curboxsync.ui.SyncUi
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

    val sync: StateFlow<SyncUi> = combine(SyncRuntime.status, clock) { s, _ -> syncUi(s, System.currentTimeMillis()) }
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
    fun choose(pkg: String) = SyncRuntime.choose(pkg)

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

        private fun syncUi(s: SyncStatus, now: Long): SyncUi {
            val phone = s.phone
            val browserRecent = s.lastBrowserAt > 0 && now - s.lastBrowserAt < RECENT_MS
            val curbox = when {
                s.installs.isEmpty() -> CurboxUi("Curbox not found", "Install Curbox, or update it to a version with its API")
                s.selectedPackage == null -> CurboxUi("Pick a Curbox", "More than one is installed")
                !s.running -> CurboxUi("Curbox", "Connects when sync is on")
                phone.access == CurboxAccess.NEEDS_APPROVAL -> CurboxUi("Curbox needs to allow this app", "To start and stop focus and read usage", approve = true)
                phone.access == CurboxAccess.READY -> CurboxUi(
                    "Curbox",
                    "${phone.groups.size} focus groups · API ${phone.apiVersion}" + if (phone.apiVersion < 5) " · update for full usage" else "",
                )
                else -> CurboxUi("Curbox", "Connecting")
            }
            val networks = s.networks.map { n ->
                val current = n.id == s.network?.id
                NetUi(n.id, n.label, "${n.address.substringBefore(':')} · ${if (current) "here now" else "last synced ${ago(n.lastSeen, now)}"}", current)
            }
            val currentNet = s.network?.let { net ->
                val saved = s.networks.firstOrNull { it.id == net.id }
                NetUi(
                    net.id,
                    saved?.label ?: net.defaultLabel,
                    "${net.address.substringBefore(':')} · ${if (saved != null) "saved" else "saved after the first sync"}",
                    true,
                )
            }
            return SyncUi(
                loaded = true,
                running = s.running,
                big = when {
                    !s.running -> "Off"
                    phone.access != CurboxAccess.READY -> "Not ready"
                    s.browsers == 0 -> "Not paired"
                    browserRecent -> "In step"
                    else -> "Waiting"
                },
                sub = when {
                    !s.running -> "Turn sync on to share focus with your browser"
                    phone.active -> "${phone.name.ifEmpty { "Focus" }} · ${UsageMath.format(phone.endsAt - now)} left"
                    phone.access == CurboxAccess.READY -> "Focus is off"
                    else -> null
                },
                error = s.error,
                curbox = curbox,
                pick = if (s.selectedPackage == null && s.installs.size > 1) s.installs.map { Choice(it.label, it.packageName) } else emptyList(),
                alternatives = if (s.selectedPackage != null) s.installs.filter { it.packageName != s.selectedPackage }
                    .map { Choice("Use ${it.label} instead", it.packageName) } else emptyList(),
                browsersTitle = when (s.browsers) {
                    0 -> "No browser paired"
                    1 -> "1 browser paired"
                    else -> "${s.browsers} browsers paired"
                },
                browsersSub = if (s.lastBrowserAt == 0L) "Open Reducers, then Phone over WiFi, in the extension"
                else "Last seen ${ago(s.lastBrowserAt, now)}",
                currentNet = currentNet,
                networks = networks,
                addresses = if (s.running) s.addresses else emptyList(),
                code = s.code,
                canUnpair = s.browsers > 0,
                lastEvent = s.lastEvent,
            )
        }

        private fun usageUi(snap: UsageSnapshot, sel: Selection, today: LocalDate): UsageUi {
            val now = System.currentTimeMillis()
            val week = UsageMath.week(sel.weeksBack, today)
            val day = week[sel.picked].let { if (it.isAfter(today)) today else it }
            val combined = UsageMath.combine(snap, day.toString())
            val bars = week.map { d ->
                val (p, b) = UsageMath.totals(snap, d.toString())
                Bar(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), p, b)
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
                        "phone", "This phone",
                        when {
                            snap.phoneReadAt == 0L -> "Not read from Curbox yet"
                            !snap.phoneDetailed -> "Update Curbox to see apps and earlier days"
                            else -> "Read from Curbox ${ago(snap.phoneReadAt, now)}"
                        },
                        phone = true,
                    )
                )
                if (snap.browsers.isEmpty()) add(SourceUi("none", "No browser yet", "Pair the Curbox extension to add its time here", false))
                snap.browsers.forEach { add(SourceUi("b:${it.id}", it.name, "Sent ${ago(it.lastSeen, now)} · ${it.days.size} days", false)) }
            }
            return UsageUi(
                loaded = true,
                heroLabel = if (day == today) "Total today" else day.format(DAY_TITLE),
                total = UsageMath.format(combined.totalMs),
                phone = "Phone ${UsageMath.format(combined.phoneMs)}",
                browser = "Browser ${UsageMath.format(combined.browserMs)}",
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
