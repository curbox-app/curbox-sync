package neth.iecal.curboxsync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import neth.iecal.curboxsync.ui.theme.Mono

class SyncActions(
    val onToggle: (Boolean) -> Unit,
    val onApprove: () -> Unit,
    val onChoose: (String) -> Unit,
    val onUnpairAll: () -> Unit,
    val onBattery: () -> Unit,
    val onScan: () -> Unit,
    val onRename: (NetUi) -> Unit,
    val onForget: (String) -> Unit,
    val onToggleManual: () -> Unit,
)

fun LazyListScope.syncContent(ui: SyncUi, showManual: Boolean, actions: SyncActions) {
    item(key = "s-hero", contentType = "hero") {
        AnimatedContent(ui.big to ui.sub, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "state") { (big, sub) ->
            Hero(label = "WiFi sync", big = big.ifEmpty { " " }, sub = sub)
        }
    }
    item(key = "s-cta", contentType = "cta") {
        if (ui.running) PillButton("Scan browser code", actions.onScan, Modifier.fillMaxWidth().padding(top = 24.dp))
        else PillButton("Turn on", { actions.onToggle(true) }, Modifier.fillMaxWidth().padding(top = 24.dp))
    }
    ui.error?.let { error ->
        item(key = "s-error", contentType = "note") {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
    }

    item(key = "s-phone-label", contentType = "label") { SectionLabel("This phone") }
    item(key = "s-curbox", contentType = "row") {
        ListRow(
            ui.curbox.title, ui.curbox.subtitle,
            leading = { Initial("C") },
            onClick = if (ui.curbox.approve) actions.onApprove else null,
            trailing = if (ui.curbox.approve) ({ QuietButton("Allow", actions.onApprove) }) else null,
        )
    }
    items(ui.pick + ui.alternatives, key = { "pkg:${it.packageName}" }, contentType = { "row" }) { c ->
        ListRow(c.label, c.packageName, onClick = { actions.onChoose(c.packageName) })
    }
    item(key = "s-browsers", contentType = "row") {
        ListRow(ui.browsersTitle, ui.browsersSub, leading = { Initial("B") })
    }
    ui.currentNet?.let { net ->
        item(key = "s-current-net", contentType = "row") {
            ListRow(net.label, "Current network · ${net.subtitle}", leading = { Initial("W") })
        }
    }

    item(key = "s-nets-label", contentType = "label") { SectionLabel("Saved networks") }
    if (ui.loaded && ui.networks.isEmpty()) {
        item(key = "s-nets-empty", contentType = "note") {
            Text(
                "Each WiFi you sync on is kept here, so your browser finds this phone again when you move between home, work or a hotspot.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
    items(ui.networks, key = { "net:${it.id}" }, contentType = { "net" }) { n ->
        ListRow(
            title = n.label,
            subtitle = n.subtitle,
            leading = { Initial(n.label, if (n.current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) },
            onClick = { actions.onRename(n) },
            trailing = { QuietButton("Forget", { actions.onForget(n.id) }, MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.animateItem(),
        )
    }

    if (ui.addresses.isNotEmpty()) {
        item(key = "s-manual-label", contentType = "label") { SectionLabel("Pair by typing") }
        item(key = "s-manual", contentType = "manual") {
            Column {
                ListRow(
                    title = if (showManual) "Hide address and code" else "Show address and code",
                    subtitle = "For a browser that cannot scan",
                    onClick = actions.onToggleManual,
                )
                AnimatedVisibility(showManual) {
                    SelectionContainer {
                        Column(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp)) {
                            ui.addresses.forEach { Text(it, style = Mono, color = MaterialTheme.colorScheme.onSurface) }
                            Text(ui.code, style = Mono, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }

    item(key = "s-settings-label", contentType = "label") { SectionLabel("Settings") }
    item(key = "s-toggle", contentType = "row") {
        ListRow(
            title = "Sync over WiFi",
            subtitle = "Runs quietly while the screen is off",
            onClick = { actions.onToggle(!ui.running) },
            trailing = { Switch(checked = ui.running, onCheckedChange = actions.onToggle) },
        )
    }
    item(key = "s-battery", contentType = "row") {
        ListRow(title = "Battery", subtitle = "Let Curbox Sync run in the background", onClick = actions.onBattery)
    }
    if (ui.canUnpair) {
        item(key = "s-unpair", contentType = "row") {
            ListRow(
                title = "Unpair all browsers",
                subtitle = "They need to scan a new code",
                onClick = actions.onUnpairAll,
                titleColor = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (ui.lastEvent.isNotEmpty()) {
        item(key = "s-event", contentType = "note") {
            Text(
                ui.lastEvent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}

@Composable
fun RenameDialog(net: NetUi, onDone: (String?) -> Unit) {
    var text by rememberSaveable(net.id) { mutableStateOf(net.label) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Name this network", style = MaterialTheme.typography.headlineSmall) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true, placeholder = { Text("Home") })
        },
        confirmButton = { QuietButton("Save", { onDone(text) }) },
        dismissButton = { QuietButton("Cancel", { onDone(null) }, MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}
