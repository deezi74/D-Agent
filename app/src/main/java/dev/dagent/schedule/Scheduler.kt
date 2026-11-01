package dev.dagent.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.dagent.DAgentApp
import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Runs a prompt later (once or every day) and posts the answer as a notification. */
class ScheduledTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prompt = inputData.getString("prompt") ?: return Result.failure()
        val label = inputData.getString("label") ?: "Scheduled task"
        val graph = (applicationContext as DAgentApp).graph
        return runCatching {
            graph.runner.runHeadless(prompt, label)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

class Scheduler(private val context: Context) {
    private val wm get() = WorkManager.getInstance(context)

    fun add(prompt: String, label: String, first: LocalDateTime, daily: Boolean): String {
        val delay = Duration.between(LocalDateTime.now(), first).toMillis().coerceAtLeast(0)
        val data = Data.Builder().putString("prompt", prompt).putString("label", label).build()
        val tag = "task:" + label.take(60)
        return if (daily) {
            val name = "daily-" + label.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(40)
            val req = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(data).addTag(TAG).addTag(tag)
                .addTag("when:" + first.toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)) + " daily").build()
            wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
            req.id.toString()
        } else {
            val req = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(data).addTag(TAG).addTag(tag)
                .addTag("when:" + first.format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US))).build()
            wm.enqueue(req)
            req.id.toString()
        }
    }

    fun list(): List<Triple<String, String, String>> = wm.getWorkInfosByTag(TAG).get()
        .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        .map { info ->
            Triple(
                info.id.toString(),
                info.tags.firstOrNull { it.startsWith("task:") }?.removePrefix("task:").orEmpty(),
                info.tags.firstOrNull { it.startsWith("when:") }?.removePrefix("when:").orEmpty(),
            )
        }

    fun cancel(id: String) = wm.cancelWorkById(java.util.UUID.fromString(id))

    companion object { const val TAG = "dagent-task" }
}

class ScheduleTool(private val scheduler: Scheduler) : Tool {
    override val name = "schedule"
    override val description = "Do something later without the person asking again: a reminder, a daily briefing, a price or news check. " +
        "add runs the prompt at a time (once or daily) and sends the answer as a notification. list and cancel manage them. For a plain alarm use the phone tool."
    override val parameters = schema {
        title()
        string("action", "add, list or cancel.", required = true, enum = listOf("add", "list", "cancel"))
        string("prompt", "For add: what to do then, written as an instruction to yourself (e.g. \"Check the weather in Austin and tell me if I need an umbrella\").")
        string("label", "For add: a short name.")
        string("at", "For add: when, as YYYY-MM-DD HH:MM, or HH:MM for the next time that clock time comes.")
        integer("in_minutes", "For add: or run this many minutes from now.")
        boolean("daily", "For add: repeat every day at that time.")
        string("id", "For cancel.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult = when (args.requireString("action")) {
        "list" -> ToolResult.ok(scheduler.list().joinToString("\n") { "${it.first}: ${it.second} (${it.third})" }.ifEmpty { "Nothing scheduled." })
        "cancel" -> { scheduler.cancel(args.requireString("id")); ToolResult.ok("Cancelled.") }
        else -> {
            val prompt = args.requireString("prompt")
            val label = args.string("label")?.takeIf { it.isNotBlank() } ?: prompt.take(40)
            val now = LocalDateTime.now()
            val first = args.int("in_minutes")?.let { now.plusMinutes(it.toLong()) } ?: parseWhen(args.string("at").orEmpty(), now)
                ?: return ToolResult.error("Give a time with at (YYYY-MM-DD HH:MM or HH:MM) or in_minutes.")
            val id = scheduler.add(prompt, label, first, args.bool("daily") == true)
            ToolResult.ok("Scheduled \"$label\" for ${first.format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US))}${if (args.bool("daily") == true) ", every day" else ""}. Id: $id")
        }
    }

    private fun parseWhen(s: String, now: LocalDateTime): LocalDateTime? {
        val t = s.trim().replace('T', ' ')
        for (p in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd H:mm", "yyyy-MM-dd h:mm a")) {
            runCatching { return LocalDateTime.parse(t, DateTimeFormatter.ofPattern(p, Locale.US)) }
        }
        for (p in listOf("H:mm", "HH:mm", "h:mm a", "h a")) {
            runCatching {
                val time = LocalTime.parse(t.uppercase(), DateTimeFormatter.ofPattern(p, Locale.US))
                val today = now.toLocalDate().atTime(time)
                return if (today.isAfter(now)) today else today.plusDays(1)
            }
        }
        return null
    }
}
