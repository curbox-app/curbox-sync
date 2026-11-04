package neth.iecal.curboxsync

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import neth.iecal.curboxsync.ui.Gutter
import neth.iecal.curboxsync.ui.NetUi
import neth.iecal.curboxsync.ui.PhoneCodeDialog
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

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val vm: MainViewModel by viewModels()

    private val syncActions by lazy {
        SyncActions(
            onToggle = { on -> if (on) SyncService.start(this) else SyncService.stop(this) },
            onApprove = ::requestApproval,
            onChoose = vm::choose,
            onUnpairAll = vm::unpairAll,
            onBattery = ::openBatterySettings,
            onRename = {},
            onForget = vm::forget,
            onForgetPhone = vm::forgetPhone,
            onTailscale = vm::tailscale,
        )
    }

    private val usageActions by lazy { UsageActions(vm::selectDay, vm::previousWeek, vm::nextWeek) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Prefs(this).enabled) SyncService.start(this)
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        SyncRuntime.loadNetworks(this)
        setContent {
            CurboxSyncTheme {
                Home(vm, syncActions, usageActions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SyncRuntime.pairingOpen = true
        SyncRuntime.recheck()
    }

    override fun onPause() {
        SyncRuntime.pairingOpen = false
        super.onPause()
    }

    private fun requestApproval() {
        val pkg = SyncRuntime.status.value.selectedPackage ?: return
        try {
            approval.launch(Intent(CurboxConnection.PERMISSION_ACTION).setPackage(pkg))
        } catch (e: ActivityNotFoundException) {
            SyncRuntime.recheck()
        }
    }

    private fun openBatterySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

@Composable
private fun Home(vm: MainViewModel, sync: SyncActions, usage: UsageActions) {
    val syncUi by vm.sync.collectAsStateWithLifecycle()
    val usageUi by vm.usage.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showPair by rememberSaveable { mutableStateOf(false) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<NetUi?>(null) }
    var addingPhone by rememberSaveable { mutableStateOf(false) }
    var pairingPhone by remember { mutableStateOf(false) }
    var phoneNote by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val syncList = rememberLazyListState()
    val usageList = rememberLazyListState()
    val actions = remember(sync) {
        SyncActions(
            onToggle = sync.onToggle, onApprove = sync.onApprove, onChoose = sync.onChoose,
            onUnpairAll = sync.onUnpairAll, onBattery = sync.onBattery,
            onRename = { renaming = it }, onForget = sync.onForget,
            onAddPhone = { phoneNote = null; addingPhone = true }, onForgetPhone = sync.onForgetPhone,
            onTailscale = sync.onTailscale,
            onTogglePair = { showPair = !showPair }, onToggleMore = { showMore = !showMore },
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
                if (t == 0) syncContent(syncUi, actions, showPair, showMore) else usageContent(usageUi, usage)
            }
        }
    }

    if (addingPhone) {
        PhoneCodeDialog(pairingPhone, phoneNote) { code ->
            if (code == null) {
                addingPhone = false
                return@PhoneCodeDialog
            }
            pairingPhone = true
            scope.launch {
                val note = SyncRuntime.pairWithPhone(context, code)
                pairingPhone = false
                if (note.startsWith("Paired")) addingPhone = false else phoneNote = note
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
