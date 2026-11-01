package dev.dagent.connectors

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.core.content.ContextCompat
import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Runs commands in the person's own Termux through its RUN_COMMAND API. Termux
 * only accepts them after the person sets allow-external-apps=true and grants
 * TruAgent the "Run commands in Termux" permission, and the person also has to
 * turn this on in TruAgent. Long jobs (an Android build) run in the background
 * with a log file the agent can check.
 */
class TermuxBridge(private val context: Context) {

    data class Result(val stdout: String, val stderr: String, val exitCode: Int?, val error: String?)

    fun isInstalled(): Boolean = runCatching { context.packageManager.getPackageInfo(TERMUX, 0) }.isSuccess

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Runs `bash -lc script` in Termux's home and waits up to [timeoutMs] for the result. */
    suspend fun run(script: String, timeoutMs: Long = 120_000): Result {
        if (!isInstalled()) return Result("", "", null, "Termux isn't installed. Install it from F-Droid.")
        if (!hasPermission()) return Result("", "", null, "D-Agent isn't allowed to run commands in Termux yet. Grant it in Settings > Connectors > Termux.")
        val action = "dev.dagent.TERMUX_RESULT." + UUID.randomUUID()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        runCatching { context.unregisterReceiver(this) }
                        val b: Bundle? = intent.getBundleExtra("result")
                        val r = Result(
                            stdout = b?.getString("stdout").orEmpty(),
                            stderr = b?.getString("stderr").orEmpty(),
                            exitCode = b?.takeIf { it.containsKey("exitCode") }?.getInt("exitCode"),
                            error = b?.getString("errmsg")?.takeIf { it.isNotBlank() && (b.getInt("err", -1) != -1) }
                        )
                        if (cont.isActive) cont.resume(r)
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                val flags = PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val reply = PendingIntent.getBroadcast(context, action.hashCode(), Intent(action).setPackage(context.packageName), flags)
                val intent = Intent("com.termux.RUN_COMMAND")
                    .setComponent(ComponentName(TERMUX, "com.termux.app.RunCommandService"))
                    .putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash")
                    .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
                    .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
                    .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                    .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "D-Agent")
                    .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", reply)
                try {
                    try {
                        context.startService(intent)
                    } catch (e: IllegalStateException) {
                        context.startForegroundService(intent)
                    }
                } catch (e: Exception) {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(Result("", "", null, "Termux refused the command: ${e.message}. Check allow-external-apps in ~/.termux/termux.properties."))
                }
            }
        } ?: Result("", "", null, "no answer from Termux within ${timeoutMs / 1000}s. For long work use action \"start\" (runs in the background).")
    }

    companion object {
        const val TERMUX = "com.termux"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val PREFIX = "/data/data/com.termux/files/usr"
        const val HOME = "/data/data/com.termux/files/home"
    }
}

class TermuxTool(private val termux: TermuxBridge, private val enabled: () -> Boolean) : Tool {
    override val name = "termux"
    override val description =
        "Run shell commands in the person's own Termux app (separate from your built-in Linux computer; use this for things set up there, like FreeLLMAPI). " +
            "run: a short command (2 min max). start: a long command in the background, returns a job id; job: show a job's log and exit code. " +
            "write: write a text file under the Termux home. freellmapi: start the FreeLLMAPI server (~/freellmapi, npm run dev, port 3001). setup_android: install JDK, Gradle and the Android SDK (about 1 GB). " +
            "Explain what a command does before running anything that changes files."
    override val parameters = schema {
        title()
        string("action", "What to do (default run).", enum = listOf("run", "start", "job", "write", "freellmapi", "setup_android"))
        string("command", "run/start: the shell command.")
        string("id", "job: the job id.")
        string("path", "write: path under the Termux home.")
        string("content", "write: file text.")
        integer("timeout", "run: seconds to wait, 5-120.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        if (!enabled()) return ToolResult.error("The Termux connector is off. The person can turn it on in Settings > Connectors > Termux.")
        if (!termux.isInstalled()) return ToolResult.error("Termux isn't installed on this phone (get it from F-Droid).")
        val arguments = args
        val action = arguments.string("action")?.lowercase(Locale.US) ?: "run"
        return when (action) {
            "run", "exec" -> {
                val cmd = arguments.string("command") ?: return ToolResult.error("missing \"command\"")
                blocked(cmd)?.let { return ToolResult.error(it) }
                report(termux.run(cmd, ((arguments.int("timeout") ?: 60).coerceIn(5, 120)) * 1000L))
            }
            "start", "background" -> {
                val cmd = arguments.string("command") ?: return ToolResult.error("missing \"command\"")
                blocked(cmd)?.let { return ToolResult.error(it) }
                startJob(cmd)
            }
            "job", "log", "status" -> {
                val id = arguments.string("id")?.takeIf { JOB.matches(it) } ?: return ToolResult.error("missing or bad job \"id\"")
                report(
                    termux.run(
                        "cd ~/.dagent/jobs && if [ -f $id.exit ]; then echo \"[finished, exit code \$(cat $id.exit)]\"; else echo '[still running]'; fi; " +
                            "echo '--- last lines ---'; tail -n 60 $id.log 2>/dev/null"
                    )
                )
            }
            "write" -> {
                val path = arguments.string("path") ?: return ToolResult.error("missing \"path\"")
                val content = arguments.string("content") ?: return ToolResult.error("missing \"content\"")
                val clean = homePath(path) ?: return ToolResult.error("path must stay inside the Termux home folder")
                report(termux.run(writeScript(mapOf(clean to content))), ok = "Wrote ~/$clean")
            }
            "freellmapi" -> startJob(FREELLMAPI_SCRIPT)
            "setup_android" -> startJob(SETUP_SCRIPT)
            else -> ToolResult.error("unknown action \"$action\"")
        }
    }

    private suspend fun startJob(cmd: String): ToolResult {
        val id = "job" + System.currentTimeMillis().toString(36)
        val b64 = Base64.encodeToString(cmd.toByteArray(), Base64.NO_WRAP)
        val script = "mkdir -p ~/.dagent/jobs && cd ~/.dagent/jobs && echo '$b64' | base64 -d > $id.sh && " +
            "(command -v termux-wake-lock >/dev/null && termux-wake-lock; nohup bash -l $id.sh > $id.log 2>&1; echo \$? > $id.exit) > /dev/null 2>&1 & echo started"
        val r = termux.run(script, 30_000)
        if (r.error != null) return ToolResult.error(r.error)
        return ToolResult.ok("Started background job $id. Check it with action \"job\" and id \"$id\" (long builds take 5 to 30 minutes on a phone).")
    }

    private fun report(r: TermuxBridge.Result, ok: String? = null): ToolResult {
        if (r.error != null) return ToolResult.error(r.error)
        val out = buildString {
            if (ok != null && r.exitCode == 0) append(ok).append('\n')
            append("exit code ").append(r.exitCode ?: "?").append('\n')
            if (r.stdout.isNotBlank()) append(r.stdout.takeLast(MAX)).append('\n')
            if (r.stderr.isNotBlank()) append("stderr:\n").append(r.stderr.takeLast(MAX / 2))
        }.trim()
        return if (r.exitCode == 0 || r.exitCode == null) ToolResult.ok(out) else ToolResult.error(out)
    }

    private fun writeScript(files: Map<String, String>): String = files.entries.joinToString(" && ") { (path, content) ->
        val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        "mkdir -p \"\$(dirname ~/'$path')\" && echo '$b64' | base64 -d > ~/'$path'"
    }

    private fun homePath(path: String): String? {
        val parts = ArrayDeque<String>()
        for (seg in path.trim().removePrefix("~").removePrefix(TermuxBridge.HOME).split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull() ?: return null
                else -> if (seg.contains('\'')) return null else parts.addLast(seg)
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    private fun safeName(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(60).ifBlank { "app" }

    /** Refuses a few commands that wipe the phone's storage or the Termux install. */
    private fun blocked(cmd: String): String? {
        val c = cmd.replace(Regex("\\s+"), " ")
        val bad = listOf(
            Regex("rm -[a-zA-Z]*[rf][a-zA-Z]* +(/|~|\\\$HOME|/data|/sdcard|/storage|~/storage)/?( |$|;|&)"),
            Regex("\\bmkfs"), Regex("\\bdd\\b.*of=/dev/"), Regex(":\\(\\)\\s*\\{")
        )
        return if (bad.any { it.containsMatchIn(c) }) "that command could wipe your files, so D-Agent won't run it. Run it yourself in Termux if you really mean it." else null
    }

    companion object {
        private val JOB = Regex("^job[a-z0-9]{4,16}$")
        private const val MAX = 6000

        /** Starts FreeLLMAPI in Termux the way the person runs it: ~/freellmapi, wake lock, npm run dev. */
        val FREELLMAPI_SCRIPT = """
            cd ~/freellmapi || { echo "No ~/freellmapi folder. Clone FreeLLMAPI there first."; exit 1; }
            command -v termux-wake-lock >/dev/null && termux-wake-lock
            [ -d node_modules ] || npm install
            npm run dev
        """.trimIndent()

        /** One-time Android build setup inside Termux (aarch64): JDK, Gradle, aapt2 and the SDK platform. */
        val SETUP_SCRIPT = """
            set -e
            yes | pkg update
            pkg install -y openjdk-17 gradle aapt2 wget unzip git
            SDK=${'$'}HOME/android-sdk
            mkdir -p ${'$'}SDK/cmdline-tools
            if [ ! -x ${'$'}SDK/cmdline-tools/latest/bin/sdkmanager ]; then
              wget -q -O ${'$'}PREFIX/tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
              rm -rf ${'$'}SDK/cmdline-tools/latest ${'$'}SDK/cmdline-tools/cmdline-tools
              unzip -q ${'$'}PREFIX/tmp/clt.zip -d ${'$'}SDK/cmdline-tools
              mv ${'$'}SDK/cmdline-tools/cmdline-tools ${'$'}SDK/cmdline-tools/latest
            fi
            yes | ${'$'}SDK/cmdline-tools/latest/bin/sdkmanager --sdk_root=${'$'}SDK "platforms;android-35" "build-tools;35.0.0" > /dev/null
            grep -q ANDROID_HOME ~/.bashrc 2>/dev/null || echo "export ANDROID_HOME=${'$'}SDK" >> ~/.bashrc
            mkdir -p ~/.gradle
            grep -q aapt2FromMavenOverride ~/.gradle/gradle.properties 2>/dev/null || echo "android.aapt2FromMavenOverride=${'$'}PREFIX/bin/aapt2" >> ~/.gradle/gradle.properties
            java -version 2>&1 | head -n1
            gradle --version | grep Gradle
            echo "Android build setup finished."
        """.trimIndent()
    }
}
