package dev.dagent.connectors

import dev.dagent.Graph
import dev.dagent.core.llm.ApiKind
import dev.dagent.core.tools.PcClient
import kotlinx.coroutines.delay

/**
 * D-Agent PC running on the phone itself, in Termux. The same worker that runs on a Windows or
 * Linux computer, listening only on 127.0.0.1, so the phone is its own always-on computer and an
 * external PC stays optional.
 */
object LocalPc {
    const val URL = "http://127.0.0.1:8787"
    private const val PORT = 8787
    private const val INSTALL = "curl -fsSL https://raw.githubusercontent.com/deezi74/D-Agent/claude/project-thread-7jgi6v/pc-worker/install.sh | bash"
    private const val TOKEN = "python ~/.dagent-pc/dagent_pc.py --token"
    private const val START = "bash ~/.dagent-pc/start.sh"

    fun isLocal(url: String) = url.isNotBlank() && PcClient.isOnThisPhone(url)

    /** Installs (or updates) D-Agent PC in Termux, starts it and pairs the app with it. */
    suspend fun install(g: Graph, progress: (String) -> Unit): String {
        val t = g.termux
        if (!t.isInstalled()) return "Install Termux from F-Droid first (the Play Store version is too old)."
        if (t.isOutdated()) return "Your Termux is the old Play Store version, which ignores other apps. Install Termux from F-Droid."
        if (!t.hasPermission()) return "Turn on the Termux connector below first and allow D-Agent to run commands in Termux."
        progress("Installing in Termux. The first time takes a minute or two...")
        val r = t.run(INSTALL, timeoutMs = 600_000)
        r.error?.let { return "Termux couldn't install it: $it" }
        if (r.exitCode != null && r.exitCode != 0) return "The install stopped (exit ${r.exitCode}): ${(r.stderr.ifBlank { r.stdout }).takeLast(400)}"
        val code = t.run(TOKEN, timeoutMs = 30_000).stdout.lines().map { it.trim() }.lastOrNull { Regex("[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}").matches(it) }
            ?: return "Installed, but couldn't read the pairing code. In Termux run: $TOKEN"
        g.connectors.pcUrl = URL
        g.connectors.pcCode = code
        progress("Starting it...")
        if (!ensureRunning(g)) return "Installed and paired, but it didn't start. In Termux run: $START"
        return useAppAi(g).let { "This phone is now ${g.settings.current.persona.name}'s always-on computer. $it" }
    }

    /** Starts the worker in Termux if nothing answers on port 8787. True once it answers. */
    suspend fun ensureRunning(g: Graph): Boolean {
        val t = g.termux
        if (t.portOpen(PORT)) return true
        if (!t.isInstalled() || !t.hasPermission()) return false
        t.run(START, timeoutMs = 20_000)
        repeat(16) {
            delay(500)
            if (t.portOpen(PORT)) return true
        }
        return false
    }

    /** Gives the worker the same AI model the app uses, so tasks work with no extra setup. */
    suspend fun useAppAi(g: Graph): String {
        val p = g.settings.provider()
        if (p.kind != ApiKind.OPENAI) return "Its tasks need an OpenAI-style AI (FreeLLMAPI, LM Studio, OpenRouter, Groq...). Pick one under AI model, then tap Use the app's AI."
        if (!p.ready) return "Set up an AI model in the app first, then tap Use the app's AI."
        val remote = !isLocal(g.connectors.pcUrl)
        val base = p.baseUrl.trim()
        if (remote && Regex("//(localhost|127\\.0\\.0\\.1)[:/]").containsMatchIn(base)) {
            return "The app's AI runs on this phone (${base}), which the other computer can't reach. Pick one of the models listed above instead."
        }
        return runCatching {
            PcWatch.client(g).setModel(base, p.model, p.apiKey)
            "Tasks use ${p.preset.name} (${p.model})."
        }.getOrElse { it.message ?: "Couldn't change its AI." }
    }
}
