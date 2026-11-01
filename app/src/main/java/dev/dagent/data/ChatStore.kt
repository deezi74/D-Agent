package dev.dagent.data

import dev.dagent.core.llm.Msg
import dev.dagent.core.llm.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** How a tool call looked in the chat, kept beside the transcript. */
@Serializable
data class ToolInfo(
    val title: String,
    val millis: Long = 0,
    val error: Boolean = false,
    val files: List<String> = emptyList(),
)

@Serializable
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val messages: List<Msg> = emptyList(),
    val tools: Map<String, ToolInfo> = emptyMap(),
    val pinned: Boolean = false,
)

@Serializable
data class SessionSummary(val id: String, val title: String, val updated: Long, val preview: String, val pinned: Boolean = false)

/** Chats live as one JSON file each, plus an index for the drawer. */
class ChatStore(root: File) {
    private val dir = File(root, "chats").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _index = MutableStateFlow(loadIndex())
    val index: StateFlow<List<SessionSummary>> = _index.asStateFlow()

    private fun loadIndex(): List<SessionSummary> = runCatching {
        json.decodeFromString<List<SessionSummary>>(indexFile.readText())
    }.getOrElse { rebuildIndex() }

    private fun rebuildIndex(): List<SessionSummary> = dir.listFiles { f -> f.name.endsWith(".chat.json") }?.mapNotNull { f ->
        runCatching { summary(json.decodeFromString<ChatSession>(f.readText())) }.getOrNull()
    }?.sortedByDescending { it.updated } ?: emptyList()

    private fun summary(s: ChatSession) = SessionSummary(
        s.id, s.title, s.updated,
        s.messages.lastOrNull { it.role != Role.TOOL && it.text.isNotBlank() }?.text?.take(90)?.replace('\n', ' ').orEmpty(),
        s.pinned,
    )

    suspend fun load(id: String): ChatSession? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<ChatSession>(File(dir, "$id.chat.json").readText()) }.getOrNull()
    }

    @Synchronized
    fun saveNow(session: ChatSession) {
        if (session.messages.isEmpty()) return
        File(dir, "${session.id}.chat.json").writeText(json.encodeToString(ChatSession.serializer(), session))
        val list = _index.value.filterNot { it.id == session.id } + summary(session)
        val sorted = list.sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.updated })
        _index.value = sorted
        indexFile.writeText(json.encodeToString(sorted))
    }

    suspend fun save(session: ChatSession) = withContext(Dispatchers.IO) { saveNow(session) }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.chat.json").delete()
        _index.value = _index.value.filterNot { it.id == id }
        indexFile.writeText(json.encodeToString(_index.value))
    }

    fun search(query: String): List<SessionSummary> {
        val q = query.lowercase()
        return _index.value.filter { it.title.lowercase().contains(q) || it.preview.lowercase().contains(q) }
    }
}
