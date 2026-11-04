package neth.iecal.curboxsync

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Curbox binds this from its always on blocker service (API 6 HOST_COMPANION). While bound, this
 * process shares Curbox's priority, so sync runs without a foreground service or notification.
 */
class CompanionService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder? {
        // Declining makes Curbox drop the binding until sync asks again.
        if (!Prefs(this).enabled) return null
        SyncService.onHosted(applicationContext, true)
        return binder
    }

    override fun onDestroy() {
        SyncService.onHosted(applicationContext, false)
        super.onDestroy()
    }
}
