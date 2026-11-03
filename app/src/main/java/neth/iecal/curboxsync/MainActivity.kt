package neth.iecal.curboxsync

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import neth.iecal.curboxsync.ui.theme.CurboxSyncTheme

class MainActivity : ComponentActivity() {

    private val approval = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        SyncRuntime.recheck()
    }

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        if (!SyncService.isRunning()) SyncService.start(this)
        if (!SyncRuntime.pairFromLink(this, text)) toast("That is not a Curbox pairing code.")
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Prefs(this).enabled) SyncService.start(this)
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        handlePairingLink(intent)
        setContent {
            CurboxSyncTheme {
                val status by SyncRuntime.status.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Screen(
                        status = status,
                        modifier = Modifier.padding(padding),
                        onToggle = { on -> if (on) SyncService.start(this) else SyncService.stop(this) },
                        onApprove = ::requestApproval,
                        onChoose = SyncRuntime::choose,
                        onNewCode = SyncRuntime::newCode,
                        onBattery = ::openBatterySettings,
                        onScan = ::scanBrowserCode,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    override fun onResume() {
        super.onResume()
        SyncRuntime.recheck()
    }

    private fun requestApproval() {
        val pkg = SyncRuntime.status.value.selectedPackage ?: return
        try {
            approval.launch(Intent(CurboxConnection.PERMISSION_ACTION).setPackage(pkg))
        } catch (e: ActivityNotFoundException) {
            SyncRuntime.recheck()
        }
    }

    private fun scanBrowserCode() {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Point at the code in the Curbox extension")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(PortraitCaptureActivity::class.java)
        )
    }

    private fun handlePairingLink(intent: Intent?) {
        val link = intent?.data?.toString() ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        intent.data = null
        if (!Prefs(this).enabled) SyncService.start(this)
        if (!SyncRuntime.pairFromLink(this, link)) toast("That is not a Curbox pairing code.")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun openBatterySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

@Composable
private fun Screen(
    status: SyncStatus,
    modifier: Modifier,
    onToggle: (Boolean) -> Unit,
    onApprove: () -> Unit,
    onChoose: (String) -> Unit,
    onNewCode: () -> Unit,
    onBattery: () -> Unit,
    onScan: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Curbox Sync", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Keeps focus mode in step between Curbox on this phone and the Curbox browser extension, over your WiFi.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Section("Sync") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (status.running) "On" else "Off", modifier = Modifier.weight(1f))
                Switch(checked = status.running, onCheckedChange = onToggle)
            }
            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        Section("Curbox on this phone") { CurboxAccessRows(status, onApprove, onChoose) }

        if (status.running) {
            Section("Pair your browser") {
                Text("In the Curbox extension open Reducers, then Phone over WiFi. Scan the code it shows.")
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan browser code") }
                Text(
                    when (status.browsers) {
                        0 -> "No browser paired yet."
                        1 -> "1 browser paired."
                        else -> "${status.browsers} browsers paired."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (status.addresses.isNotEmpty()) {
                    Label("Or type this into the browser")
                    status.addresses.forEach { Mono(it) }
                    Mono(status.code)
                }
                TextButton(onClick = onNewCode) { Text("Unpair all browsers") }
            }

            Section("Status") {
                val phone = status.phone
                Text(
                    if (phone.active) "Focus on: ${phone.name.ifEmpty { "Focus" }}, ends ${
                        DateUtils.getRelativeTimeSpanString(phone.endsAt)
                    }" else "Focus is off"
                )
                Text(
                    if (status.lastBrowserAt == 0L) "No browser has connected yet"
                    else "Browser last seen ${DateUtils.getRelativeTimeSpanString(status.lastBrowserAt)}"
                )
                if (status.lastEvent.isNotEmpty()) Text(status.lastEvent, style = MaterialTheme.typography.bodySmall)
            }
        }

        Section("Stay connected") {
            Text(
                "Let Curbox Sync run in the background so the browser can reach it while the screen is off.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onBattery) { Text("Battery settings") }
        }
    }
}

@Composable
private fun CurboxAccessRows(status: SyncStatus, onApprove: () -> Unit, onChoose: (String) -> Unit) {
    val phone = status.phone
    when {
        status.installs.isEmpty() -> Text("Curbox is not installed, or it is too old to offer its API.")
        status.selectedPackage == null -> {
            Text("More than one Curbox is installed. Pick the one to sync:")
            status.installs.forEach { install ->
                OutlinedButton(onClick = { onChoose(install.packageName) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${install.label} (${install.packageName})")
                }
            }
        }
        !status.running -> Text("Turn sync on to connect.")
        phone.access == CurboxAccess.NEEDS_APPROVAL -> {
            Text("Curbox needs to allow this app. Allowing it lets this app start and stop focus mode and read your focus groups.")
            Button(onClick = onApprove) { Text("Allow in Curbox") }
        }
        phone.access == CurboxAccess.READY -> {
            Text("Connected to ${status.selectedPackage} (API ${phone.apiVersion})")
            Text("${phone.groups.size} focus groups found")
            if (phone.apiVersion < 4) {
                Text(
                    "Update Curbox for exact end times. This version only shares whole minutes.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        else -> Text("Connecting to ${status.selectedPackage}…")
    }
    if (status.installs.size > 1 && status.selectedPackage != null) {
        status.installs.filter { it.packageName != status.selectedPackage }.forEach { install ->
            TextButton(onClick = { onChoose(install.packageName) }) { Text("Use ${install.label} instead") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun Mono(text: String) {
    SelectionContainer {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}
