package dev.dagent.content

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class FeedPost(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String,
    val title: String,
    val body: String,
    val url: String? = null,
    val created: Long = System.currentTimeMillis(),
    val reaction: Int = 0,
)

@Serializable
data class Goal(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val note: String = "",
    val category: String = "Personal",
    val done: Boolean = false,
    val created: Long = System.currentTimeMillis(),
)

/** The feed posts, goals and feed instruction, kept as small JSON files. */
class ContentStore(root: File) {
    private val dir = File(root, "content").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val feedFile = File(dir, "feed.json")
    private val goalsFile = File(dir, "goals.json")
    private val steerFile = File(dir, "steer.txt")

    private val _feed = MutableStateFlow(read(feedFile, FeedPost.serializer()))
    val feed: StateFlow<List<FeedPost>> = _feed.asStateFlow()
    private val _goals = MutableStateFlow(read(goalsFile, Goal.serializer()))
    val goals: StateFlow<List<Goal>> = _goals.asStateFlow()

    var steer: String
        get() = runCatching { steerFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_STEER
        set(v) = steerFile.writeText(v.trim())
    val steerSet: Boolean get() = steerFile.isFile

    private fun <T> read(f: File, s: kotlinx.serialization.KSerializer<T>): List<T> =
        runCatching { json.decodeFromString(ListSerializer(s), f.readText()) }.getOrDefault(emptyList())

    @Synchronized private fun saveFeed(list: List<FeedPost>) {
        _feed.value = list.sortedByDescending { it.created }.take(120)
        feedFile.writeText(json.encodeToString(ListSerializer(FeedPost.serializer()), _feed.value))
    }

    @Synchronized private fun saveGoals(list: List<Goal>) {
        _goals.value = list
        goalsFile.writeText(json.encodeToString(ListSerializer(Goal.serializer()), list))
    }

    fun addPosts(posts: List<FeedPost>) = saveFeed(posts + _feed.value)
    fun react(id: String, r: Int) = saveFeed(_feed.value.map { if (it.id == id) it.copy(reaction = if (it.reaction == r) 0 else r) else it })
    fun deletePost(id: String) = saveFeed(_feed.value.filterNot { it.id == id })
    fun clearFeed() = saveFeed(emptyList())
    fun rated(): List<FeedPost> = _feed.value.filter { it.reaction != 0 }

    fun addGoal(g: Goal) = saveGoals(_goals.value + g)
    fun toggleGoal(id: String) = saveGoals(_goals.value.map { if (it.id == id) it.copy(done = !it.done) else it })
    fun deleteGoal(id: String) = saveGoals(_goals.value.filterNot { it.id == id })

    companion object {
        const val DEFAULT_STEER = "Tech and AI news that matters, smart money and business moves, and a fun fact. Keep it short and punchy."
    }
}
