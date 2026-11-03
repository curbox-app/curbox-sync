package neth.iecal.curboxsync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class UsageActions(
    val onSelectDay: (Int) -> Unit,
    val onPrev: () -> Unit,
    val onNext: () -> Unit,
)

fun LazyListScope.usageContent(ui: UsageUi, actions: UsageActions) {
    item(key = "u-hero", contentType = "hero") {
        AnimatedContent(ui.heroLabel to ui.total, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "total") { (label, total) ->
            Hero(label = label, big = if (ui.loaded) total else " ")
        }
    }
    item(key = "u-split", contentType = "split") { Split(ui.phone, ui.browser) }
    item(key = "u-bars", contentType = "bars") {
        WeekBars(ui.bars, ui.selected, actions.onSelectDay, Modifier.padding(top = 32.dp))
    }
    item(key = "u-week", contentType = "week") { WeekNav(ui, actions) }
    item(key = "u-rows-label", contentType = "label") { SectionLabel("Apps and sites") }
    if (ui.loaded && ui.rows.isEmpty()) {
        item(key = "u-empty", contentType = "note") {
            Text(
                "Nothing recorded for this day yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
    items(ui.rows, key = { it.key }, contentType = { "row" }) { row ->
        ListRow(
            title = row.title,
            subtitle = row.subtitle,
            leading = { Initial(row.title) },
            trailing = { TrailingText(row.time) },
            modifier = Modifier.animateItem(),
        )
    }
    item(key = "u-sources-label", contentType = "label") { SectionLabel("Sources") }
    items(ui.sources, key = { it.key }, contentType = { "source" }) { s ->
        val color = MaterialTheme.colorScheme.primary
        ListRow(
            title = s.title,
            subtitle = s.subtitle,
            leading = { Dot(if (s.phone) color else color.copy(alpha = 0.38f), 10) },
        )
    }
}

@Composable
private fun Split(phone: String, browser: String) {
    val color = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(6.dp))
        Text(phone, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Dot(color.copy(alpha = 0.38f))
        Spacer(Modifier.width(6.dp))
        Text(browser, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WeekNav(ui: UsageUi, actions: UsageActions) {
    val on = MaterialTheme.colorScheme.onSurfaceVariant
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        QuietButton("‹", actions.onPrev, if (ui.canPrev) on else off)
        Text(
            ui.weekRange,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        QuietButton("›", actions.onNext, if (ui.canNext) on else off)
    }
}
