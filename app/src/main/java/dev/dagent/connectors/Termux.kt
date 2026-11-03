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

    /** True when Termux actually runs a command for us (it silently drops them when allow-external-apps is off). */
    suspend fun answers(): Boolean = run("echo DAGENT_OK", 15_000).stdout.contains("DAGENT_OK")

    /** Termux from the Play Store is years old and ignores commands from other apps. */
    fun isOutdated(): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(TERMUX, 0)
        @Suppress("DEPRECATION") val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        code < 118
    }.getOrDefault(false)

    /** True when something answers on 127.0.0.1:[port]. */
    suspend fun portOpen(port: Int): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 400) }; true }.getOrDefault(false)
    }

    /**
     * Opens a visible Termux session running [script], the same way the person starts a server by
     * hand. Termux keeps foreground sessions alive, so long-running servers survive.
     */
    fun openSession(script: String, label: String): String? = runCatching {
        val intent = Intent("com.termux.RUN_COMMAND")
            .setComponent(ComponentName(TERMUX, "com.termux.app.RunCommandService"))
            .putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "1") // open the session but stay in D-Agent
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label)
        try { context.startService(intent) } catch (e: IllegalStateException) { context.startForegroundService(intent) }
        null
    }.getOrElse { it.message ?: "Termux refused the command" }

    /** Last resort: put [command] on the clipboard and open Termux so the person can paste it. */
    suspend fun pasteFallback(command: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Termux command", command))
        context.packageManager.getLaunchIntentForPackage(TERMUX)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** What's missing before D-Agent can run commands in Termux, or null when it's ready. */
    fun problem(): String? = when {
        !isInstalled() -> "Termux isn't installed. Get it from F-Droid (f-droid.org/packages/com.termux), not the Play Store."
        isOutdated() -> "This Termux is the old Play Store version, which doesn't accept commands from other apps. Install Termux from F-Droid instead."
        !hasPermission() -> "D-Agent needs permission to run Termux commands: open Settings > Connectors > Termux and tap Allow."
        else -> null
    }

    companion object {
        const val FREELLMAPI_COMMAND = "cd ~/freellmapi && termux-wake-lock && npm run dev"
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
            "write: write a text file under the Termux home. freellmapi: start the FreeLLMAPI server (~/freellmapi, npm run dev, port 3001). voice: install and start the free natural voice server (port 5050). setup_android: install JDK, Gradle and the Android SDK (about 1 GB). " +
            "Explain what a command does before running anything that changes files."
    override val parameters = schema {
        title()
        string("action", "What to do (default run).", enum = listOf("run", "start", "job", "write", "freellmapi", "voice", "setup_android"))
        string("command", "run/start: the shell command.")
        string("id", "job: the job id.")
        string("path", "write: path under the Termux home.")
        string("content", "write: file text.")
        integer("timeout", "run: seconds to wait, 5-120.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val arguments = args
        val action = arguments.string("action")?.lowercase(Locale.US) ?: "run"
        if (action == "freellmapi") return startFreeLlmApi(ctx)
        if (action == "voice") return startVoiceServer(ctx)
        termux.problem()?.let { return ToolResult.error(it + SETUP_HINT) }
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
            "setup_android" -> startJob(SETUP_SCRIPT)
            else -> ToolResult.error("unknown action \"$action\"")
        }
    }

    /**
     * Starts FreeLLMAPI the way the person does by hand (a visible Termux session), then waits for
     * port 3001. If Termux can't take commands, copies the command and opens Termux to paste it.
     */
    suspend fun startFreeLlmApi(ctx: ToolContext): ToolResult {
        if (termux.portOpen(3001)) return ToolResult.ok("FreeLLMAPI is already running on http://localhost:3001/v1.")
        val problem = termux.problem()
        if (problem != null) {
            if (!termux.isInstalled() || termux.isOutdated()) return ToolResult.error(problem)
            termux.pasteFallback(FREELLMAPI_START)
            return ToolResult.ok("$problem\nFor now I copied the start command and opened Termux: the person just long-presses, pastes and presses Enter. Tell them that.")
        }
        // Termux drops commands silently when allow-external-apps is off, so check it answers first.
        ctx.progress("Checking Termux...")
        if (!termux.answers()) {
            termux.pasteFallback(FREELLMAPI_START)
            return ToolResult.ok(NOT_ANSWERING + " For now I copied the start command and opened Termux: long-press, Paste, Enter.")
        }
        ctx.progress("Starting FreeLLMAPI in Termux...")
        termux.openSession(FREELLMAPI_SCRIPT, "FreeLLMAPI")?.let { err ->
            termux.pasteFallback(FREELLMAPI_START)
            return ToolResult.ok("Termux didn't take the command ($err$SETUP_HINT). I copied the start command and opened Termux so the person can paste it and press Enter.")
        }
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(2_000)
            if (termux.portOpen(3001)) return ToolResult.ok("FreeLLMAPI is running on http://localhost:3001/v1 (a Termux session named FreeLLMAPI keeps it going).")
        }
        val log = termux.run("ls -d ~/freellmapi >/dev/null 2>&1 && echo HAS_DIR || echo NO_DIR; tail -n 15 ~/.dagent/freellmapi.log 2>/dev/null", 15_000)
        if (log.stdout.contains("NO_DIR")) return ToolResult.error("There's no ~/freellmapi folder in Termux, so there's nothing to start. Clone FreeLLMAPI into ~/freellmapi first.")
        val tail = log.stdout.substringAfter("HAS_DIR").trim().takeLast(1500)
        return ToolResult.error("Started FreeLLMAPI in Termux but port 3001 didn't open within 90 seconds." +
            (if (tail.isNotBlank()) " Its last output:\n$tail\n" else " Ask the person to open Termux and look at the FreeLLMAPI session for the error.") +
            if (log.error != null) " (Termux also said: ${log.error}$SETUP_HINT)" else "")
    }

    /**
     * Installs (first time) and starts the free voice server in a Termux session on port 5050: a
     * small OpenAI-style /v1/audio/speech endpoint backed by the edge-tts Python package.
     */
    suspend fun startVoiceServer(ctx: ToolContext): ToolResult {
        val port = dev.dagent.phone.CloudVoice.TERMUX_PORT
        if (termux.portOpen(port)) return ToolResult.ok("The free voice is already running. Pick it in Settings > Voice.")
        termux.problem()?.let { return ToolResult.error(it + SETUP_HINT) }
        if (!termux.answers()) return ToolResult.error(NOT_ANSWERING)
        ctx.progress("Setting up the free voice in Termux...")
        val b64 = Base64.encodeToString(VOICE_SERVER_PY.toByteArray(), Base64.NO_WRAP)
        val script = "mkdir -p ~/.dagent/voice && echo '$b64' | base64 -d > ~/.dagent/voice/server.py && " +
            "(command -v python >/dev/null || pkg install -y python) && (python -c 'import edge_tts' 2>/dev/null || AIOHTTP_NO_EXTENSIONS=1 MULTIDICT_NO_EXTENSIONS=1 YARL_NO_EXTENSIONS=1 FROZENLIST_NO_EXTENSIONS=1 PROPCACHE_NO_EXTENSIONS=1 pip install edge-tts) && " +
            "command -v termux-wake-lock >/dev/null && termux-wake-lock; python ~/.dagent/voice/server.py"
        termux.openSession(script, "D-Agent voice")?.let { return ToolResult.error("Termux didn't take the command ($it$SETUP_HINT).") }
        val deadline = System.currentTimeMillis() + 240_000
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(3_000)
            if (termux.portOpen(port)) return ToolResult.ok("The free voice is running in Termux (session \"D-Agent voice\"). Pick \"Free natural voice\" in Settings > Voice.")
        }
        return ToolResult.error("The voice server didn't start within 4 minutes. Open Termux and check the \"D-Agent voice\" session for the error.")
    }

    suspend fun startJob(cmd: String): ToolResult {
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

        const val FREELLMAPI_START = TermuxBridge.FREELLMAPI_COMMAND
        const val NOT_ANSWERING = "Termux isn't accepting commands from D-Agent right now (it ignores them without saying why). " +
            "Fix: open Termux and run  echo allow-external-apps=true >> ~/.termux/termux.properties ; termux-reload-settings  " +
            "(if that says Permission denied, first run  chmod u+w ~/.termux/termux.properties ), then close Termux and try again."
        const val SETUP_HINT = ". One-time Termux setup: in Termux run  echo allow-external-apps=true >> ~/.termux/termux.properties && termux-reload-settings  then allow D-Agent in Settings > Connectors > Termux"

        /** A tiny OpenAI-style speech server: POST /v1/audio/speech {input, voice} returns MP3. */
        val VOICE_SERVER_PY = """
            import asyncio, json
            from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
            import edge_tts

            async def speak(text, voice):
                out = bytearray()
                async for chunk in edge_tts.Communicate(text, voice).stream():
                    if chunk["type"] == "audio":
                        out += chunk["data"]
                return bytes(out)

            class Handler(BaseHTTPRequestHandler):
                def do_POST(self):
                    if not self.path.startswith("/v1/audio/speech"):
                        self.send_error(404); return
                    body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
                    try:
                        audio = asyncio.run(speak(body.get("input", ""), body.get("voice") or "en-US-AvaNeural"))
                    except Exception as e:
                        msg = json.dumps({"error": {"message": str(e)}}).encode()
                        self.send_response(500); self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(msg); return
                    self.send_response(200); self.send_header("Content-Type", "audio/mpeg"); self.send_header("Content-Length", str(len(audio))); self.end_headers()
                    self.wfile.write(audio)
                def do_GET(self):
                    self.send_response(200); self.end_headers(); self.wfile.write(b"D-Agent voice is running")
                def log_message(self, *a): pass

            print("D-Agent voice running on http://127.0.0.1:5050 (leave this open)")
            ThreadingHTTPServer(("127.0.0.1", 5050), Handler).serve_forever()
        """.trimIndent()

        /** Starts FreeLLMAPI in Termux the way the person runs it: ~/freellmapi, wake lock, npm run dev. */
        val FREELLMAPI_SCRIPT = """
            mkdir -p ~/.dagent
            {
              cd ~/freellmapi || { echo "No ~/freellmapi folder. Clone FreeLLMAPI there first."; exit 1; }
              command -v termux-wake-lock >/dev/null && termux-wake-lock
              [ -d node_modules ] || npm install
              npm run dev
            } 2>&1 | tee ~/.dagent/freellmapi.log
            echo; echo "FreeLLMAPI stopped (see above). Press Enter to close this session."; read _
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
