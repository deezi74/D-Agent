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
        runCatching {
            ServiceCompat.startForeground(
                this, 7, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
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
        fun start(context: Context, name: String) {
            runCatching {
                context.startForegroundService(Intent(context, AgentService::class.java).putExtra("name", name))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }
}
