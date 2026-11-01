package dev.dagent.core.tools

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.clip
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title

data class ShellResult(val output: String, val exitCode: Int, val timedOut: Boolean = false)

/** Runs commands in the phone's built-in Linux computer. Implemented by the app. */
interface ShellRunner {
    /** Null when ready; otherwise a sentence saying why commands cannot run yet. */
    fun unavailableReason(): String?
    suspend fun run(command: String, timeoutSeconds: Int, onOutput: (String) -> Unit): ShellResult
    fun startJob(command: String): String
    fun jobStatus(id: String): String
    fun stopJob(id: String): String
    fun jobs(): String
}

class ShellTool(private val shell: ShellRunner) : Tool {
    override val name = "shell"
    override val description =
        "Run a command in your own Linux computer (Alpine Linux on this phone, you are root). " +
            "The working folder is /workspace, shared with the file tools. Commands run with /bin/sh -c, stdout and stderr merged. " +
            "Install anything with apk (apk add python3 py3-pip nodejs ffmpeg git ...) or pip/npm. curl and wget reach the internet. " +
            "The `phone` command controls the phone from scripts (run `phone help`). " +
            "For servers or jobs longer than a few minutes set background=true and check them with shell_job."
    override val parameters = schema {
        title()
        string("command", "The command. Multi-line scripts are fine; for very long scripts write a file with file_write and run it.", required = true)
        integer("timeout", "Seconds before it is stopped (default 300, max 1800).")
        boolean("background", "Start it as a background job and return at once with a job id.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        shell.unavailableReason()?.let { return ToolResult.error(it) }
        val command = args.requireString("command")
        if (args.bool("background") == true) {
            val id = shell.startJob(command)
            return ToolResult.ok("Started background job $id. Check it with shell_job(action=\"status\", id=\"$id\").")
        }
        val timeout = (args.int("timeout") ?: 300).coerceIn(5, 1800)
        var last = 0L
        val res = shell.run(command, timeout) { chunk ->
            val now = System.currentTimeMillis()
            if (now - last > 400) {
                last = now
                chunk.lineSequence().lastOrNull { it.isNotBlank() }?.let { ctx.progress(it.take(160)) }
            }
        }
        val status = when {
            res.timedOut -> "[stopped after $timeout s; use background=true for long jobs]"
            else -> "[exit code ${res.exitCode}]"
        }
        val text = clip(res.output.trimEnd(), 16_000).ifEmpty { "(no output)" } + "\n" + status
        return if (res.exitCode != 0 || res.timedOut) ToolResult(text, isError = false) else ToolResult.ok(text)
    }
}

class ShellJobTool(private val shell: ShellRunner) : Tool {
    override val name = "shell_job"
    override val description = "Check, list or stop background jobs started with shell(background=true). status shows the latest output."
    override val parameters = schema {
        title()
        string("action", "What to do.", required = true, enum = listOf("status", "list", "stop"))
        string("id", "The job id (for status and stop).")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult = when (args.requireString("action")) {
        "list" -> ToolResult.ok(shell.jobs())
        "stop" -> ToolResult.ok(shell.stopJob(args.requireString("id")))
        else -> ToolResult.ok(clip(shell.jobStatus(args.requireString("id")), 12_000))
    }
}
