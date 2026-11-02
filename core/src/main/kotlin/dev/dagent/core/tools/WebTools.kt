package dev.dagent.core.tools

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.clip
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLDecoder

data class SearchHit(val title: String, val url: String, val snippet: String, val source: String = "", val age: String = "")

/**
 * Live web search. Free with no key: DuckDuckGo first, Bing as a fallback, or your own SearXNG.
 * Optional keyed services (Tavily, Brave) go first when a key is set, with the free ones as backup.
 */
class WebSearch(
    private val http: OkHttpClient,
    private val searxUrl: () -> String = { "" },
    /** (provider id, key): "tavily" or "brave", or ("", "") for the free default. */
    private val keyed: () -> Pair<String, String> = { "" to "" },
) {

    suspend fun search(query: String, max: Int = 8): List<SearchHit> = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val (provider, key) = keyed()
        if (key.isNotBlank()) when (provider) {
            TAVILY -> runCatching { tavily(query, key, max) }
            BRAVE -> runCatching { brave(query, key, max) }
            else -> null
        }?.onSuccess { if (it.isNotEmpty()) return@withContext it.take(max) }?.onFailure { errors += "$provider: ${it.message}" }
        val searx = searxUrl().trim()
        if (searx.isNotEmpty()) runCatching { searxng(searx, query) }.onSuccess { if (it.isNotEmpty()) return@withContext it.take(max) }
            .onFailure { errors += "SearXNG: ${it.message}" }
        runCatching { duck(query) }.onSuccess { if (it.isNotEmpty()) return@withContext it.take(max) }
            .onFailure { errors += "DuckDuckGo: ${it.message}" }
        runCatching { bing(query) }.onSuccess { if (it.isNotEmpty()) return@withContext it.take(max) }
            .onFailure { errors += "Bing: ${it.message}" }
        if (errors.isNotEmpty()) throw IOException(errors.joinToString("; "))
        emptyList()
    }

    /** Recent headlines for a topic, newest first. */
    suspend fun news(query: String, max: Int = 10): List<SearchHit> = withContext(Dispatchers.IO) {
        val url = "https://www.bing.com/news/search?q=${enc(query)}&qft=sortbydate%3d%221%22&form=YFNR"
        val doc = get(url)
        val hits = doc.select("div.news-card, div.newsitem").mapNotNull { card ->
            val a = card.selectFirst("a.title") ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.startsWith("http") } ?: card.attr("url")
            SearchHit(
                title = a.text(),
                url = href,
                snippet = card.selectFirst("div.snippet")?.text().orEmpty(),
                source = card.attr("data-author").ifEmpty { card.selectFirst("div.source a, div.source")?.text().orEmpty() },
                age = card.selectFirst("span[aria-label], div.source span")?.attr("aria-label").orEmpty(),
            )
        }.filter { it.url.isNotEmpty() && it.title.isNotEmpty() }
        if (hits.isNotEmpty()) hits.distinctBy { it.url }.take(max) else search("$query news", max)
    }

    private fun duck(query: String): List<SearchHit> {
        val req = Request.Builder().url("https://html.duckduckgo.com/html/")
            .header("User-Agent", UA).header("Accept-Language", "en-US,en;q=0.9")
            .post(FormBody.Builder().add("q", query).add("kl", "us-en").build()).build()
        val html = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val doc = Jsoup.parse(html, "https://duckduckgo.com/")
        return doc.select("div.result").mapNotNull { el ->
            if (el.hasClass("result--ad")) return@mapNotNull null
            val a = el.selectFirst("a.result__a") ?: return@mapNotNull null
            SearchHit(a.text(), cleanDuckUrl(a.attr("href")), el.selectFirst(".result__snippet")?.text().orEmpty(),
                el.selectFirst(".result__url")?.text()?.trim().orEmpty())
        }.filter { it.url.startsWith("http") }
    }

    private fun bing(query: String): List<SearchHit> {
        val doc = get("https://www.bing.com/search?q=${enc(query)}&setlang=en-US&cc=US")
        return doc.select("li.b_algo").mapNotNull { li ->
            val a = li.selectFirst("h2 a") ?: return@mapNotNull null
            SearchHit(a.text(), a.attr("href"), li.selectFirst(".b_caption p, p")?.text().orEmpty())
        }.filter { it.url.startsWith("http") }
    }

    internal fun tavily(query: String, key: String, max: Int, base: String = "https://api.tavily.com"): List<SearchHit> {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("query", JsonPrimitive(query)); put("max_results", JsonPrimitive(max.coerceIn(1, 20))); put("search_depth", JsonPrimitive("basic"))
        }.toString()
        val req = Request.Builder().url("$base/search").header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException(if (r.code == 401 || r.code == 403) "the key was refused" else "HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val results = Json.parseToJsonElement(text).jsonObject["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            SearchHit(str("title"), str("url"), str("content").take(400), "tavily")
        }.filter { it.url.startsWith("http") }
    }

    internal fun brave(query: String, key: String, max: Int, base: String = "https://api.search.brave.com"): List<SearchHit> {
        val req = Request.Builder().url("$base/res/v1/web/search?q=${enc(query)}&count=${max.coerceIn(1, 20)}&country=us&search_lang=en")
            .header("X-Subscription-Token", key).header("Accept", "application/json").build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException(if (r.code == 401 || r.code == 403 || r.code == 422) "the key was refused" else "HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val web = Json.parseToJsonElement(text).jsonObject["web"] as? JsonObject ?: return emptyList()
        val results = web["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            SearchHit(Jsoup.parse(str("title")).text(), str("url"), Jsoup.parse(str("description")).text(), "brave", str("age"))
        }.filter { it.url.startsWith("http") }
    }

    private fun searxng(base: String, query: String): List<SearchHit> {
        val url = "${base.trimEnd('/')}/search?q=${enc(query)}&format=json&language=en-US"
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val results = Json.parseToJsonElement(body).jsonObject["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            SearchHit(s("title"), s("url"), s("content"), s("engine"))
        }
    }

    private fun get(url: String): Document {
        val html = http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en-US,en;q=0.9").build())
            .execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        return Jsoup.parse(html, url)
    }

    companion object {
        const val TAVILY = "tavily"
        const val BRAVE = "brave"
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"
        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        fun cleanDuckUrl(href: String): String {
            val full = if (href.startsWith("//")) "https:$href" else href
            val u = full.toHttpUrlOrNull() ?: return full
            return u.queryParameter("uddg")?.let { URLDecoder.decode(it, "UTF-8") } ?: full
        }
    }
}

class WebSearchTool(private val search: WebSearch) : Tool {
    override val name = "web_search"
    override val description = "Search the live web. Use it for anything current: news, prices, scores, weather, releases, people, places. " +
        "Set news=true for the latest headlines on a topic. Then open the best pages with web_fetch before answering, and cite sources as markdown links."
    override val parameters = schema {
        title()
        string("query", "What to search for. Be specific; add the year for recent events.", required = true)
        boolean("news", "Search recent news instead of the whole web.")
        integer("count", "How many results (default 8).")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val q = args.requireString("query")
        val n = (args.int("count") ?: 8).coerceIn(1, 15)
        val hits = if (args.bool("news") == true) search.news(q, n) else search.search(q, n)
        if (hits.isEmpty()) return ToolResult.ok("No results for \"$q\". Try other words.")
        return ToolResult.ok(hits.mapIndexed { i, h ->
            buildString {
                append(i + 1).append(". ").append(h.title).append('\n').append("   ").append(h.url)
                if (h.source.isNotBlank() || h.age.isNotBlank()) append("\n   ").append(listOf(h.source, h.age).filter { it.isNotBlank() }.joinToString(" · "))
                if (h.snippet.isNotBlank()) append("\n   ").append(h.snippet)
            }
        }.joinToString("\n"))
    }
}

/** Downloads a page and boils it down to readable text and its main links. */
class WebFetchTool(private val http: OkHttpClient) : Tool {
    override val name = "web_fetch"
    override val description = "Open a web page or API URL and read it as clean text (articles, docs, JSON, plain text). Faster than the browser; use the browser tool for pages that need JavaScript, clicks or logins."
    override val parameters = schema {
        title()
        string("url", "The full http(s) URL.", required = true)
        boolean("links", "Also list the links on the page.")
        integer("max_chars", "How much text to return (default 20000).")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val url = args.requireString("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext ToolResult.error("Only http(s) URLs can be fetched.")
        val max = (args.int("max_chars") ?: 20_000).coerceIn(1_000, 60_000)
        http.newCall(Request.Builder().url(url).header("User-Agent", WebSearch.UA).header("Accept-Language", "en-US,en;q=0.9").build()).execute().use { r ->
            val type = r.header("Content-Type").orEmpty()
            val body = r.body ?: return@withContext ToolResult.error("Empty response (HTTP ${r.code})")
            if (!r.isSuccessful) return@withContext ToolResult.error("HTTP ${r.code} for $url")
            if (type.contains("image") || type.contains("pdf") || type.contains("octet-stream") || type.contains("video") || type.contains("audio")) {
                return@withContext ToolResult.ok("$url is $type (${r.header("Content-Length") ?: "unknown"} bytes). Download it with the shell (curl -LO) to work with it.")
            }
            val raw = body.string()
            if (!type.contains("html") && !raw.trimStart().startsWith("<")) return@withContext ToolResult.ok(clip(raw, max))
            ToolResult.ok(clip(readable(raw, r.request.url.toString(), args.bool("links") == true), max))
        }
    }

    companion object {
        fun readable(html: String, url: String, withLinks: Boolean): String {
            val doc = Jsoup.parse(html, url)
            val title = doc.title()
            doc.select("script, style, noscript, svg, iframe, form, nav, footer, header, aside, [aria-hidden=true], .ad, .ads, .cookie, .banner").remove()
            val main = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.selectFirst("[role=main]") ?: doc.body() ?: doc
            val sb = StringBuilder()
            if (title.isNotBlank()) sb.append("# ").append(title).append("\n").append(url).append("\n\n")
            val blocks = main.select("h1, h2, h3, h4, p, li, pre, blockquote, td, th, dt, dd")
            val containers = setOf("li", "p", "blockquote", "td", "th", "dd", "pre")
            for (el in blocks) {
                // A block inside another selected block was already printed with its parent.
                if (el.parents().any { it.tagName() in containers }) continue
                val t = el.text().trim()
                if (t.isEmpty()) continue
                when (el.tagName()) {
                    "h1" -> sb.append("\n# ").append(t).append('\n')
                    "h2" -> sb.append("\n## ").append(t).append('\n')
                    "h3", "h4" -> sb.append("\n### ").append(t).append('\n')
                    "li" -> sb.append("- ").append(t).append('\n')
                    "pre" -> sb.append("```\n").append(el.wholeText()).append("\n```\n")
                    else -> sb.append(t).append('\n')
                }
            }
            if (sb.length < 200) sb.append(main.text())
            if (withLinks) {
                sb.append("\n\nLinks:\n")
                main.select("a[href]").map { it.text().trim() to it.absUrl("href") }
                    .filter { it.first.isNotEmpty() && it.second.startsWith("http") }
                    .distinctBy { it.second }.take(60)
                    .forEach { (t, h) -> sb.append("- [").append(t.take(80)).append("](").append(h).append(")\n") }
            }
            return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
        }
    }
}
