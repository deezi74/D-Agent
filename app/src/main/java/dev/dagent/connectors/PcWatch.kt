package dev.dagent.connectors

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.dagent.DAgentApp
import dev.dagent.Graph
import dev.dagent.R
import dev.dagent.core.tools.PcClient
import dev.dagent.core.tools.PcTool
import dev.dagent.phone.PhoneActions
import java.util.concurrent.TimeUnit

/**
 * Tells the person when a task they handed to their PC finishes, even with D-Agent closed.
 * A periodic check (every 15 minutes, Android's shortest) plus quicker one-off checks right
 * after a task starts.
 */
object PcWatch {
    private const val PERIODIC = "pc-watch"
    private const val SOON = "pc-watch-soon"

    fun client(g: Graph) = PcClient({ g.connectors.pcUrl }, { g.connectors.pcCode }, g.http)

    fun add(context: Context, g: Graph, id: String, title: String) {
        g.connectors.pcWatched = g.connectors.pcWatched + "$id|${title.replace('|', ' ').take(80)}"
        schedule(context, soon = true)
    }

    fun schedule(context: Context, soon: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PcWatchWorker>(15, TimeUnit.MINUTES).setConstraints(net).build(),
        )
        if (soon) wm.enqueueUniqueWork(
            SOON, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PcWatchWorker>().setInitialDelay(2, TimeUnit.MINUTES).setConstraints(net).build(),
        )
    }

    /** Checks every watched task; notifies and forgets the finished ones. True while some still run. */
    suspend fun check(context: Context, g: Graph): Boolean {
        val watched = g.connectors.pcWatched
        if (watched.isEmpty() || !client(g).ready()) return false
        val pc = client(g)
        var stillRunning = false
        val done = mutableSetOf<String>()
        for (entry in watched) {
            val id = entry.substringBefore('|')
            val t = runCatching { pc.task(id) }.getOrElse { e ->
                // A task the PC forgot (deleted) is dropped; a PC that's off is tried again later.
                if (e.message.orEmpty().contains("No such task")) done += entry else stillRunning = true
                null
            } ?: continue
            if (PcTool.status(t) in PcTool.RUNNING) { stillRunning = true; continue }
            done += entry
            notify(context, g, PcTool.title(t), PcTool.status(t), PcTool.result(t))
        }
        if (done.isNotEmpty()) g.connectors.pcWatched = g.connectors.pcWatched - done
        return stillRunning
    }

    private fun notify(context: Context, g: Graph, title: String, status: String, result: String) {
        runCatching {
            PhoneActions.ensureChannel(context)
            val head = when (status) {
                "done" -> "Done on your PC: $title"
                "cancelled" -> "Cancelled on your PC: $title"
                else -> "PC task stopped: $title"
            }
            val n = NotificationCompat.Builder(context, PhoneActions.CHANNEL_AGENT)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(head)
                .setContentText(result.take(200))
                .setStyle(NotificationCompat.BigTextStyle().bigText(result.take(1500)))
                .setAutoCancel(true)
                .setContentIntent(PhoneActions.launchIntent(context))
                .build()
            NotificationManagerCompat.from(context).notify((title.hashCode() and 0xffff) + 50_000, n)
        }
    }
}

class PcWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as DAgentApp).graph
        val running = PcWatch.check(applicationContext, g)
        // While something still runs, look again sooner than the 15-minute cycle.
        if (running && tags.none { it.contains("periodic", true) }) PcWatch.schedule(applicationContext, soon = true)
        return Result.success()
    }
}
