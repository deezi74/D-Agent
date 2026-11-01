package dev.dagent.local

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import dev.dagent.core.llm.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A GGUF model the app suggests. Sizes are the Q4_K_M files on Hugging Face. */
data class CatalogModel(val id: String, val name: String, val maker: String, val url: String, val bytes: Long, val note: String) {
    val fileName get() = url.substringAfterLast('/')
}

/**
 * Offline models: downloads GGUF files and runs llama.cpp's OpenAI-compatible server
 * (shipped as libllamaserver.so) on 127.0.0.1, so the agent talks to it like any provider.
 */
class LocalModels(private val context: Context) {
    val catalog = listOf(
        CatalogModel("llama32-1b", "Llama 3.2 1B Instruct", "Meta", "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf", 807_694_464, "Smallest and fastest. Good for quick chat on any phone."),
        CatalogModel("granite33-2b", "Granite 3.3 2B Instruct", "IBM", "https://huggingface.co/bartowski/ibm-granite_granite-3.3-2b-instruct-GGUF/resolve/main/ibm-granite_granite-3.3-2b-instruct-Q4_K_M.gguf", 1_545_303_616, "Small and good at using tools."),
        CatalogModel("llama32-3b", "Llama 3.2 3B Instruct", "Meta", "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf", 2_019_377_696, "Balanced. Needs about 4 GB of free RAM."),
        CatalogModel("gemma3-4b", "Gemma 3 4B Instruct", "Google", "https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/google_gemma-3-4b-it-Q4_K_M.gguf", 2_489_758_112, "Strong writing. Needs about 5 GB of free RAM."),
        CatalogModel("phi4-mini", "Phi-4 mini Instruct", "Microsoft", "https://huggingface.co/bartowski/microsoft_Phi-4-mini-instruct-GGUF/resolve/main/microsoft_Phi-4-mini-instruct-Q4_K_M.gguf", 2_491_874_688, "Good at reasoning and math. Needs about 5 GB of free RAM."),
    )

    val dir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }
    private val server = File(context.applicationInfo.nativeLibraryDir, "libllamaserver.so")
    val engineBundled: Boolean get() = server.isFile

    sealed interface Engine {
        data object Stopped : Engine
        data class Starting(val model: String) : Engine
        data class Running(val model: String) : Engine
        data class Failed(val message: String) : Engine
    }

    private val _engine = MutableStateFlow<Engine>(Engine.Stopped)
    val engine: StateFlow<Engine> = _engine.asStateFlow()

    /** Download progress by file name, 0..1, or -1 when the download failed. */
    private val _downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloads: StateFlow<Map<String, Float>> = _downloads.asStateFlow()

    private var process: Process? = null
    private var runningFile: String = ""
    private val log = File(context.cacheDir, "llama-server.log")
    private val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()

    fun installed(): List<File> = dir.listFiles { f -> f.name.endsWith(".gguf") }?.sortedBy { it.name } ?: emptyList()

    fun totalRamGb(): Double {
        val mi = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.totalMem / 1e9
    }

    fun download(model: CatalogModel): Long {
        val dm = context.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("D-Agent: ${model.name}")
            .setDescription("On-phone AI model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "models", model.fileName + ".part")
            .setAllowedOverMetered(true)
        val id = dm.enqueue(req)
        _downloads.value = _downloads.value + (model.fileName to 0f)
        return id
    }

    /** Polls the system download manager; call from a coroutine while the screen is open. */
    suspend fun watchDownloads() = withContext(Dispatchers.IO) {
        val dm = context.getSystemService(DownloadManager::class.java)
        while (true) {
            val map = mutableMapOf<String, Float>()
            dm.query(DownloadManager.Query()).use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty()
                    if (!title.startsWith("D-Agent: ")) continue
                    val uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: continue
                    val name = Uri.parse(uri).lastPathSegment?.removeSuffix(".part") ?: continue
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val part = File(dir, "$name.part")
                            if (part.isFile) part.renameTo(File(dir, name))
                            dm.remove(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))).let { }
                        }
                        DownloadManager.STATUS_FAILED -> map[name] = -1f
                        else -> map[name] = if (total > 0) done.toFloat() / total else 0f
                    }
                }
            }
            _downloads.value = map
            delay(1000)
        }
    }

    fun delete(file: File) {
        if (runningFile == file.name) stop()
        file.delete()
    }

    /** Copies a .gguf the person picked into the models folder. */
    suspend fun import(uri: Uri, name: String): File = withContext(Dispatchers.IO) {
        val target = File(dir, name.takeIf { it.endsWith(".gguf") } ?: "$name.gguf")
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it, 1 shl 20) } }
            ?: throw IOException("Can't read that file")
        target
    }

    /** Starts the server with [file] unless it already runs it. Returns when it answers. */
    suspend fun ensureRunning(file: File, contextSize: Int, threads: Int) = withContext(Dispatchers.IO) {
        if (!engineBundled) throw IOException("This build has no on-phone engine (arm64 phones only).")
        if (!file.isFile) throw IOException("Model file is missing: ${file.name}. Pick one in Settings > On-phone models.")
        if (process?.isAlive == true && runningFile == file.name && healthy()) return@withContext
        stop()
        _engine.value = Engine.Starting(file.name)
        val cores = Runtime.getRuntime().availableProcessors()
        val t = if (threads > 0) threads else (cores - 2).coerceIn(2, 6)
        val pb = ProcessBuilder(
            server.absolutePath,
            "-m", file.absolutePath,
            "--host", "127.0.0.1",
            "--port", Providers.LOCAL_PORT.toString(),
            "-c", contextSize.toString(),
            "-t", t.toString(),
            "--jinja",
            "--no-webui",
            "-np", "1",
        ).redirectErrorStream(true).redirectOutput(log)
        pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        process = pb.start()
        runningFile = file.name
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) {
                val tail = runCatching { log.readText().takeLast(600) }.getOrDefault("")
                // Older builds of the server do not know --no-webui; retry without it once.
                if (tail.contains("no-webui")) {
                    process = ProcessBuilder(pb.command().filterNot { it == "--no-webui" }).redirectErrorStream(true).redirectOutput(log)
                        .also { it.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir }.start()
                    continue
                }
                _engine.value = Engine.Failed(tail.ifBlank { "The model server stopped." })
                throw IOException("The on-phone model could not start: ${tail.takeLast(300)}")
            }
            if (healthy()) {
                _engine.value = Engine.Running(file.name)
                return@withContext
            }
            delay(700)
        }
        stop()
        _engine.value = Engine.Failed("Timed out loading the model.")
        throw IOException("The on-phone model took too long to load. Try a smaller model.")
    }

    private fun healthy(): Boolean = runCatching {
        http.newCall(Request.Builder().url("http://127.0.0.1:${Providers.LOCAL_PORT}/health").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    fun stop() {
        process?.destroy()
        process = null
        runningFile = ""
        _engine.value = Engine.Stopped
    }

    fun logTail(): String = runCatching { log.readText().takeLast(4000) }.getOrDefault("")
}
