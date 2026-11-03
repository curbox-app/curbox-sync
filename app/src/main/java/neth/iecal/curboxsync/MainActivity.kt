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
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import neth.iecal.curboxsync.ui.Gutter
import neth.iecal.curboxsync.ui.NetUi
import neth.iecal.curboxsync.ui.RenameDialog
import neth.iecal.curboxsync.ui.SyncActions
import neth.iecal.curboxsync.ui.Tabs
import neth.iecal.curboxsync.ui.UsageActions
import neth.iecal.curboxsync.ui.syncContent
import neth.iecal.curboxsync.ui.usageContent
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

    private val vm: MainViewModel by viewModels()

    private val syncActions by lazy {
        SyncActions(
            onToggle = { on -> if (on) SyncService.start(this) else SyncService.stop(this) },
            onApprove = ::requestApproval,
            onChoose = vm::choose,
            onUnpairAll = vm::unpairAll,
            onBattery = ::openBatterySettings,
            onScan = ::scanBrowserCode,
            onRename = {},
            onForget = vm::forget,
            onToggleManual = {},
        )
    }

    private val usageActions by lazy { UsageActions(vm::selectDay, vm::previousWeek, vm::nextWeek) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Prefs(this).enabled) SyncService.start(this)
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        handlePairingLink(intent)
        SyncRuntime.loadNetworks(this)
        setContent {
            CurboxSyncTheme {
                Home(vm, syncActions, usageActions)
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
private fun Home(vm: MainViewModel, sync: SyncActions, usage: UsageActions) {
    val syncUi by vm.sync.collectAsStateWithLifecycle()
    val usageUi by vm.usage.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showManual by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<NetUi?>(null) }
    val syncList = rememberLazyListState()
    val usageList = rememberLazyListState()
    val actions = remember(sync) {
        SyncActions(
            onToggle = sync.onToggle, onApprove = sync.onApprove, onChoose = sync.onChoose,
            onUnpairAll = sync.onUnpairAll, onBattery = sync.onBattery, onScan = sync.onScan,
            onRename = { renaming = it }, onForget = sync.onForget, onToggleManual = { showManual = !showManual },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Curbox Sync", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Tabs(listOf("Sync", "Usage"), tab, { tab = it })
        }
        Crossfade(tab, animationSpec = tween(220), label = "tab") { t ->
            LazyColumn(
                state = if (t == 0) syncList else usageList,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 32.dp) +
                    WindowInsets.navigationBars.asPaddingValues(),
            ) {
                if (t == 0) syncContent(syncUi, showManual, actions) else usageContent(usageUi, usage)
            }
        }
    }

    renaming?.let { net ->
        RenameDialog(net) { label ->
            if (label != null) vm.rename(net.id, label)
            renaming = null
        }
    }
}

@Composable
private operator fun PaddingValues.plus(other: PaddingValues): PaddingValues {
    val dir = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(dir) + other.calculateStartPadding(dir),
        top = calculateTopPadding() + other.calculateTopPadding(),
        end = calculateEndPadding(dir) + other.calculateEndPadding(dir),
        bottom = calculateBottomPadding() + other.calculateBottomPadding(),
    )
}
