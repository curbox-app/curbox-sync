package neth.iecal.curboxsync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import neth.iecal.curboxsync.PairCode
import neth.iecal.curboxsync.ui.theme.Mono

class SyncActions(
    val onToggle: (Boolean) -> Unit,
    val onApprove: () -> Unit,
    val onChoose: (String) -> Unit,
    val onUnpairAll: () -> Unit,
    val onBattery: () -> Unit,
    val onRename: (NetUi) -> Unit,
    val onForget: (String) -> Unit,
    val onAddPhone: () -> Unit = {},
    val onForgetPhone: (String) -> Unit = {},
    val onTailscale: () -> Unit = {},
    val onTogglePair: () -> Unit = {},
    val onToggleMore: () -> Unit = {},
)

/**
 * The Sync tab. Until setup is done it is three numbered steps with one button at a time, in the
 * order they have to happen. After that it is just the paired devices, one way to pair more, and
 * everything else folded under More.
 */
fun LazyListScope.syncContent(ui: SyncUi, actions: SyncActions, showPair: Boolean = false, showMore: Boolean = false) {
    item(key = "s-hero", contentType = "hero") {
        AnimatedContent(ui.big to ui.sub, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "state") { (big, sub) ->
            Hero(label = "Sync", big = big.ifEmpty { " " }, sub = sub)
        }
    }
    ui.error?.let { error ->
        item(key = "s-error", contentType = "note") {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
    if (!ui.loaded) return

    if (!ui.setupDone) {
        item(key = "s-steps-gap", contentType = "gap") { Spacer(Modifier.height(20.dp)) }
        items(ui.steps, key = { "step:${it.number}" }, contentType = { "step" }) { step ->
            StepCard(step, ui, actions, Modifier.animateItem())
        }
        if (ui.running) more(ui, actions, showMore)
        return
    }

    // ---- Set up: what is paired, and one way to add more

    if (ui.batteryTip) {
        item(key = "s-battery-tip", contentType = "row") {
            ListRow(
                title = "Keep sync running",
                subtitle = "Your phone may pause sync while the screen is off",
                leading = { Initial("!", MaterialTheme.colorScheme.primaryContainer) },
                onClick = actions.onBattery,
                trailing = { QuietButton("Allow", actions.onBattery) },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
    item(key = "s-devices-label", contentType = "label") { SectionLabel("Paired") }
    if (ui.browsersTitle.isNotEmpty() && !ui.browsersTitle.startsWith("No ")) {
        item(key = "s-browsers", contentType = "row") {
            ListRow(ui.browsersTitle, ui.browsersSub, leading = { Initial("B", MaterialTheme.colorScheme.primaryContainer) })
        }
    }
    items(ui.phones, key = { "phone:${it.id}" }, contentType = { "phone" }) { p ->
        ListRow(
            title = p.title,
            subtitle = p.subtitle,
            leading = { Initial(p.title, if (p.inStep) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) },
            modifier = Modifier.animateItem(),
        )
    }
    item(key = "s-pair", contentType = "row") {
        ListRow(
            title = if (showPair) "Done pairing" else "Pair another device",
            subtitle = if (showPair) null else "A browser or another phone",
            leading = { Initial(if (showPair) "-" else "+") },
            onClick = actions.onTogglePair,
        )
    }
    if (showPair) {
        item(key = "s-pair-code", contentType = "code") {
            Column(Modifier.animateItem()) {
                CodeCard(ui.code, "In the Curbox extension, open Reducers, then Phone sync, and type this code. On another phone, type it in Curbox Sync.")
                OtherPhoneLink(actions.onAddPhone)
            }
        }
    }
    more(ui, actions, showMore)
}

/** One numbered setup step. Only the current one shows its details and button. */
@Composable
private fun StepCard(step: StepUi, ui: SyncUi, actions: SyncActions, modifier: Modifier = Modifier) {
    val current = step.state == StepState.CURRENT
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (current) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepBadge(step.number, step.state)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    step.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (step.state == StepState.LATER) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (!current) {
                    Text(step.body, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!current) return@Column
        Text(
            step.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (step.number == 2 && ui.pick.isNotEmpty()) {
            ui.pick.forEach { c -> ListRow(c.label, c.packageName, onClick = { actions.onChoose(c.packageName) }) }
        }
        step.button?.let { label ->
            PillButton(
                label,
                {
                    when (step.action) {
                        StepAction.TURN_ON -> actions.onToggle(true)
                        StepAction.ALLOW_CURBOX -> actions.onApprove()
                        null -> Unit
                    }
                },
                Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
        if (step.pairing) {
            CodeCard(ui.code, null)
            OtherPhoneLink(actions.onAddPhone)
        }
    }
}

@Composable
private fun StepBadge(number: Int, state: StepState) {
    val bg = when (state) {
        StepState.DONE -> MaterialTheme.colorScheme.primaryContainer
        StepState.CURRENT -> MaterialTheme.colorScheme.primary
        StepState.LATER -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = when (state) {
        StepState.DONE -> MaterialTheme.colorScheme.onPrimaryContainer
        StepState.CURRENT -> MaterialTheme.colorScheme.onPrimary
        StepState.LATER -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(32.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(if (state == StepState.DONE) "\u2713" else number.toString(), style = MaterialTheme.typography.titleSmall, color = fg)
    }
}

@Composable
private fun OtherPhoneLink(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        QuietButton("The other phone shows a code? Type it here", onClick, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Everything people rarely need, folded behind one row. */
private fun LazyListScope.more(ui: SyncUi, actions: SyncActions, showMore: Boolean) {
    item(key = "s-more", contentType = "row") {
        ListRow(
            title = if (showMore) "Less" else "More",
            subtitle = if (showMore) null else "Sync from anywhere, WiFi, battery, unpair",
            onClick = actions.onToggleMore,
            modifier = Modifier.padding(top = 20.dp),
        )
    }
    if (!showMore) return

    item(key = "s-where-label", contentType = "label") { SectionLabel("Works on") }
    item(key = "s-current-net", contentType = "row") {
        val net = ui.currentNet
        ListRow(
            title = net?.label ?: "No WiFi right now",
            subtitle = net?.let { "${it.subtitle} · tap to rename" } ?: "Join a WiFi, or turn on Tailscale below",
            leading = { Initial("W", if (net != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) },
            onClick = net?.let { { actions.onRename(it) } },
        )
    }
    item(key = "s-tailscale", contentType = "row") {
        val t = ui.tailscale
        ListRow(
            title = t.title,
            subtitle = t.subtitle,
            leading = { Initial("T", if (t.on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) },
            onClick = actions.onTailscale,
            trailing = t.button?.let { label -> { QuietButton(label, actions.onTailscale) } },
        )
    }
    items(ui.networks.filter { !it.current }, key = { "net:${it.id}" }, contentType = { "net" }) { n ->
        ListRow(
            title = n.label,
            subtitle = n.subtitle,
            leading = { Initial(n.label) },
            onClick = { actions.onRename(n) },
            trailing = { QuietButton("Forget", { actions.onForget(n.id) }, MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }

    item(key = "s-settings-label", contentType = "label") { SectionLabel("Settings") }
    item(key = "s-curbox", contentType = "row") {
        ListRow(ui.curbox.title, ui.curbox.subtitle, leading = { Initial("C") })
    }
    items(ui.alternatives, key = { "pkg:${it.packageName}" }, contentType = { "row" }) { c ->
        ListRow(c.label, c.packageName, onClick = { actions.onChoose(c.packageName) })
    }
    item(key = "s-toggle", contentType = "row") {
        ListRow(
            title = "Sync",
            subtitle = ui.runSub,
            onClick = { actions.onToggle(!ui.running) },
            trailing = { Switch(checked = ui.running, onCheckedChange = actions.onToggle) },
        )
    }
    item(key = "s-battery", contentType = "row") {
        ListRow(title = "Battery", subtitle = ui.batterySub, onClick = actions.onBattery)
    }
    items(ui.phones.filter { it.canForget }, key = { "forget:${it.id}" }, contentType = { "row" }) { p ->
        ListRow(
            title = "Forget ${p.title}",
            subtitle = "Unpair it on both phones",
            onClick = { actions.onForgetPhone(p.id) },
        )
    }
    if (ui.canUnpair) {
        item(key = "s-unpair", contentType = "row") {
            ListRow(
                title = "Unpair all",
                subtitle = "Every browser and phone, which need a new code to pair again",
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

/** The code a browser or another phone types to pair with this one. */
@Composable
private fun CodeCard(code: String, hint: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(
            if (code.isEmpty()) "Join a WiFi to get a code" else code,
            style = if (code.isEmpty()) MaterialTheme.typography.titleMedium else MaterialTheme.typography.displayMedium.copy(fontFamily = Mono.fontFamily),
            color = if (code.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

/** Types the code another phone shows. [onDone] gets the code, or null when cancelled. */
@Composable
fun PhoneCodeDialog(busy: Boolean, note: String?, onDone: (String?) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val ready = PairCode.normalize(text).length == PairCode.LENGTH
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Add another phone", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text(
                    "Open Curbox Sync on the other phone and type the code at its top. Both need the same WiFi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = PairCode.format(text),
                    onValueChange = { text = PairCode.normalize(it).take(PairCode.LENGTH) },
                    singleLine = true,
                    enabled = !busy,
                    placeholder = { Text("ABC 123", style = Mono) },
                    textStyle = Mono.copy(textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ready) onDone(text) }),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                Text(
                    if (busy) "Pairing..." else note.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { QuietButton("Pair", { if (ready && !busy) onDone(text) }) },
        dismissButton = { QuietButton("Cancel", { onDone(null) }, MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}
