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

/** A GGUF model the app suggests. Sizes are the exact file sizes on Hugging Face (checked 2026-11-02). */
data class CatalogModel(
    val id: String,
    val name: String,
    val maker: String,
    val repo: String,
    val file: String,
    val bytes: Long,
    val minRamGb: Int,
    val tag: String,
    val note: String,
) {
    val fileName get() = file
    val url get() = "https://huggingface.co/$repo/resolve/main/$file"
}

/** A repository or file found on Hugging Face. */
data class HfRepo(val id: String, val downloads: Long, val likes: Long)
data class HfFile(val repo: String, val path: String, val bytes: Long) {
    val fileName get() = path.substringAfterLast('/')
    val url get() = "https://huggingface.co/$repo/resolve/main/$path"
}

/**
 * Offline models: downloads GGUF files and runs llama.cpp's OpenAI-compatible server
 * (shipped as libllamaserver.so) on 127.0.0.1, so the agent talks to it like any provider.
 */
class LocalModels(private val context: Context) {
    val catalog = listOf(
        CatalogModel("lfm25-1.2b", "LFM2.5 1.2B Instruct", "Liquid AI", "LiquidAI/LFM2.5-1.2B-Instruct-GGUF", "LFM2.5-1.2B-Instruct-Q4_K_M.gguf", 730_895_168, 3, "Fastest",
            "Built for phones: a hybrid design that replies very fast on the phone's CPU and handles simple tools (flashlight, timers, quick lookups)."),
        CatalogModel("qwen35-0.8b", "Qwen3.5 0.8B", "Qwen", "bartowski/Qwen_Qwen3.5-0.8B-GGUF", "Qwen_Qwen3.5-0.8B-Q4_K_M.gguf", 579_615_840, 3, "Tiny",
            "The smallest of the 2026 Qwen family. Runs on any phone next to other apps."),
        CatalogModel("qwen35-2b", "Qwen3.5 2B", "Qwen", "bartowski/Qwen_Qwen3.5-2B-GGUF", "Qwen_Qwen3.5-2B-Q4_K_M.gguf", 1_396_198_496, 4, "Best balance",
            "The best pick for most phones. Newer and smarter than Qwen3 1.7B, reliable with tools, web search and phone controls."),
        CatalogModel("lfm25-2.6b", "LFM2.5 2.6B", "Liquid AI", "LiquidAI/LFM2.5-2.6B-GGUF", "LFM2.5-2.6B-Q4_K_M.gguf", 1_674_455_040, 4, "Quick agent",
            "Fast like the 1.2B but noticeably smarter. Answers without a thinking pause, so replies start sooner."),
        CatalogModel("gemma4-e2b", "Gemma 4 E2B", "Google", "bartowski/google_gemma-4-E2B-it-GGUF", "google_gemma-4-E2B-it-Q4_K_M.gguf", 3_462_680_032, 6, "Natural writer",
            "Google's 2026 phone model with built-in tool calling. Great general knowledge and natural writing."),
        CatalogModel("qwen35-4b", "Qwen3.5 4B", "Qwen", "bartowski/Qwen_Qwen3.5-4B-GGUF", "Qwen_Qwen3.5-4B-Q4_K_M.gguf", 3_013_027_808, 8, "Smartest for 8 GB",
            "Much stronger research and planning. Best on phones with 8 GB of RAM or more."),
        CatalogModel("nemotron-bolt-e4b", "Nemotron 3.5 Bolt E4B", "NVIDIA", "unsloth/NVIDIA-Nemotron-3.5-Bolt-E4B-GGUF", "NVIDIA-Nemotron-3.5-Bolt-E4B-UD-Q4_K_XL.gguf", 5_060_482_240, 8, "Best tool user",
            "NVIDIA's October 2026 assistant model, tuned from Gemma 4 for fast, multi-step tool use. Needs 8 GB of RAM or more."),
        CatalogModel("gemma4-e4b", "Gemma 4 E4B", "Google", "bartowski/google_gemma-4-E4B-it-GGUF", "google_gemma-4-E4B-it-Q4_K_M.gguf", 5_405_170_144, 8, "Best writing",
            "The bigger Gemma 4 phone model: the most natural chat and writing here. Tools work well; Nemotron Bolt is better at them."),
        CatalogModel("qwen35-9b", "Qwen3.5 9B", "Qwen", "bartowski/Qwen_Qwen3.5-9B-GGUF", "Qwen_Qwen3.5-9B-Q4_K_M.gguf", 6_169_341_984, 12, "Flagship only",
            "Closest to a cloud model. Needs 12 GB of RAM or more and patience; expect a few words per second."),
        CatalogModel("qwen3-1.7b", "Qwen3 1.7B (2025)", "Qwen", "bartowski/Qwen_Qwen3-1.7B-GGUF", "Qwen_Qwen3-1.7B-Q4_K_M.gguf", 1_282_439_584, 4, "Older",
            "Last year's balance pick. Qwen3.5 2B replaces it."),
        CatalogModel("qwen3-4b", "Qwen3 4B (2025)", "Qwen", "bartowski/Qwen_Qwen3-4B-GGUF", "Qwen_Qwen3-4B-Q4_K_M.gguf", 2_497_280_960, 8, "Older",
            "Last year's 8 GB pick. Qwen3.5 4B replaces it."),
    )

    /** The model D-Agent suggests for this phone's memory. */
    fun best(): CatalogModel {
        val gb = totalRamGb()
        val id = when {
            gb >= 7.5 -> "qwen35-4b"
            gb >= 3.6 -> "qwen35-2b"
            else -> "lfm25-1.2b"
        }
        return catalog.first { it.id == id }
    }

    fun fits(m: CatalogModel): Boolean = totalRamGb() >= m.minRamGb - 0.5

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

    fun download(model: CatalogModel): Long = downloadFile(model.fileName, model.url, model.name)

    fun download(file: HfFile): Long = downloadFile(file.fileName, file.url, file.fileName.removeSuffix(".gguf"))

    private fun downloadFile(fileName: String, url: String, label: String): Long {
        val dm = context.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("D-Agent: $label")
            .setDescription("On-phone AI model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "models", "$fileName.part")
            .setAllowedOverMetered(true)
        val id = dm.enqueue(req)
        _downloads.value = _downloads.value + (fileName to 0f)
        return id
    }

    // ---- Hugging Face ---------------------------------------------------------------------------

    private val hub = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun getJson(url: String): kotlinx.serialization.json.JsonElement {
        hub.newCall(Request.Builder().url(url).header("User-Agent", "D-Agent").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Hugging Face said HTTP ${r.code}")
            return kotlinx.serialization.json.Json.parseToJsonElement(r.body!!.string())
        }
    }

    /** GGUF repositories matching [query], most downloaded first. */
    suspend fun searchHub(query: String): List<HfRepo> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        getJson("https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=25").let { el ->
            (el as kotlinx.serialization.json.JsonArray).mapNotNull { item ->
                val o = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun long(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                val id = (o["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: (o["modelId"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                HfRepo(id, long("downloads"), long("likes"))
            }
        }
    }

    /** The .gguf files in a repository ("owner/name" or a huggingface.co link), smallest first. */
    suspend fun hubFiles(repoOrUrl: String): List<HfFile> = withContext(Dispatchers.IO) {
        val repo = repoOrUrl.trim().removePrefix("https://").removePrefix("http://").removePrefix("huggingface.co/")
            .split('/').filter { it.isNotBlank() }.take(2).joinToString("/")
        if (!repo.contains('/')) throw IOException("Use owner/name, like bartowski/Qwen_Qwen3-1.7B-GGUF")
        val el = getJson("https://huggingface.co/api/models/$repo/tree/main?recursive=true")
        (el as kotlinx.serialization.json.JsonArray).mapNotNull { item ->
            val o = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val path = (o["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            if (!path.endsWith(".gguf", true) || path.contains("mmproj", true)) return@mapNotNull null
            val lfs = o["lfs"] as? kotlinx.serialization.json.JsonObject
            val size = ((lfs?.get("size") ?: o["size"]) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            HfFile(repo, path, size)
        }.sortedBy { it.bytes }
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

    /** True while the on-phone model server process is running. */
    fun alive(): Boolean = process?.isAlive == true

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
