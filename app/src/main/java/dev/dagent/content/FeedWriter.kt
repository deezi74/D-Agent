package dev.dagent.content

import dev.dagent.Graph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Has the agent write a few short posts steered by the person's feed sentence, memory, goals and
 * past likes and dislikes (or a "trending now" edition with sources), then stores them.
 */
class FeedWriter(private val g: Graph) {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    suspend fun write(count: Int = 5, trending: Boolean = false): Int {
        val store = g.content
        val rated = store.rated()
        val liked = rated.filter { it.reaction > 0 }.takeLast(15).joinToString("; ") { it.title }
        val disliked = rated.filter { it.reaction < 0 }.takeLast(15).joinToString("; ") { it.title }
        val goals = store.goals.value.filterNot { it.done }.joinToString("; ") { it.title }
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, h:mm a", Locale.US))
        val prompt = buildString {
            append("Write my feed. It is ").append(now).append(".\n")
            if (trending) {
                append("Make it a \"trending now\" edition: the biggest news and what people are talking about online today in the US, ")
                append("mixed with anything trending that fits my interests (\"").append(store.steer).append("\"). Search the web first; every post needs a source url.\n")
            } else {
                append("My instruction for the feed: \"").append(store.steer).append("\"\n")
            }
            if (liked.isNotEmpty()) append("Posts I liked before: ").append(liked).append('\n')
            if (disliked.isNotEmpty()) append("Posts I disliked (avoid similar): ").append(disliked).append('\n')
            if (goals.isNotEmpty()) append("My goals: ").append(goals).append('\n')
            append("Use what you know about me. Use web_search for anything current (news, releases, prices) and keep facts accurate; include the source url. ")
            append("Then reply with ONLY a JSON array of ").append(count).append(" objects, no prose, no code fence:\n")
            append("""[{"emoji": "one emoji", "title": "max 8 words", "body": "2-3 short sentences in your voice", "url": "source url or empty"}]""")
        }
        val answer = g.runner.runOnce(prompt, maxSteps = 10)
        val posts = parse(answer)
        if (posts.isEmpty()) throw IllegalStateException("The model didn't send feed posts. Try again or pick a stronger model.")
        store.addPosts(posts)
        return posts.size
    }

    fun parse(answer: String): List<FeedPost> {
        val start = answer.indexOf('[')
        val end = answer.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = runCatching { json.parseToJsonElement(answer.substring(start, end + 1)) as? JsonArray }.getOrNull() ?: return emptyList()
        val created = System.currentTimeMillis()
        return arr.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val title = str("title")
            val body = str("body")
            if (title.isEmpty() || body.isEmpty()) return@mapIndexedNotNull null
            FeedPost(emoji = str("emoji").ifEmpty { "✨" }.take(8), title = title, body = body, url = str("url").takeIf { it.startsWith("http") }, created = created - i)
        }
    }

    companion object {
        /** A scheduled task with this prompt writes the feed instead of sending a notification. */
        const val ROUTINE_PROMPT = "[write-feed]"
    }
}
