package dev.dagent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.dagent.R
import dev.dagent.phone.PhoneActions

/** Keeps the app alive while the agent works, so tasks finish with the screen off. */
class AgentService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PhoneActions.ensureChannel(this)
        val name = intent?.getStringExtra("name") ?: "D-Agent"
        val n = NotificationCompat.Builder(this, PhoneActions.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$name is working")
            .setContentText("Tap to watch")
            .setOngoing(true)
            .setContentIntent(PhoneActions.launchIntent(this))
            .build()
        val ok = runCatching {
            ServiceCompat.startForeground(
                this, 7, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.isSuccess
        // Android refuses some foreground starts (e.g. the daily data-sync time limit on Android 15+).
        // A service left un-promoted after startForegroundService() crashes the app, so give up quietly:
        // the agent still runs while the app is open.
        if (!ok || !active) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dagent:agent").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        /** False after stop(), so a start that arrives late doesn't keep a notification up. */
        @Volatile private var active = false

        /**
         * Called while the person is looking at the app, so a plain startService is allowed and has
         * no 10-second deadline (startForegroundService crashes the app when the run ends, or
         * Android refuses the foreground start, before the service gets going). Only from the
         * background does it fall back to startForegroundService.
         */
        fun start(context: Context, name: String) {
            active = true
            val intent = Intent(context, AgentService::class.java).putExtra("name", name)
            runCatching { context.startService(intent) }.onFailure {
                runCatching { context.startForegroundService(intent) }
            }
        }

        fun stop(context: Context) {
            active = false
            runCatching { context.stopService(Intent(context, AgentService::class.java)) }
        }
    }
}
