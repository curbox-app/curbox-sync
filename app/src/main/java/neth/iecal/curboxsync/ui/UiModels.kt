package neth.iecal.curboxsync.ui

import androidx.compose.runtime.Immutable

// Everything the screens draw, already formatted, so composition does no work beyond layout.

@Immutable
data class Choice(val label: String, val packageName: String)

@Immutable
data class CurboxUi(val title: String, val subtitle: String, val approve: Boolean = false)

@Immutable
data class NetUi(val id: String, val label: String, val subtitle: String, val current: Boolean)

/** The one Tailscale row: what it says, and the single button that moves it forward. */
@Immutable
data class TailscaleUi(val title: String, val subtitle: String, val button: String? = null, val on: Boolean = false)

@Immutable
data class PhoneUi(val id: String, val title: String, val subtitle: String, val inStep: Boolean, val canForget: Boolean)

enum class StepState { DONE, CURRENT, LATER }

/** What the one button on the current setup step does. */
enum class StepAction { TURN_ON, ALLOW_CURBOX }

/** Setup has three steps in a fixed order; [pairing] marks the step that shows the code. */
@Immutable
data class StepUi(
    val number: Int,
    val title: String,
    val body: String,
    val state: StepState,
    val button: String? = null,
    val action: StepAction? = null,
    val pairing: Boolean = false,
)

@Immutable
data class SyncUi(
    val loaded: Boolean = false,
    /** Sync is on, Curbox allowed it, and something is paired: the steps fold away. */
    val setupDone: Boolean = false,
    val steps: List<StepUi> = emptyList(),
    /** Battery optimization would pause sync in Doze, and Curbox is not hosting it. */
    val batteryTip: Boolean = false,
    val running: Boolean = false,
    val big: String = "",
    val sub: String? = null,
    val error: String? = null,
    val curbox: CurboxUi = CurboxUi("Curbox", "Connecting"),
    val pick: List<Choice> = emptyList(),
    val alternatives: List<Choice> = emptyList(),
    val browsersTitle: String = "",
    val browsersSub: String = "",
    val currentNet: NetUi? = null,
    val networks: List<NetUi> = emptyList(),
    val addresses: List<String> = emptyList(),
    val code: String = "",
    val canUnpair: Boolean = false,
    val lastEvent: String = "",
    val phones: List<PhoneUi> = emptyList(),
    val tailscale: TailscaleUi = TailscaleUi("Sync from anywhere", ""),
    val runSub: String = "Runs quietly while the screen is off",
    val batterySub: String = "Let Curbox Sync run in the background",
)

/**
 * One day's bar. [values] has one slot per chart color (see [Series]); missing sources are 0, so
 * every bar animates the same slots.
 */
@Immutable
data class Bar(val label: String, val values: List<Long>)

/** Chart color slots: this phone, up to [Series.PEERS] other phones, then browsers. */
object Series {
    const val PEERS = 4
    const val SLOTS = PEERS + 2
    const val OWN = 0
    const val BROWSER = SLOTS - 1
}

/** One chart source in the legend, with its color slot and its time on the picked day. */
@Immutable
data class SeriesUi(val key: String, val label: String, val time: String, val slot: Int)

@Immutable
data class RowUi(val key: String, val title: String, val subtitle: String, val time: String)

@Immutable
data class SourceUi(val key: String, val title: String, val subtitle: String, val slot: Int)

@Immutable
data class UsageUi(
    val loaded: Boolean = false,
    val heroLabel: String = "Total today",
    val total: String = "",
    val series: List<SeriesUi> = emptyList(),
    val bars: List<Bar> = emptyList(),
    val selected: Int = 0,
    val weekRange: String = "",
    val canPrev: Boolean = false,
    val canNext: Boolean = false,
    val rows: List<RowUi> = emptyList(),
    val sources: List<SourceUi> = emptyList(),
)
