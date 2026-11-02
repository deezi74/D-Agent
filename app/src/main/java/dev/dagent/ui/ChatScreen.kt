package dev.dagent.ui

import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import dev.dagent.Graph
import dev.dagent.agent.ChatState
import dev.dagent.agent.LiveTool
import dev.dagent.core.agent.PlanBoard
import dev.dagent.core.llm.Msg
import dev.dagent.core.llm.Role
import dev.dagent.data.ToolInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.dagent.character.Mood
import java.io.File

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(g: Graph, shared: MutableState<Pair<String, Uri?>?>, openSettings: (String) -> Unit, openVoice: () -> Unit = {}, openDiscover: () -> Unit = {}) {
    val p = LocalPalette.current
    val state by g.runner.state.collectAsState()
    // Seconds since the reply started, so a slow model visibly isn't frozen.
    var waited by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.running) {
        waited = 0
        while (state.running) { kotlinx.coroutines.delay(1_000); waited++ }
    }
    val prefs by g.settings.state.collectAsState()
    g.settings.providersVersion.collectAsState().value
    val provider = g.settings.provider(prefs.providerId)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<Uri>() }
    var showModels by remember { mutableStateOf(false) }
    val look by g.character.active.collectAsState()
    val confirm by g.interactions.confirm.collectAsState()
    var cheer by remember { mutableStateOf(false) }
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(state.running) {
        if (wasRunning && !state.running && state.error.isBlank()) { cheer = true; delay(5000); cheer = false }
        wasRunning = state.running
    }
    val mood = when {
        confirm != null -> Mood.WAITING
        state.running -> Mood.WORKING
        state.error.isNotBlank() -> Mood.ERROR
        cheer -> Mood.HAPPY
        else -> Mood.IDLE
    }

    LaunchedEffect(shared.value) {
        shared.value?.let { (text, img) ->
            if (text.isNotBlank()) input = text
            if (img != null) attachments += img
            shared.value = null
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = p.surface) {
                ChatDrawer(g, state.session.id,
                    onOpen = { g.runner.open(it); scope.launch { drawer.close() } },
                    onNew = { g.runner.newChat(); scope.launch { drawer.close() } },
                    onSettings = { scope.launch { drawer.close() }; openSettings("home") },
                    onPals = { scope.launch { drawer.close() }; openSettings("pals") })
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            // Header: menu, the agent, the model, Agent/Chat switch.
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Chats", tint = p.text) }
                AgentAvatar(prefs.persona.name, prefs.avatarPath, if (look != null) 44.dp else 38.dp, busy = state.running, modifier = Modifier.clickable { openSettings("persona") }, look = look, mood = mood)
                Column(Modifier.weight(1f).padding(start = 10.dp).clickable { showModels = true }) {
                    val pal = remember(prefs.activePal) { g.activePal() }
                    Text(pal?.let { "${it.emoji} ${it.name}" } ?: prefs.persona.name, color = p.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
                    Text(
                        if (state.running) (state.live.values.lastOrNull { it.running }?.title ?: "Thinking...") + (if (waited >= 5) " · ${waited}s" else "") else "${provider.preset.name} · ${provider.model}",
                        color = if (state.running) p.accent else p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                ModeSwitch(if (prefs.autoMode) "auto" else if (prefs.agentMode) "agent" else "chat", state.running && prefs.autoMode, state.usingAgent) { m ->
                    g.settings.update { it.copy(autoMode = m == "auto", agentMode = if (m == "auto") it.agentMode else m == "agent") }
                }
                IconButton(openDiscover) { Icon(Icons.Default.Explore, "Discover: feed, goals and ideas", tint = p.text) }
                IconButton(openVoice) { Icon(Icons.Default.GraphicEq, "Voice mode", tint = p.accent) }
                IconButton({ g.runner.newChat() }) { Icon(Icons.Default.Edit, "New chat", tint = p.text) }
            }

            Box(Modifier.weight(1f)) {
                if (state.session.messages.isEmpty() && !state.running) {
                    val pal = remember(prefs.activePal) { g.activePal() }
                    if (pal != null) PalEmptyChat(g, pal, prefs.persona.name, openPals = { openSettings("pals") }) { input = it }
                    else EmptyChat(prefs.persona.name, prefs.avatarPath, prefs.persona.userName, prefs.agentMode || prefs.autoMode, look, g, openPals = { openSettings("pals") }) { input = it }
                } else {
                    Transcript(g, state)
                }
            }

            BrowserLive(g)
            if (state.plan.isNotEmpty()) PlanCard(state.plan)
            if (state.notice.isNotBlank() && state.running) {
                Text(state.notice, color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
            if (state.error.isNotBlank()) ErrorBar(state.error, onRetry = { g.runner.dismissError(); g.runner.retryLast() }, onSettings = { openSettings("providers") }, onClose = { g.runner.dismissError() })

            Composer(
                g = g,
                input = input, onInput = { input = it },
                attachments = attachments,
                running = state.running,
                think = prefs.think,
                onThink = { g.settings.update { it.copy(think = !it.think) } },
                onSend = {
                    g.runner.send(input, attachments.toList())
                    input = ""
                    attachments.clear()
                },
                onStop = { g.runner.stop() },
            )
        }
    }

    if (showModels) ModelSwitcher(g) { showModels = false; if (it) openSettings("providers") }
    HandOverDialog(g)
}

@Composable
private fun ModeSwitch(mode: String, working: Boolean, usingAgent: Boolean, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(p.surface2).padding(3.dp)) {
        listOf("auto" to "Auto", "chat" to "Chat", "agent" to "Agent").forEach { (value, label) ->
            val on = value == mode
            // While Auto is working, show which way it went ("Auto·Agent").
            val shown = if (on && value == "auto" && working) (if (usingAgent) "Auto·Agent" else "Auto·Chat") else label
            Text(
                shown, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                color = if (on) Color(0xFF1A0E08) else p.muted,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) p.accent else Color.Transparent)
                    .clickable { onChange(value) }.padding(horizontal = 9.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun EmptyChat(name: String, avatar: String, user: String, agent: Boolean, look: dev.dagent.character.CharacterLook?, g: Graph, openPals: () -> Unit, pick: (String) -> Unit) {
    val p = LocalPalette.current
    val ideas = if (agent) listOf(
        "What's the biggest news today? Give me the short version with sources.",
        "Build me a one-page website for my business and show me a preview.",
        "Make an image of a neon tiger on a city rooftop at night.",
        "Look at my calendar and plan my week.",
        "Research the best budget phones this year and write me a report.",
        "Write a Python script that converts all my photos in /workspace to PNG.",
    ) else listOf(
        "Roast my morning routine: coffee, scroll, panic.",
        "What's trending right now?",
        "Explain black holes like I'm twelve.",
        "Give me a hot take on pineapple pizza.",
    )
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AgentAvatar(name, avatar, if (look != null) 120.dp else 84.dp, look = look)
        Spacer(Modifier.height(14.dp))
        Text("Hey${if (user.isNotBlank()) " $user" else ""}, I'm $name.", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            if (agent) "Give me a job. I'll search, code, browse and use your phone to get it done." else "Ask me anything. Switch to Agent when you want me to do the work.",
            color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
        )
        dev.dagent.ui.pals.PalStrip(g, openPals)
        Spacer(Modifier.height(10.dp))
        ideas.take(4).forEach { idea ->
            Text(
                idea, color = p.text, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                    .background(p.surface).border(1.dp, p.line, RoundedCornerShape(14.dp)).clickable { pick(idea) }.padding(12.dp),
            )
        }
    }
}

/** One row of the conversation as drawn. */
private sealed interface Row_ {
    data class User(val msg: Msg) : Row_
    data class Assistant(val msg: Msg, val results: Map<String, Msg>) : Row_
}

@Composable
private fun Transcript(g: Graph, state: ChatState) {
    val p = LocalPalette.current
    val list = rememberLazyListState()
    val rows = remember(state.session.messages) {
        val msgs = state.session.messages
        val results = msgs.filter { it.role == Role.TOOL }.associateBy { it.toolCallId.orEmpty() }
        msgs.mapNotNull { m ->
            when (m.role) {
                Role.USER -> Row_.User(m)
                Role.ASSISTANT -> Row_.Assistant(m, results)
                Role.TOOL -> null
            }
        }
    }
    val total = rows.size + 1
    LaunchedEffect(total, state.streamText.length / 200, state.live.size) {
        list.animateScrollToItem(total)
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        items(rows) { row ->
            when (row) {
                is Row_.User -> UserBubble(row.msg)
                is Row_.Assistant -> AssistantBlock(g, row.msg, row.results, state.session.tools, state.live)
            }
        }
        item {
            if (state.running) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    if (state.streamReasoning.isNotBlank()) Thought(state.streamReasoning, live = true)
                    if (state.streamText.isNotBlank()) Markdown(state.streamText)
                    if (state.streamText.isBlank() && state.live.values.none { it.running }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = p.accent)
                            Text("  thinking", color = p.muted, fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun UserBubble(m: Msg) {
    val p = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.End) {
        if (m.images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                m.images.take(4).forEach { img ->
                    val bytes = remember(img.base64.length) { android.util.Base64.decode(img.base64, android.util.Base64.DEFAULT) }
                    AsyncImage(bytes, null, contentScale = ContentScale.Crop, modifier = Modifier.size(110.dp).clip(RoundedCornerShape(14.dp)))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        val shown = m.text.substringBefore("\n\n[Attached:").trim()
        if (shown.isNotEmpty()) {
            Text(
                shown, color = p.text, fontSize = 16.sp,
                modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(p.surface2)
                    .clickable { clipboard.setText(AnnotatedString(shown)) }.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantBlock(g: Graph, m: Msg, results: Map<String, Msg>, infos: Map<String, ToolInfo>, live: Map<String, LiveTool>) {
    val p = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (m.reasoning.isNotBlank()) Thought(m.reasoning, live = false)
        if (m.text.isNotBlank()) {
            Markdown(m.text)
            if (m.toolCalls.isEmpty()) {
                Row {
                    IconButton({ clipboard.setText(AnnotatedString(m.text)) }, Modifier.size(34.dp)) { Icon(Icons.Default.ContentCopy, "Copy", tint = p.muted, modifier = Modifier.size(16.dp)) }
                    IconButton({ g.speaker.say(m.text) }, Modifier.size(34.dp)) { Icon(Icons.Default.VolumeUp, "Read aloud", tint = p.muted, modifier = Modifier.size(16.dp)) }
                }
            }
        }
        m.toolCalls.forEach { call ->
            val l = live[call.id]
            val info = infos[call.id]
            ToolCard(
                title = l?.title ?: info?.title ?: call.name,
                name = call.name,
                running = l?.running == true,
                progress = l?.progress.orEmpty(),
                millis = l?.millis ?: info?.millis ?: 0L,
                error = l?.error ?: info?.error ?: false,
                args = call.arguments,
                result = results[call.id]?.text,
            )
            val files = l?.files?.ifEmpty { null } ?: info?.files ?: emptyList()
            if (files.isNotEmpty()) FileStrip(files)
        }
    }
}

@Composable
private fun Thought(text: String, live: Boolean) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(Modifier.clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lightbulb, null, tint = p.muted, modifier = Modifier.size(14.dp))
            Text(if (live) "  Thinking..." else "  Thought it through", color = p.muted, fontSize = 13.sp)
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = p.muted, modifier = Modifier.size(16.dp))
        }
        AnimatedVisibility(open || live) {
            Text(
                if (live) text.takeLast(600) else text, color = p.muted, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp).border(width = 0.dp, color = Color.Transparent),
            )
        }
    }
}

@Composable
private fun ToolCard(title: String, name: String, running: Boolean, progress: String, millis: Long, error: Boolean, args: String, result: String?) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(p.surface)
            .border(1.dp, if (running) p.accent.copy(alpha = 0.5f) else p.line, RoundedCornerShape(14.dp))
            .clickable { open = !open }.padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                running -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = p.accent)
                error -> Icon(Icons.Default.ErrorOutline, null, tint = p.bad, modifier = Modifier.size(16.dp))
                else -> Icon(Icons.Default.Check, null, tint = p.good, modifier = Modifier.size(16.dp))
            }
            Text(title, color = p.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            Text(toolLabel(name) + if (millis > 0) " · ${"%.1f".format(millis / 1000.0)}s" else "", color = p.muted, fontSize = 11.sp)
        }
        if (running && progress.isNotBlank()) Text(progress, color = p.muted, fontSize = 12.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 8.dp)) {
                Text(prettyArgs(args), color = p.muted, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.horizontalScroll(rememberScrollState()))
                if (result != null) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = p.line)
                    Text(result.take(6000), color = p.text, fontSize = 12.sp, fontFamily = Mono, lineHeight = 16.sp)
                }
            }
        }
    }
}

private fun toolLabel(name: String) = when (name) {
    "web_search" -> "search"
    "web_fetch" -> "read page"
    "shell" -> "linux"
    "make_image" -> "image"
    else -> name.replace('_', ' ')
}

private fun prettyArgs(json: String): String = runCatching {
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
    obj.filterKeys { it != "title" }.entries.joinToString("\n") { (k, v) ->
        val s = (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString()
        "$k: ${s.take(1500)}"
    }
}.getOrDefault(json)

@Composable
private fun FileStrip(paths: List<String>) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var full by remember { mutableStateOf<String?>(null) }
    var fullSite by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        paths.forEach { path ->
            val f = File(path)
            if (f.extension.lowercase() in IMAGE_EXT) {
                AsyncImage(f, f.name, contentScale = ContentScale.Crop, modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).clickable { full = path })
            } else if (f.extension.lowercase() in listOf("mp4", "webm", "3gp") && f.isFile) {
                Column {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { c ->
                            android.widget.VideoView(c).apply {
                                setVideoPath(f.absolutePath)
                                setMediaController(android.widget.MediaController(c).also { it.setAnchorView(this) })
                                setOnPreparedListener { mp -> mp.isLooping = true; start() }
                            }
                        },
                        modifier = Modifier.size(width = 200.dp, height = 300.dp).clip(RoundedCornerShape(16.dp)),
                    )
                    TextButton({ shareFile(context, f) }) { Text("Share video") }
                }
            } else if (f.extension.lowercase() in listOf("html", "htm") && f.isFile) {
                Column {
                    HtmlPreview(f, Modifier.size(width = 280.dp, height = 440.dp).clip(RoundedCornerShape(16.dp)))
                    Row {
                        TextButton({ fullSite = path }) { Text("Full screen") }
                        TextButton({ shareFile(context, f) }) { Text("Share") }
                    }
                }
            } else {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(p.surface2).clickable { openFile(context, f) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.InsertDriveFile, null, tint = p.accent, modifier = Modifier.size(16.dp))
                    Text(" ${f.name}", color = p.text, fontSize = 13.sp)
                }
            }
        }
    }
    fullSite?.let { path ->
        Dialog({ fullSite = null }, DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Color.White)) {
                Row(Modifier.fillMaxWidth().background(p.bg).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(File(path).parentFile?.name ?: "Preview", color = p.text, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton({ shareFile(context, File(path)) }) { Text("Share") }
                    TextButton({ fullSite = null }) { Text("Close") }
                }
                HtmlPreview(File(path), Modifier.fillMaxSize())
            }
        }
    }
    full?.let { path ->
        Dialog({ full = null }, DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { full = null }, contentAlignment = Alignment.Center) {
                AsyncImage(File(path), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                Row(Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                    Button({ shareFile(context, File(path)) }) { Text("Share") }
                }
            }
        }
    }
}

/** A live web page made by make_website (or any .html file), with scripts and links working. */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPreview(f: File, modifier: Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c ->
            android.webkit.WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = android.webkit.WebViewClient()
                loadUrl(android.net.Uri.fromFile(f).toString())
            }
        },
        update = { it.reload() },
        modifier = modifier,
    )
}

fun openFile(context: android.content.Context, f: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "text/plain"
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, "No app can open ${f.name}", Toast.LENGTH_SHORT).show() }
}

fun shareFile(context: android.content.Context, f: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun PlanCard(steps: List<PlanBoard.Step>) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(true) }
    val done = steps.count { it.status == "done" }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(p.surface)
            .border(1.dp, p.line, RoundedCornerShape(14.dp)).clickable { open = !open }.padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Plan", color = p.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("$done/${steps.size}", color = p.accent, fontSize = 13.sp)
            Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ExpandLess, null, tint = p.muted)
        }
        AnimatedVisibility(open) {
            Column(Modifier.heightIn(max = 180.dp).padding(top = 6.dp)) {
                steps.forEach { s ->
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (s.status) { "done" -> "✓"; "active" -> "▸"; else -> "○" },
                            color = when (s.status) { "done" -> p.good; "active" -> p.accent; else -> p.muted }, fontSize = 14.sp,
                            modifier = Modifier.width(20.dp),
                        )
                        Text(s.text, color = if (s.status == "done") p.muted else p.text, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBar(text: String, onRetry: () -> Unit, onSettings: () -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2A1416)).padding(12.dp)) {
        Text(text, color = Color(0xFFFFC9C9), fontSize = 13.sp)
        Row {
            TextButton(onRetry) { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp)); Text(" Retry") }
            TextButton(onSettings) { Text("Providers") }
            Spacer(Modifier.weight(1f))
            IconButton(onClose) { Icon(Icons.Default.Close, "Dismiss", tint = p.muted) }
        }
    }
}

@Composable
private fun Composer(
    g: Graph,
    input: String,
    onInput: (String) -> Unit,
    attachments: MutableList<Uri>,
    running: Boolean,
    think: Boolean,
    onThink: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris -> attachments += uris }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> attachments += uris.take(10) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { attachments += it } }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) onInput(if (input.isBlank()) said else "$input $said")
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (attachments.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                attachments.toList().forEach { uri ->
                    Box {
                        val type = remember(uri) { runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty() }
                        if (type.isEmpty() || type.startsWith("image/")) {
                            AsyncImage(uri, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                        } else {
                            val name = remember(uri) {
                                runCatching {
                                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                                }.getOrNull() ?: "file"
                            }
                            Column(
                                Modifier.size(width = 120.dp, height = 64.dp).clip(RoundedCornerShape(12.dp)).background(p.surface2).padding(8.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Default.Description, null, tint = p.accent, modifier = Modifier.size(20.dp))
                                Text(name, color = p.text, fontSize = 11.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                        Icon(Icons.Default.Close, "Remove", tint = Color.White,
                            modifier = Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(Color(0x99000000)).clickable { attachments.remove(uri) })
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(26.dp)).padding(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                IconButton({ menu = !menu }) { Icon(Icons.Default.Add, "Add", tint = p.text) }
                androidx.compose.material3.DropdownMenu(menu, { menu = false }, containerColor = p.surface2) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Photos") }, leadingIcon = { Icon(Icons.Default.Image, null) },
                        onClick = { menu = false; pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Files") }, leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                        onClick = { menu = false; pickFiles.launch(arrayOf("*/*")) },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Camera") }, leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                        onClick = {
                            menu = false
                            val f = File(context.cacheDir, "camera-${System.currentTimeMillis()}.jpg")
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                            cameraUri = uri
                            camera.launch(uri)
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (think) "Thinking: on" else "Thinking: off") }, leadingIcon = { Icon(Icons.Default.Lightbulb, null) },
                        onClick = { menu = false; onThink() },
                    )
                }
            }
            TextField(
                value = input, onValueChange = onInput,
                placeholder = { Text("Ask ${g.settings.current.persona.name} anything", color = p.muted) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 180.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = p.accent,
                ),
            )
            if (think) Icon(Icons.Default.Lightbulb, "Thinking on", tint = p.accent, modifier = Modifier.padding(bottom = 14.dp, end = 2.dp).size(16.dp))
            when {
                running -> RoundButton(Icons.Default.Stop, p.text, p.surface2, "Stop", onStop)
                input.isBlank() && attachments.isEmpty() -> IconButton({
                    runCatching {
                        voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US"))
                    }.onFailure { Toast.makeText(context, "No speech recognizer on this phone", Toast.LENGTH_SHORT).show() }
                }) { Icon(Icons.Default.Mic, "Talk", tint = p.text) }
                else -> RoundButton(Icons.Default.ArrowUpward, Color(0xFF1A0E08), p.accent, "Send", onSend)
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, bg: Color, label: String, onClick: () -> Unit) {
    Box(Modifier.padding(4.dp).size(42.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint)
    }
}

/** The agent's browser, live. Tap to watch full screen. */
@Composable
private fun BrowserLive(g: Graph) {
    val p = LocalPalette.current
    val view by g.browser.view.collectAsState()
    var big by remember { mutableStateOf(false) }
    if (!view.active) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(14.dp))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Language, null, tint = p.accent, modifier = Modifier.size(16.dp))
            Text(" " + (view.title.ifBlank { view.url }), color = p.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (view.loading) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = p.accent)
            IconButton({ big = true }, Modifier.size(28.dp)) { Icon(Icons.Default.OpenInFull, "Expand", tint = p.muted, modifier = Modifier.size(16.dp)) }
            IconButton({ g.browser.close() }, Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Hide", tint = p.muted, modifier = Modifier.size(16.dp)) }
        }
        if (!big) WebHost(g, Modifier.fillMaxWidth().height(170.dp))
    }
    if (big) {
        Dialog({ big = false }, DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(view.url, color = p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton({ big = false }) { Text("Close") }
                }
                WebHost(g, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun WebHost(g: Graph, modifier: Modifier) {
    AndroidView(
        factory = { _ -> g.browser.webView().also { (it.parent as? ViewGroup)?.removeView(it) } },
        modifier = modifier,
        onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
    )
}

/** When a page needs the person (sign-in, captcha, payment), the browser opens for them. */
@Composable
private fun HandOverDialog(g: Graph) {
    val p = LocalPalette.current
    val req by g.browser.handOver.collectAsState()
    val r = req ?: return
    Dialog({ }, DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().background(p.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your turn", color = p.accent, fontWeight = FontWeight.Bold)
                    Text(r.reason, color = p.text, fontSize = 13.sp)
                }
                Button({ g.browser.finishHandOver() }) { Text("I'm done") }
            }
            WebHost(g, Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatDrawer(g: Graph, current: String, onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onPals: () -> Unit) {
    val p = LocalPalette.current
    val index by g.chats.index.collectAsState()
    var query by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val shown = if (query.isBlank()) index else g.chats.search(query)
    Column(Modifier.fillMaxHeight().width(300.dp).statusBarsPadding().padding(12.dp)) {
        Text("D-Agent", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(8.dp))
        Button(onNew, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)); Text("  New chat") }
        OutlinedTextField(
            query, { query = it }, singleLine = true, placeholder = { Text("Search chats") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { s ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (s.id == current) p.surface2 else Color.Transparent)
                        .combinedClickable(onClick = { onOpen(s.id) }, onLongClick = { confirmDelete = s.id }).padding(10.dp),
                ) {
                    Text(s.title, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    if (s.preview.isNotBlank()) Text(s.preview, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                }
            }
        }
        HorizontalDivider(color = p.line)
        Row(Modifier.fillMaxWidth().clickable(onClick = onPals).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, tint = p.accent)
            Text("  Pals and D's Hub", color = p.text)
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, null, tint = p.text)
            Text("  Settings", color = p.text)
        }
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this chat?") },
            confirmButton = { TextButton({ g.runner.delete(id); confirmDelete = null }) { Icon(Icons.Default.Delete, null); Text(" Delete") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Keep") } },
        )
    }
}

/** Quick switch between configured providers and models. */
@Composable
private fun ModelSwitcher(g: Graph, close: (openProviders: Boolean) -> Unit) {
    val p = LocalPalette.current
    val prefs by g.settings.state.collectAsState()
    val ready = g.settings.allProviders().filter { it.ready }
    AlertDialog(
        onDismissRequest = { close(false) },
        title = { Text("Brain") },
        text = {
            Column {
                ready.forEach { cfg ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (cfg.id == prefs.providerId) p.surface2 else Color.Transparent)
                            .clickable { g.settings.update { it.copy(providerId = cfg.id) }; close(false) }.padding(10.dp),
                    ) {
                        Column {
                            Text(cfg.preset.name, color = p.text)
                            Text(cfg.model, color = p.muted, fontSize = 12.sp)
                        }
                    }
                }
                if (ready.isEmpty()) Text("No provider is set up yet.", color = p.muted)
            }
        },
        confirmButton = { TextButton({ close(true) }) { Text("Manage providers") } },
    )
}

/** The empty chat when a Pal is in charge: its face, job and starter questions. */
@Composable
private fun PalEmptyChat(g: Graph, pal: dev.dagent.core.pals.Pal, everyday: String, openPals: () -> Unit, pick: (String) -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(Color(pal.color)), contentAlignment = Alignment.Center) {
            Text(pal.emoji, fontSize = 44.sp)
        }
        Spacer(Modifier.height(14.dp))
        Text("I'm ${pal.name}.", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(pal.tagline, color = p.muted, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        pal.starters.forEach { idea ->
            Text(
                idea, color = p.text, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                    .background(p.surface).border(1.dp, p.line, RoundedCornerShape(14.dp)).clickable { pick(idea) }.padding(12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ g.settings.update { it.copy(activePal = "") } }) { Text("Back to $everyday") }
            TextButton(openPals) { Text("Other Pals") }
        }
    }
}
