package dev.dagent.pals

import dev.dagent.core.pals.HubIndex
import dev.dagent.core.pals.PalFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * D's Hub: the shared list of Pals. For now it's a public file in the D-Agent GitHub repo
 * (the same file the website shows), so it needs no account. Accounts and uploads come later.
 */
class DsHub(private val http: OkHttpClient, private val cache: File) {
    suspend fun load(force: Boolean = false): HubIndex = withContext(Dispatchers.IO) {
        if (!force && cache.isFile && System.currentTimeMillis() - cache.lastModified() < 30 * 60_000) {
            runCatching { return@withContext PalFormat.decodeHub(cache.readText()) }
        }
        val errors = mutableListOf<String>()
        for (url in SOURCES) {
            try {
                http.newCall(Request.Builder().url(url).header("User-Agent", "D-Agent").build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val text = r.body!!.string()
                    val hub = PalFormat.decodeHub(text)
                    cache.writeText(text)
                    return@withContext hub
                }
            } catch (e: Exception) {
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        if (cache.isFile) runCatching { return@withContext PalFormat.decodeHub(cache.readText()) }
        throw IOException("Couldn't reach D's Hub (${errors.firstOrNull() ?: "offline"}).")
    }

    companion object {
        const val SITE = "https://deezi74.github.io/D-Agent/"
        private val SOURCES = listOf(
            "https://deezi74.github.io/D-Agent/hub/pals.json",
            "https://raw.githubusercontent.com/deezi74/D-Agent/claude/project-thread-7jgi6v/docs/hub/pals.json",
        )
    }
}

/** Where a Pal's banner picture lives (its own link, or D's Hub's covers folder). */
fun coverUrl(pal: dev.dagent.core.pals.Pal): String = when {
    pal.cover.startsWith("https://") -> pal.cover
    pal.cover.isNotBlank() -> DsHub.SITE + "hub/" + pal.cover
    else -> DsHub.SITE + "hub/covers/" + pal.id + ".webp"
}
