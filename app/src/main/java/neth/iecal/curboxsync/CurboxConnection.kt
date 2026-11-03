package neth.iecal.curboxsync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import neth.iecal.curbox.api.ICurboxApi

data class CurboxInstall(val packageName: String, val label: String)

/** Owns the Binder connection to one Curbox installation. See CURBOX_API.md "Binding and lifecycle". */
class CurboxConnection(private val context: Context) {
    @Volatile
    var api: ICurboxApi? = null
        private set

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var bindingRequested = false
    var packageName: String? = null
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            api = ICurboxApi.Stub.asInterface(service)
            _connected.value = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            api = null
            _connected.value = false
        }

        override fun onBindingDied(name: ComponentName) {
            val pkg = packageName
            disconnect()
            if (pkg != null) connect(pkg)
        }

        override fun onNullBinding(name: ComponentName) {
            disconnect()
        }
    }

    fun installs(): List<CurboxInstall> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent(BIND_ACTION), 0)
            .distinctBy { it.serviceInfo.packageName }
            .map { CurboxInstall(it.serviceInfo.packageName, it.loadLabel(pm).toString()) }
    }

    fun connect(pkg: String) {
        if (bindingRequested && pkg == packageName) return
        disconnect()
        packageName = pkg
        bindingRequested = try {
            context.bindService(Intent(BIND_ACTION).setPackage(pkg), connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot bind to $pkg", e)
            false
        }
    }

    fun disconnect() {
        if (bindingRequested) {
            runCatching { context.unbindService(connection) }
            bindingRequested = false
        }
        api = null
        _connected.value = false
    }

    companion object {
        const val BIND_ACTION = "neth.iecal.curbox.api.BIND"
        const val PERMISSION_ACTION = "neth.iecal.curbox.api.REQUEST_PERMISSION"
        private const val TAG = "CurboxConnection"
    }
}
