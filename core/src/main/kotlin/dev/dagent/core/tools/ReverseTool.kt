package dev.dagent.core.tools

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.clip
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title

/**
 * Reverse engineering on the phone, inside the built-in Linux computer: radare2 for native
 * programs and firmware (ELF, EXE, .so, raw binaries) and jadx for Android apps (APK, DEX).
 * Read-only: it looks at files, it never runs them.
 */
class ReverseTool(private val shell: ShellRunner) : Tool {
    override val name = "reverse"
    override val description =
        "Reverse engineer a file in /workspace (uploads are in /workspace/uploads) for learning, CTFs and the person's own or authorized apps: " +
            "info (type, arch, protections), strings, functions, disasm (one function), decompile (pseudo-C of one function), hexdump, " +
            "apk (turn an APK/DEX back into Java with jadx, plus its permissions), search (find text in a decompiled APK). " +
            "Uses radare2 and jadx in the Linux computer; setup installs them (about 150 MB, once). It only reads files, never runs them."
    override val parameters = schema {
        title()
        string("action", "What to do.", required = true, enum = ACTIONS)
        string("path", "The file, e.g. /workspace/uploads/app.apk or uploads/crackme. Not needed for setup.")
        string("target", "A function name or address for disasm/decompile/hexdump (e.g. main, sym.check_pass, 0x401136), or the text to look for with strings/search.")
        integer("limit", "Most lines to return (default 200).")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        shell.unavailableReason()?.let { return ToolResult.error(it) }
        val action = args.requireString("action")
        if (action == "setup") return ToolResult.ok(setup(ctx))
        val path = filePath(args.string("path") ?: return ToolResult.error("Say which file (path)."))
            ?: return ToolResult.error("Use a file inside /workspace (letters, numbers, - _ . / only).")
        val target = args.string("target")?.trim().orEmpty()
        if (target.isNotEmpty() && !SAFE_TARGET.matches(target)) return ToolResult.error("That target has characters I can't pass safely; use a name, address or plain words.")
        val limit = (args.int("limit") ?: 200).coerceIn(10, 2000)
        if (action !in ACTIONS) return ToolResult.error("Unknown action $action.")
        if (needsTools(action)) {
            val have = shell.run("command -v r2 >/dev/null && command -v jadx >/dev/null && echo ok", 30) {}
            if (!have.output.contains("ok")) {
                ctx.progress("Installing radare2 and jadx (first time only)…")
                setup(ctx)
            }
        }
        val cmd = command(action, path, target, limit) ?: return ToolResult.error("For $action, give a target.")
        ctx.progress("Looking at ${path.substringAfterLast('/')}…")
        val res = shell.run("test -f '$path' || { echo 'No such file: $path'; exit 2; }; $cmd", 900) { chunk ->
            chunk.lineSequence().lastOrNull { it.isNotBlank() }?.let { ctx.progress(it.take(120)) }
        }
        val text = clip(res.output.trimEnd(), 20_000).ifEmpty { "(nothing found)" }
        return if (res.exitCode == 0) ToolResult.ok(text) else ToolResult(text + "\n[exit code ${res.exitCode}]", isError = res.exitCode == 2)
    }

    private suspend fun setup(ctx: ToolContext): String {
        ctx.progress("Installing radare2, Java and jadx…")
        val res = shell.run(SETUP, 1500) { chunk -> chunk.lineSequence().lastOrNull { it.isNotBlank() }?.let { ctx.progress(it.take(120)) } }
        return clip(res.output.trimEnd(), 4000) + if (res.exitCode == 0) "\nReady." else "\n[setup exit code ${res.exitCode}]"
    }

    companion object {
        val ACTIONS = listOf("setup", "info", "strings", "functions", "disasm", "decompile", "hexdump", "apk", "search")
        private val SAFE_PATH = Regex("[A-Za-z0-9_./+-]{1,300}")
        private val SAFE_TARGET = Regex("[A-Za-z0-9_.:@ +-]{1,120}")

        private fun needsTools(action: String) = action != "search"

        /** Keeps paths inside /workspace and safe to put in single quotes. */
        fun filePath(raw: String): String? {
            val p = raw.trim().removePrefix("./")
            val abs = if (p.startsWith("/")) p else "/workspace/$p"
            if (!SAFE_PATH.matches(abs) || ".." in abs.split('/') || !abs.startsWith("/workspace/")) return null
            return abs
        }

        /** Where jadx writes an app's Java code. */
        fun outDir(path: String) = "/workspace/re/" + path.substringAfterLast('/').replace('.', '_')

        fun command(action: String, path: String, target: String, limit: Int): String? {
            val q = "'$path'"
            val r2 = "r2 -q -e scr.color=0 -e bin.relocs.apply=true -e anal.timeout=120"
            return when (action) {
                "info" -> "file $q; echo; rabin2 -I $q 2>/dev/null | head -n $limit; echo; echo '# entry points'; rabin2 -e $q 2>/dev/null | head -n 20; echo '# imports'; rabin2 -i $q 2>/dev/null | head -n 60"
                "strings" -> if (target.isEmpty()) "rabin2 -zz $q 2>/dev/null | awk 'length(\$0)>12' | head -n $limit"
                    else "rabin2 -zz $q 2>/dev/null | grep -i -F -- '${target}' | head -n $limit"
                "functions" -> "$r2 -c 'aa;afl' $q 2>/dev/null | head -n $limit"
                "disasm" -> if (target.isEmpty()) null else "$r2 -c 'aa;s $target;af;pdf' $q 2>/dev/null | head -n $limit"
                "decompile" -> if (target.isEmpty()) null else "$r2 -c 'aa;s $target;af;pdc' $q 2>/dev/null | head -n $limit"
                "hexdump" -> "$r2 -c 's ${target.ifEmpty { "0" }};px 512' $q 2>/dev/null | head -n $limit"
                "apk" -> {
                    val out = outDir(path)
                    "rm -rf '$out' && jadx --quiet --show-bad-code -d '$out' $q >/dev/null 2>&1; " +
                        "echo \"Decompiled to $out\"; echo; echo '# permissions'; grep -o 'android:name=\"android.permission[^\"]*\"' '$out/resources/AndroidManifest.xml' 2>/dev/null | sort -u | head -n 40; " +
                        "echo; echo '# package and components'; grep -oE '(package|<activity|<service|<receiver|<provider)[^>]{0,160}' '$out/resources/AndroidManifest.xml' 2>/dev/null | head -n 40; " +
                        "echo; echo '# biggest Java packages'; find '$out/sources' -name '*.java' 2>/dev/null | sed 's|$out/sources/||; s|/[^/]*$||' | sort | uniq -c | sort -rn | head -n 25"
                }
                "search" -> if (target.isEmpty()) null else {
                    val out = outDir(path)
                    "test -d '$out' || { echo 'Run apk on this file first.'; exit 2; }; grep -rn -i -F -- '${target}' '$out' 2>/dev/null | cut -c1-220 | head -n $limit"
                }
                else -> null
            }
        }

        private const val SETUP = """set -e
apk add --no-progress radare2 openjdk17-jre-headless file curl jq unzip >/dev/null
if ! command -v jadx >/dev/null; then
  url=${'$'}(curl -fsSL https://api.github.com/repos/skylot/jadx/releases/latest | jq -r '.assets[] | select(.name | test("^jadx-[0-9.]+\\.zip${'$'}")) | .browser_download_url' | head -n 1)
  [ -n "${'$'}url" ] || url=https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip
  curl -fsSL -o /tmp/jadx.zip "${'$'}url"
  rm -rf /opt/jadx && mkdir -p /opt/jadx && unzip -q /tmp/jadx.zip -d /opt/jadx && rm /tmp/jadx.zip
  chmod +x /opt/jadx/bin/jadx && ln -sf /opt/jadx/bin/jadx /usr/local/bin/jadx
fi
r2 -v | head -n 1
jadx --version"""
    }
}
