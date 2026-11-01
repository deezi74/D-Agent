package dev.dagent.linux

import android.content.Context
import android.system.Os
import dev.dagent.core.linux.TarExtractor
import dev.dagent.core.tools.ShellResult
import dev.dagent.core.tools.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * D-Agent's own Linux computer: an Alpine Linux root filesystem in the app's private storage,
 * run through PRoot (shipped as libproot.so). No root and no Termux needed. The app's workspace
 * is mounted at /workspace and the skills at /skills, and a `phone` command talks to the app.
 */
class LinuxComputer(
    private val context: Context,
    private val workspace: File,
    private val skillsDir: File,
    private val bridge: PhoneBridge,
    private val http: OkHttpClient,
) : ShellRunner {
    sealed interface State {
        data object NotInstalled : State
        data class Installing(val step: String) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val base = File(context.filesDir, "linux")
    val rootfs = File(base, "rootfs")
    private val tmp = File(base, "tmp")
    private val marker = File(base, ".installed")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val proot = File(nativeDir, "libproot.so")
    private val jobs = ConcurrentHashMap<String, Job>()
    @Volatile private var noSeccomp = false

    private class Job(val process: Process, val log: File, val command: String, val started: Long)

    private val _state = MutableStateFlow<State>(if (marker.isFile) State.Ready else State.NotInstalled)
    val state: StateFlow<State> = _state.asStateFlow()

    val engineBundled: Boolean get() = proot.isFile
    val installed: Boolean get() = marker.isFile && rootfs.isDirectory

    override fun unavailableReason(): String? = when {
        !engineBundled -> "This build has no Linux engine for this phone (it needs a 64-bit ARM phone)."
        _state.value is State.Installing -> "The Linux computer is still installing. Try again in a moment."
        !installed -> "The Linux computer is not installed yet. Ask the person to open Settings > Linux computer and tap Install (about 30 seconds)."
        else -> null
    }

    /** Downloads Alpine Linux (a few MB) and installs bash, curl, git, python and node. */
    suspend fun install(): String = withContext(Dispatchers.IO) {
        if (!engineBundled) throw IOException("This build has no Linux engine for this phone (arm64 only).")
        try {
            _state.value = State.Installing("Finding the latest Alpine Linux")
            val index = get("$ALPINE/latest-releases.yaml").decodeToString()
            val file = Regex("file:\\s*(alpine-minirootfs-[0-9.]+-aarch64\\.tar\\.gz)").find(index)?.groupValues?.get(1)
                ?: throw IOException("Couldn't find the Alpine download")
            _state.value = State.Installing("Downloading $file")
            val archive = File(context.cacheDir, file)
            download("$ALPINE/$file", archive)
            _state.value = State.Installing("Unpacking")
            if (base.exists()) base.deleteRecursively()
            rootfs.mkdirs()
            val result = archive.inputStream().use { input ->
                TarExtractor(
                    symlink = { target, link -> Os.symlink(target, link.absolutePath) },
                    chmod = { f, mode -> runCatching { Os.chmod(f.absolutePath, mode and 0b111_111_111) } },
                ).extractGz(input, rootfs)
            }
            archive.delete()
            tmp.mkdirs()
            configure()
            marker.writeText("${result.files} files")
            _state.value = State.Installing("Installing bash, curl, git, Python and Node.js")
            val setup = run("apk update && apk add --no-progress bash curl wget git python3 py3-pip nodejs npm nano jq file unzip zip", 900) { }
            _state.value = State.Ready
            "Alpine Linux is installed (${result.files} files).\n" + setup.output.takeLast(1500)
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "install failed")
            throw e
        }
    }

    /** Writes the files that tie Linux to the app; safe to run on every start. */
    fun configure() {
        if (!rootfs.isDirectory) return
        File(rootfs, "etc/resolv.conf").apply { delete() }.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(rootfs, "workspace").mkdirs()
        File(rootfs, "skills").mkdirs()
        File(rootfs, "root").mkdirs()
        File(rootfs, "etc/profile.d").mkdirs()
        File(rootfs, "etc/profile.d/dagent.sh").writeText(
            "export PS1='d-agent:\\w# '\nexport LANG=C.UTF-8\nexport PIP_BREAK_SYSTEM_PACKAGES=1\nexport PYTHONDONTWRITEBYTECODE=1\n"
        )
        File(rootfs, "etc/dagent").mkdirs()
        File(rootfs, "etc/dagent/bridge.env").writeText("DAGENT_PORT=${bridge.port}\nDAGENT_TOKEN=${bridge.token}\n")
        val phone = File(rootfs, "usr/local/bin/phone")
        phone.parentFile?.mkdirs()
        phone.writeText(PHONE_SCRIPT)
        runCatching { Os.chmod(phone.absolutePath, 493) }
    }

    fun uninstall() {
        jobs.values.forEach { it.process.destroy() }
        jobs.clear()
        base.deleteRecursively()
        _state.value = State.NotInstalled
    }

    private fun command(script: String): ProcessBuilder {
        val pb = ProcessBuilder(
            proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${tmp.absolutePath}:/tmp",
            "-b", "${workspace.absolutePath}:/workspace",
            "-b", "${skillsDir.absolutePath}:/skills",
            "-w", "/workspace",
            "/bin/sh", "-c", ". /etc/profile >/dev/null 2>&1; $script",
        )
        val env = pb.environment()
        env.clear()
        File(nativeDir, "libproot-loader.so").takeIf { it.isFile }?.let { env["PROOT_LOADER"] = it.absolutePath }
        File(nativeDir, "libproot-loader32.so").takeIf { it.isFile }?.let { env["PROOT_LOADER_32"] = it.absolutePath }
        env["PROOT_TMP_DIR"] = tmp.absolutePath
        env["LD_LIBRARY_PATH"] = nativeDir.absolutePath
        if (noSeccomp) env["PROOT_NO_SECCOMP"] = "1"
        env["HOME"] = "/root"
        env["USER"] = "root"
        env["TERM"] = "dumb"
        env["NO_COLOR"] = "1"
        env["LANG"] = "C.UTF-8"
        env["TZ"] = java.util.TimeZone.getDefault().id
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        tmp.mkdirs()
        workspace.mkdirs()
        skillsDir.mkdirs()
        return pb.redirectErrorStream(true)
    }

    override suspend fun run(command: String, timeoutSeconds: Int, onOutput: (String) -> Unit): ShellResult = withContext(Dispatchers.IO) {
        check(installed || _state.value is State.Installing) { "Linux isn't installed yet" }
        val first = runOnce(command, timeoutSeconds, onOutput)
        // Some kernels reject PRoot's seccomp filter; the documented fix is to turn it off and retry.
        if (!noSeccomp && first.exitCode != 0 && SECCOMP_HINTS.any { first.output.contains(it) }) {
            noSeccomp = true
            return@withContext runOnce(command, timeoutSeconds, onOutput)
        }
        first
    }

    private fun runOnce(command: String, timeoutSeconds: Int, onOutput: (String) -> Unit): ShellResult {
        val process = command(command).start()
        val out = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().use { r ->
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    val chunk = String(buf, 0, n)
                    synchronized(out) {
                        out.append(chunk)
                        if (out.length > 200_000) out.delete(0, out.length - 100_000)
                    }
                    runCatching { onOutput(chunk) }
                }
            }
        }.apply { start() }
        val finished = try {
            process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            throw e
        }
        if (!finished) process.destroyForcibly()
        reader.join(2_000)
        val text = synchronized(out) { out.toString() }
        return ShellResult(text, if (finished) process.exitValue() else -1, timedOut = !finished)
    }

    override fun startJob(command: String): String {
        check(installed) { "Linux isn't installed yet" }
        val id = "job" + (jobs.size + 1) + "-" + (System.currentTimeMillis() % 10000)
        val log = File(base, "jobs/$id.log").apply { parentFile?.mkdirs() }
        val process = command(command).redirectOutput(log).start()
        jobs[id] = Job(process, log, command, System.currentTimeMillis())
        return id
    }

    override fun jobStatus(id: String): String {
        val job = jobs[id] ?: return "No job $id. Jobs: ${jobs.keys.joinToString().ifEmpty { "none" }}"
        val status = if (job.process.isAlive) "running" else "finished (exit ${job.process.exitValue()})"
        return "Job $id: $status, started ${(System.currentTimeMillis() - job.started) / 1000} s ago\n$ ${job.command}\n" +
            (if (job.log.isFile) job.log.readText().takeLast(8_000) else "")
    }

    override fun stopJob(id: String): String {
        val job = jobs.remove(id) ?: return "No job $id"
        job.process.destroy()
        return "Stopped job $id"
    }

    override fun jobs(): String = jobs.entries.joinToString("\n") { (id, j) ->
        "$id: ${if (j.process.isAlive) "running" else "done"} · ${j.command.take(80)}"
    }.ifEmpty { "No background jobs." }

    fun diskUsage(): Long = if (rootfs.isDirectory) rootfs.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0

    private fun get(url: String): ByteArray = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
        r.body?.bytes() ?: throw IOException("Empty download")
    }

    private fun download(url: String, to: File) = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
        r.body?.byteStream()?.use { input -> to.outputStream().use { input.copyTo(it) } } ?: throw IOException("Empty download")
    }

    companion object {
        private const val ALPINE = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64"
        private val SECCOMP_HINTS = listOf("Function not implemented", "Bad system call", "seccomp")

        private val PHONE_SCRIPT = """
            #!/bin/sh
            # phone: control the phone from Linux through the D-Agent app.
            #   phone help
            #   phone notify title="Backup done" text="All 3 folders copied"
            #   phone clipboard_set text="hello"
            . /etc/dagent/bridge.env
            action="${'$'}{1:-help}"
            [ ${'$'}# -gt 0 ] && shift
            url="http://127.0.0.1:${'$'}DAGENT_PORT/phone"
            if command -v curl >/dev/null 2>&1; then
              n=${'$'}#
              for a in "${'$'}@"; do set -- "${'$'}@" --data-urlencode "${'$'}a"; done
              shift ${'$'}n
              exec curl -s -X POST -H "X-Token: ${'$'}DAGENT_TOKEN" --data-urlencode "action=${'$'}action" "${'$'}@" "${'$'}url"
            fi
            body="action=${'$'}action"
            for a in "${'$'}@"; do body="${'$'}body&${'$'}(printf '%s' "${'$'}a" | sed 's/%/%25/g; s/&/%26/g; s/ /%20/g; s/+/%2B/g')"; done
            exec wget -qO- --header "X-Token: ${'$'}DAGENT_TOKEN" --post-data "${'$'}body" "${'$'}url"
        """.trimIndent() + "\n"
    }
}
