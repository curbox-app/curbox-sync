package neth.iecal.curboxsync.ui

import androidx.compose.runtime.Immutable

// Everything the screens draw, already formatted, so composition does no work beyond layout.

@Immutable
data class Choice(val label: String, val packageName: String)

@Immutable
data class CurboxUi(val title: String, val subtitle: String, val approve: Boolean = false)

@Immutable
data class NetUi(val id: String, val label: String, val subtitle: String, val current: Boolean)

@Immutable
data class SyncUi(
    val loaded: Boolean = false,
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
    val runSub: String = "Runs quietly while the screen is off",
    val batterySub: String = "Let Curbox Sync run in the background",
)

@Immutable
data class Bar(val label: String, val phone: Long, val browser: Long)

@Immutable
data class RowUi(val key: String, val title: String, val subtitle: String, val time: String)

@Immutable
data class SourceUi(val key: String, val title: String, val subtitle: String, val phone: Boolean)

@Immutable
data class UsageUi(
    val loaded: Boolean = false,
    val heroLabel: String = "Total today",
    val total: String = "",
    val phone: String = "",
    val browser: String = "",
    val bars: List<Bar> = emptyList(),
    val selected: Int = 0,
    val weekRange: String = "",
    val canPrev: Boolean = false,
    val canNext: Boolean = false,
    val rows: List<RowUi> = emptyList(),
    val sources: List<SourceUi> = emptyList(),
)
