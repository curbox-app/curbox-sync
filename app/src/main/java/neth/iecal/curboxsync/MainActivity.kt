package neth.iecal.curboxsync

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import neth.iecal.curboxsync.ui.Gutter
import neth.iecal.curboxsync.ui.SyncActions
import neth.iecal.curboxsync.ui.SyncScreen
import neth.iecal.curboxsync.ui.Tabs
import neth.iecal.curboxsync.ui.UsageScreen
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

    private val actions by lazy {
        SyncActions(
            onToggle = { on -> if (on) SyncService.start(this) else SyncService.stop(this) },
            onApprove = ::requestApproval,
            onChoose = SyncRuntime::choose,
            onUnpairAll = SyncRuntime::newCode,
            onBattery = ::openBatterySettings,
            onScan = ::scanBrowserCode,
            onRename = { id, label -> SyncRuntime.renameNetwork(this, id, label) },
            onForget = { id -> SyncRuntime.forgetNetwork(this, id) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Prefs(this).enabled) SyncService.start(this)
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        handlePairingLink(intent)
        SyncRuntime.loadNetworks(this)
        setContent {
            CurboxSyncTheme {
                val status by SyncRuntime.status.collectAsStateWithLifecycle()
                val usage by SyncRuntime.usage(this).snapshot.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Home(status, usage, actions, padding)
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
private fun Home(status: SyncStatus, usage: UsageSnapshot, actions: SyncActions, padding: PaddingValues) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = Gutter)
            .padding(bottom = 32.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Curbox Sync", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Tabs(listOf("Sync", "Usage"), tab, { tab = it })
        }
        Crossfade(tab, label = "tab") { t ->
            if (t == 0) SyncScreen(status, actions) else UsageScreen(usage)
        }
    }
}
