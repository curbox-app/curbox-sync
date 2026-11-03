package neth.iecal.curboxsync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import neth.iecal.curboxsync.CurboxAccess
import neth.iecal.curboxsync.SavedNetwork
import neth.iecal.curboxsync.SyncStatus
import neth.iecal.curboxsync.UsageMath
import neth.iecal.curboxsync.ui.theme.Mono

class SyncActions(
    val onToggle: (Boolean) -> Unit,
    val onApprove: () -> Unit,
    val onChoose: (String) -> Unit,
    val onUnpairAll: () -> Unit,
    val onBattery: () -> Unit,
    val onScan: () -> Unit,
    val onRename: (String, String) -> Unit,
    val onForget: (String) -> Unit,
)

private const val RECENT_MS = 90_000L

@Composable
fun SyncScreen(status: SyncStatus, actions: SyncActions) {
    val phone = status.phone
    val now = System.currentTimeMillis()
    val browserRecent = status.lastBrowserAt > 0 && now - status.lastBrowserAt < RECENT_MS
    val big = when {
        !status.running -> "Off"
        phone.access != CurboxAccess.READY -> "Not ready"
        status.browsers == 0 -> "Not paired"
        browserRecent -> "In step"
        else -> "Waiting"
    }
    val sub = when {
        !status.running -> "Turn sync on to share focus with your browser"
        phone.active -> "${phone.name.ifEmpty { "Focus" }} · ${UsageMath.format(phone.endsAt - now)} left"
        phone.access == CurboxAccess.READY -> "Focus is off"
        else -> null
    }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var showManual by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Hero(label = "WiFi sync", big = big, sub = sub)
        if (status.running) {
            PillButton("Scan browser code", actions.onScan, Modifier.fillMaxWidth().padding(top = 24.dp))
        } else {
            PillButton("Turn on", { actions.onToggle(true) }, Modifier.fillMaxWidth().padding(top = 24.dp))
        }
        status.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }

        SectionLabel("This phone")
        CurboxRow(status, actions)
        ListRow(
            title = when (status.browsers) {
                0 -> "No browser paired"
                1 -> "1 browser paired"
                else -> "${status.browsers} browsers paired"
            },
            subtitle = if (status.lastBrowserAt == 0L) "Open Reducers, then Phone over WiFi, in the extension"
            else "Last seen ${ago(status.lastBrowserAt)}",
            leading = { Initial("B") },
        )
        status.network?.let { net ->
            val saved = status.networks.firstOrNull { it.id == net.id }
            ListRow(
                title = saved?.label ?: net.defaultLabel,
                subtitle = if (saved != null) "Current network · saved" else "Current network · saved after the first sync",
                leading = { Initial("W") },
                trailing = { TrailingText(net.address.substringBefore(':')) },
            )
        }

        SectionLabel("Saved networks")
        if (status.networks.isEmpty()) {
            Text(
                "Each WiFi you sync on is kept here, so your browser finds this phone again when you move between home, work or a hotspot.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        status.networks.forEach { n ->
            NetworkRow(n, current = n.id == status.network?.id, onRename = { renaming = n.id }, onForget = { actions.onForget(n.id) })
        }

        if (status.running && status.addresses.isNotEmpty()) {
            SectionLabel("Pair by typing")
            ListRow(
                title = if (showManual) "Hide address and code" else "Show address and code",
                subtitle = "For a browser that cannot scan",
                onClick = { showManual = !showManual },
            )
            AnimatedVisibility(showManual) {
                Column(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp)) {
                    SelectionContainer {
                        Column {
                            status.addresses.forEach { Text(it, style = Mono, color = MaterialTheme.colorScheme.onSurface) }
                            Text(status.code, style = Mono, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }

        SectionLabel("Settings")
        ListRow(
            title = "Sync over WiFi",
            subtitle = "Runs quietly while the screen is off",
            onClick = { actions.onToggle(!status.running) },
            trailing = { Switch(checked = status.running, onCheckedChange = actions.onToggle) },
        )
        ListRow(title = "Battery", subtitle = "Let Curbox Sync run in the background", onClick = actions.onBattery)
        if (status.browsers > 0) {
            ListRow(
                title = "Unpair all browsers",
                subtitle = "They need to scan a new code",
                onClick = actions.onUnpairAll,
                titleColor = MaterialTheme.colorScheme.error,
            )
        }
        if (status.lastEvent.isNotEmpty()) {
            Text(
                status.lastEvent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }

    renaming?.let { id ->
        val n = status.networks.firstOrNull { it.id == id }
        if (n == null) renaming = null else RenameDialog(n, onDone = { label ->
            if (label != null) actions.onRename(id, label)
            renaming = null
        })
    }
}

@Composable
private fun CurboxRow(status: SyncStatus, actions: SyncActions) {
    val phone = status.phone
    when {
        status.installs.isEmpty() -> ListRow("Curbox not found", "Install Curbox, or update it to a version with its API", leading = { Initial("C") })
        status.selectedPackage == null -> {
            ListRow("Pick a Curbox", "More than one is installed", leading = { Initial("C") })
            status.installs.forEach { i -> ListRow(i.label, i.packageName, onClick = { actions.onChoose(i.packageName) }) }
        }
        !status.running -> ListRow("Curbox", "Connects when sync is on", leading = { Initial("C") })
        phone.access == CurboxAccess.NEEDS_APPROVAL -> ListRow(
            "Curbox needs to allow this app",
            "To start and stop focus and read usage",
            leading = { Initial("C") },
            onClick = actions.onApprove,
            trailing = { QuietButton("Allow", actions.onApprove) },
        )
        phone.access == CurboxAccess.READY -> ListRow(
            "Curbox",
            buildString {
                append("${phone.groups.size} focus groups · API ${phone.apiVersion}")
                if (phone.apiVersion < 5) append(" · update for full usage")
            },
            leading = { Initial("C") },
        )
        else -> ListRow("Curbox", "Connecting", leading = { Initial("C") })
    }
    if (status.installs.size > 1 && status.selectedPackage != null) {
        status.installs.filter { it.packageName != status.selectedPackage }.forEach { i ->
            ListRow("Use ${i.label} instead", i.packageName, onClick = { actions.onChoose(i.packageName) })
        }
    }
}

@Composable
private fun NetworkRow(n: SavedNetwork, current: Boolean, onRename: () -> Unit, onForget: () -> Unit) {
    ListRow(
        title = n.label,
        subtitle = buildString {
            append(n.address.substringBefore(':'))
            append(" · ")
            append(if (current) "here now" else "last synced ${ago(n.lastSeen)}")
        },
        leading = { Initial(n.label, if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) },
        onClick = onRename,
        trailing = { QuietButton("Forget", onForget, MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

@Composable
private fun RenameDialog(n: SavedNetwork, onDone: (String?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(n.label) }
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
