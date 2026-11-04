package neth.iecal.curboxsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps sync running while the screen is off, unless Curbox hosts it through [CompanionService].
 * Then this service stops and its notification goes away.
 */
class SyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop(this)
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        SyncRuntime.start(applicationContext)
        // Curbox may have bound us while this start was on its way.
        if (hosted) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        if (!hosted) SyncRuntime.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "WiFi sync", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SyncService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Focus sync is on")
            .setContentText("Keeping focus mode in step with your browser over WiFi")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "neth.iecal.curboxsync.STOP"

        private const val TAG = "SyncService"

        /** True while Curbox keeps this process alive, so no foreground service is needed. */
        @Volatile
        var hosted = false
            private set

        fun start(context: Context) {
            Prefs(context).enabled = true
            if (hosted) SyncRuntime.start(context.applicationContext)
            else ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
        }

        fun isRunning() = SyncRuntime.status.value.running

        fun stop(context: Context) {
            Prefs(context).enabled = false
            SyncRuntime.stop(releaseHost = true)
            context.stopService(Intent(context, SyncService::class.java))
        }

        /** Curbox started or stopped hosting this app. */
        fun onHosted(context: Context, on: Boolean) {
            if (on == hosted) return
            hosted = on
            SyncRuntime.setHosted(on)
            if (on) {
                SyncRuntime.start(context)
                context.stopService(Intent(context, SyncService::class.java))
            } else if (Prefs(context).enabled) {
                // Curbox stopped (for example its accessibility service was turned off). Battery
                // exemption, which hosting requires, allows this start from the background.
                try {
                    ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
                } catch (e: Exception) {
                    Log.w(TAG, "Could not fall back to a foreground service", e)
                    SyncRuntime.stop()
                }
            } else {
                SyncRuntime.stop()
            }
        }
    }
}
