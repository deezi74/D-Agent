package dev.dagent.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dagent.Graph
import dev.dagent.core.agent.Persona
import dev.dagent.core.llm.ChatRequest
import dev.dagent.core.llm.LlmEvent
import dev.dagent.core.llm.Msg
import dev.dagent.core.llm.Providers
import dev.dagent.data.Settings
import dev.dagent.linux.LinuxComputer
import dev.dagent.local.LocalModels
import dev.dagent.ui.AgentAvatar
import dev.dagent.ui.LocalPalette
import dev.dagent.ui.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

// ---- AI providers --------------------------------------------------------------------------------

@Composable
fun ProvidersPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    val prefs by g.settings.state.collectAsState()
    g.settings.providersVersion.collectAsState().value
    var editing by remember { mutableStateOf(prefs.providerId) }
    Page("AI providers", back) {
        Muted("Pick where the answers come from. Keys stay encrypted on this phone. Tap one to edit it, then Use.")
        g.settings.allProviders().forEach { cfg ->
            val active = cfg.id == prefs.providerId
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface)
                    .border(1.dp, if (active) p.accent else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable { editing = cfg.id }.padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cfg.preset.name, color = p.text, fontWeight = FontWeight.SemiBold)
                        Text(if (cfg.ready) cfg.model else if (cfg.preset.keyRequired) "Needs a key" else "Not set up", color = if (cfg.ready) p.muted else p.bad, fontSize = 12.sp)
                    }
                    if (active) Text("In use", color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                if (editing == cfg.id) ProviderEditor(g, cfg.id)
            }
        }
    }
}

@Composable
private fun ProviderEditor(g: Graph, id: String) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val cfg = remember(id) { g.settings.provider(id) }
    var url by remember(id) { mutableStateOf(cfg.baseUrl) }
    var key by remember(id) { mutableStateOf(cfg.apiKey) }
    var model by remember(id) { mutableStateOf(cfg.model) }
    var models by remember(id) { mutableStateOf<List<String>>(emptyList()) }
    var status by remember(id) { mutableStateOf("") }
    var filter by remember(id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (cfg.preset.blurb.isNotBlank()) Muted(cfg.preset.blurb)
        if (id != Providers.LOCAL_ID) OutlinedTextField(url, { url = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (id != Providers.LOCAL_ID) OutlinedTextField(
            key, { key = it }, label = { Text(if (cfg.preset.keyRequired) "API key" else "API key (optional)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        if (cfg.preset.keyUrl.isNotBlank()) Text(
            "Get a key: ${cfg.preset.keyUrl}", color = p.accent, fontSize = 13.sp,
            modifier = Modifier.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cfg.preset.keyUrl))) } },
        )
        if (id != Providers.LOCAL_ID) OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (models.isNotEmpty()) {
            OutlinedTextField(filter, { filter = it }, label = { Text("Filter ${models.size} models") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Column(Modifier.heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)).background(p.surface2).padding(4.dp)) {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    models.filter { filter.isBlank() || it.contains(filter, true) }.take(60).forEach { m ->
                        Text(m, color = if (m == model) p.accent else p.text, fontSize = 13.sp, modifier = Modifier.clickable { model = m }.padding(6.dp))
                    }
                }
            }
        }
        if (status.isNotBlank()) Text(status, color = p.muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                {
                    g.settings.saveProvider(id, url, model, key)
                    g.settings.update { it.copy(providerId = id) }
                    status = "Saved. ${cfg.preset.name} is now in use."
                },
                colors = ButtonDefaults.buttonColors(containerColor = p.accent),
            ) { Text("Use") }
            if (id != Providers.LOCAL_ID) OutlinedButton({
                scope.launch {
                    status = "Asking for the model list..."
                    val client = Providers.client(g.http, cfg.kind, url, key, cfg.preset.reasoning, cfg.preset.headers)
                    models = client.listModels()
                    status = if (models.isEmpty()) "The server didn't list models. Type the name yourself." else "Tap a model to pick it."
                }
            }) { Text("List models") }
            if (id != Providers.LOCAL_ID) OutlinedButton({
                scope.launch {
                    status = "Testing..."
                    status = withContext(Dispatchers.IO) {
                        runCatching {
                            val client = Providers.client(g.http, cfg.kind, url, key, cfg.preset.reasoning, cfg.preset.headers)
                            val sb = StringBuilder()
                            withTimeout(45_000) {
                                client.stream(ChatRequest(model, "Reply with one short friendly sentence.", listOf(Msg.user("Say hi.")), maxTokens = 60)).collect {
                                    if (it is LlmEvent.Text) sb.append(it.delta)
                                }
                            }
                            "Works: ${sb.toString().trim().take(120)}"
                        }.getOrElse { "Failed: ${it.message}" }
                    }
                }
            }) { Text("Test") }
        }
    }
}

// ---- On-phone models ---------------------------------------------------------------------------

@Composable
fun LocalModelsPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    val prefs by g.settings.state.collectAsState()
    val engine by g.local.engine.collectAsState()
    val downloads by g.local.downloads.collectAsState()
    var installed by remember { mutableStateOf(g.local.installed()) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            status = "Copying the model..."
            status = runCatching { g.local.import(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf").name.let { "Imported $it" } }.getOrElse { "Import failed: ${it.message}" }
            installed = g.local.installed()
        }
    }
    LaunchedEffect(Unit) { g.local.watchDownloads() }
    LaunchedEffect(downloads) { installed = g.local.installed() }
    Page("On-phone models", back) {
        Muted("Runs fully offline with llama.cpp. This phone has about ${"%.1f".format(g.local.totalRamGb())} GB of RAM; pick a model well under that. Then choose \"On this phone\" in AI providers.")
        if (!g.local.engineBundled) Text("This build has no on-phone engine for this phone (64-bit ARM only).", color = p.bad)
        Card {
            Text("Engine: " + when (val e = engine) {
                LocalModels.Engine.Stopped -> "stopped (starts when you chat)"
                is LocalModels.Engine.Starting -> "loading ${e.model}..."
                is LocalModels.Engine.Running -> "running ${e.model}"
                is LocalModels.Engine.Failed -> "failed: ${e.message.takeLast(200)}"
            }, color = p.text, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ g.local.stop() }) { Text("Stop") }
                OutlinedButton({ g.settings.update { it.copy(providerId = Providers.LOCAL_ID) }; status = "Chat now uses the on-phone model." }) { Text("Use for chat") }
            }
            Text("Context size: ${prefs.localContext} tokens", color = p.muted, fontSize = 13.sp)
            Slider(
                value = prefs.localContext.toFloat(), valueRange = 2048f..32768f, steps = 14,
                onValueChange = { v -> g.settings.update { it.copy(localContext = (v / 2048).toInt() * 2048) } },
            )
        }
        if (status.isNotBlank()) Muted(status)
        Section("Your models")
        if (installed.isEmpty()) Muted("None yet. Download one below or import a .gguf file.")
        installed.forEach { f ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(prefs.localModelFile == f.name, { g.settings.update { it.copy(localModelFile = f.name) } })
                    Column(Modifier.weight(1f)) {
                        Text(f.nameWithoutExtension, color = p.text, fontSize = 14.sp)
                        Text("${"%.2f".format(f.length() / 1e9)} GB", color = p.muted, fontSize = 12.sp)
                    }
                    TextButton({ g.local.delete(f); installed = g.local.installed() }) { Text("Delete", color = p.bad) }
                }
            }
        }
        OutlinedButton({ pickFile.launch(arrayOf("*/*")) }) { Text("Import a .gguf file") }
        Section("Download")
        val best = remember { g.local.best() }
        Muted("Tested with ${prefs.persona.name}'s tools. One tap downloads it; you can leave this screen while it runs.")
        g.local.catalog.sortedByDescending { it.id == best.id }.forEach { m ->
            val have = installed.any { it.name == m.fileName }
            val progress = downloads[m.fileName]
            val fits = g.local.fits(m)
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${m.name} · ${m.maker}", color = p.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (m.id == best.id) "Best for this phone" else m.tag, color = if (m.id == best.id) p.good else p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text("${"%.1f".format(m.bytes / 1e9)} GB · needs ${m.minRamGb} GB RAM. ${m.note}", color = p.muted, fontSize = 13.sp)
                if (!fits) Text("This phone may not have enough memory for it.", color = p.bad, fontSize = 12.sp)
                when {
                    have -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloaded", color = p.good, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton({
                            g.settings.update { it.copy(localModelFile = m.fileName, providerId = Providers.LOCAL_ID) }
                            status = "Chat now uses ${m.name} on this phone."
                        }) { Text("Use it") }
                    }
                    progress != null && progress >= 0 -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = p.accent)
                    else -> Button({ g.local.download(m); status = "Downloading ${m.name}. You can leave this screen." }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) {
                        Text(if (progress == -1f) "Retry download" else "Download")
                    }
                }
            }
        }
        HubSection(g, installed, downloads) { status = it }
    }
}

/** Search Hugging Face for any GGUF model and download one of its files. */
@Composable
private fun HubSection(g: Graph, installed: List<File>, downloads: Map<String, Float>, say: (String) -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf(emptyList<dev.dagent.local.HfRepo>()) }
    var openRepo by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(emptyList<dev.dagent.local.HfFile>()) }
    fun listFiles(repo: String) {
        busy = true; error = ""; openRepo = repo
        scope.launch {
            runCatching { g.local.hubFiles(repo) }
                .onSuccess { files = it; if (it.isEmpty()) error = "No .gguf files in $repo." }
                .onFailure { error = it.message ?: "Couldn't list files." }
            busy = false
        }
    }
    Section("Any model from Hugging Face")
    Muted("Search by name (qwen3, llama, gemma, phi, smollm...) or paste owner/repo. Q4_K_M files of 0.5B to 4B models run well on most phones.")
    OutlinedTextField(query, { query = it }, label = { Text("Search or owner/repo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({
            busy = true; error = ""; files = emptyList(); openRepo = ""
            scope.launch {
                runCatching { g.local.searchHub(query) }
                    .onSuccess { repos = it; if (it.isEmpty()) error = "Nothing found for \"$query\"." }
                    .onFailure { error = it.message ?: "Search failed." }
                busy = false
            }
        }, enabled = !busy && query.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Search") }
        OutlinedButton({ listFiles(query) }, enabled = !busy && query.contains('/')) { Text("Open repo") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
    if (error.isNotBlank()) Text(error, color = p.bad, fontSize = 13.sp)
    if (openRepo.isNotBlank() && files.isNotEmpty()) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(openRepo, color = p.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton({ openRepo = ""; files = emptyList() }) { Text("Back") }
            }
            files.forEach { f ->
                val have = installed.any { it.name == f.fileName }
                val progress = downloads[f.fileName]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.fileName, color = p.text, fontSize = 13.sp, fontFamily = Mono)
                        Text(if (f.bytes > 0) "${"%.2f".format(f.bytes / 1e9)} GB" else "size unknown", color = p.muted, fontSize = 12.sp)
                    }
                    when {
                        have -> Text("Downloaded", color = p.good, fontSize = 12.sp)
                        progress != null && progress >= 0 -> Text("${(progress * 100).toInt()}%", color = p.accent, fontSize = 12.sp)
                        else -> TextButton({ g.local.download(f); say("Downloading ${f.fileName}.") }) { Text("Download") }
                    }
                }
            }
        }
    } else {
        repos.forEach { r ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).clickable { listFiles(r.id) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.id, color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${compact(r.downloads)} downloads", color = p.muted, fontSize = 12.sp)
            }
        }
    }
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fK".format(n / 1e3)
    else -> n.toString()
}

// ---- Persona ---------------------------------------------------------------------------------

@Composable
fun PersonaPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val prefs by g.settings.state.collectAsState()
    var name by remember { mutableStateOf(prefs.persona.name) }
    var custom by remember { mutableStateOf(prefs.persona.customStyle) }
    var user by remember { mutableStateOf(prefs.persona.userName) }
    var about by remember { mutableStateOf(prefs.persona.aboutUser) }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    File(context.filesDir, "avatar").listFiles()?.forEach { it.delete() }
                    val f = File(context.filesDir, "avatar/avatar-${System.currentTimeMillis()}.img").apply { parentFile?.mkdirs() }
                    context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                    f.absolutePath
                }.getOrNull()
            }
            if (path != null) { g.settings.update { it.copy(avatarPath = path) }; g.character.clear() }
        }
    }
    fun save() = g.settings.update {
        it.copy(persona = it.persona.copy(name = name.ifBlank { "D" }, customStyle = custom, userName = user, aboutUser = about))
    }
    Page("Look and personality", { save(); back() }) {
        val look by g.character.active.collectAsState()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(name, prefs.avatarPath, 88.dp, look = look)
            Column(Modifier.padding(start = 16.dp)) {
                Button({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Choose picture") }
                if (look != null) TextButton({ g.character.clear() }) { Text("Stop using the character") }
                else if (prefs.avatarPath.isNotBlank()) TextButton({ g.settings.update { it.copy(avatarPath = "") } }) { Text("Use the monogram") }
            }
        }
        CharacterStudioCard(g)
        OutlinedTextField(name, { name = it.take(24) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Section("Personality")
        listOf(
            Persona.STYLE_WITTY to "Witty: bold, funny, sharp opinions",
            Persona.STYLE_WARM to "Warm: friendly and encouraging",
            Persona.STYLE_STRAIGHT to "Straight: short and to the point",
            Persona.STYLE_CUSTOM to "Custom: write it yourself",
        ).forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().clickable { g.settings.update { it.copy(persona = it.persona.copy(style = id)) } }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(prefs.persona.style == id, { g.settings.update { it.copy(persona = it.persona.copy(style = id)) } })
                Text(label, color = p.text)
            }
        }
        if (prefs.persona.style == Persona.STYLE_CUSTOM) OutlinedTextField(custom, { custom = it }, label = { Text("How it talks") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Section("About you")
        OutlinedTextField(user, { user = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(about, { about = it }, label = { Text("Anything it should always know (job, city, goals, how you like answers)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Section("Color")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Settings.ACCENTS.forEach { (label, c) ->
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Color(c))
                        .border(3.dp, if (prefs.accent == c) Color.White else Color.Transparent, CircleShape)
                        .clickable { g.settings.update { it.copy(accent = c) } },
                )
                if (label.isEmpty()) Text(label)
            }
        }
        Button({ save(); back() }, colors = ButtonDefaults.buttonColors(containerColor = p.accent), modifier = Modifier.fillMaxWidth()) { Text("Save") }
    }
}

@Composable
private fun CharacterStudioCard(g: Graph) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val look by g.character.active.collectAsState()
    val slots by g.character.slots.collectAsState()
    val posing by g.character.posing.collectAsState()
    var description by remember { mutableStateOf("") }
    var style by remember { mutableStateOf(dev.dagent.character.CharacterStudio.STYLES.first().id) }
    var error by remember { mutableStateOf("") }
    var saved by remember(look) { mutableStateOf(g.character.saved()) }
    Section("Character")
    Card {
        Muted("Describe a character and pick one of four. It gets a pose for each mood, so its face in the chat changes while it works, waits on you, finishes or slips up. You can also just say \"make yourself a robot fox\" in the chat.")
        OutlinedTextField(description, { description = it }, label = { Text("Who is it? (e.g. a chill astronaut cat in an orange suit)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dev.dagent.character.CharacterStudio.STYLES.forEach { st ->
                val on = st.id == style
                Text(
                    st.label, color = if (on) Color(0xFF1A0E08) else p.text, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.accent else p.bg).clickable { style = st.id }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        Button(
            { error = ""; g.character.makeOptions(description, style) },
            enabled = description.isNotBlank() && slots.none { it is dev.dagent.character.CharacterStudio.Slot.Loading },
            colors = ButtonDefaults.buttonColors(containerColor = p.accent),
        ) { Text(if (slots.isEmpty()) "Make 4 options" else "Make 4 more") }
        if (slots.isNotEmpty()) {
            slots.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEachIndexed { col, slot ->
                        val index = row * 2 + col
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(p.bg)
                                .clickable(enabled = slot is dev.dagent.character.CharacterStudio.Slot.Ready) {
                                    scope.launch { runCatching { g.character.pick(index) }.onFailure { error = it.message ?: "Couldn't use it" } }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            when (slot) {
                                is dev.dagent.character.CharacterStudio.Slot.Ready -> coil.compose.AsyncImage(
                                    model = slot.file, contentDescription = "Option ${index + 1}",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.matchParentSize(),
                                )
                                is dev.dagent.character.CharacterStudio.Slot.Failed -> Text(slot.message, color = p.bad, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                                else -> androidx.compose.material3.CircularProgressIndicator(color = p.accent, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
            Muted("Tap the one you like.")
        }
        if (error.isNotBlank()) Text(error, color = p.bad, fontSize = 13.sp)
        look?.let { l ->
            Text("In use: ${l.description}", color = p.text, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                dev.dagent.character.Mood.entries.forEach { m ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AgentAvatar("", "", 52.dp, look = l, mood = m)
                        Text(m.label, color = p.muted, fontSize = 10.sp)
                    }
                }
            }
            if (posing.isNotBlank()) Muted(posing)
            else if (l.posed() < dev.dagent.character.Mood.entries.size) TextButton({ g.character.redoMissingMoods() }) { Text("Draw the missing moods") }
        }
        if (saved.size > 1 || (saved.isNotEmpty() && look == null)) {
            Text("Saved characters", color = p.muted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                saved.forEach { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AgentAvatar("", "", 56.dp, look = s, modifier = Modifier.clickable { g.character.use(s) })
                        Text("Delete", color = p.bad, fontSize = 10.sp, modifier = Modifier.clickable { g.character.delete(s); saved = g.character.saved() })
                    }
                }
            }
        }
    }
}

// ---- Linux -----------------------------------------------------------------------------------

@Composable
fun LinuxPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    val state by g.linux.state.collectAsState()
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("uname -a && cat /etc/alpine-release") }
    var busy by remember { mutableStateOf(false) }
    Page("Linux computer", back) {
        Muted("A real Alpine Linux system inside the app. The agent uses it to run code, convert files, build websites and automate things. Your workspace is at /workspace. Nothing here can see the rest of your phone.")
        Card {
            Text(
                when (val s = state) {
                    LinuxComputer.State.NotInstalled -> "Not installed (about 60 MB after setup)"
                    is LinuxComputer.State.Installing -> s.step + "..."
                    LinuxComputer.State.Ready -> "Installed"
                    is LinuxComputer.State.Failed -> "Failed: ${s.message}"
                },
                color = p.text,
            )
            if (state is LinuxComputer.State.Installing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state !is LinuxComputer.State.Ready) Button(
                    { scope.launch { output = runCatching { g.linux.install() }.getOrElse { "Install failed: ${it.message}" } } },
                    enabled = state !is LinuxComputer.State.Installing && g.linux.engineBundled,
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent),
                ) { Text("Install") }
                else OutlinedButton({ g.linux.uninstall() }) { Text("Remove") }
            }
        }
        if (state is LinuxComputer.State.Ready) {
            Section("Terminal")
            OutlinedTextField(command, { command = it }, label = { Text("Command") }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 14.sp, color = p.text))
            Button({
                busy = true
                scope.launch {
                    output = runCatching { g.linux.run(command, 300) { }.let { it.output + "\n[exit ${it.exitCode}]" } }.getOrElse { it.message.orEmpty() }
                    busy = false
                }
            }, enabled = !busy) { Text(if (busy) "Running..." else "Run") }
        }
        if (output.isNotBlank()) Text(
            output.takeLast(8000), color = p.text, fontFamily = Mono, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0F1014)).padding(10.dp),
        )
    }
}

// ---- Memory ----------------------------------------------------------------------------------

@Composable
fun MemoryPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf(g.memory.all()) }
    Page("Memory", back) {
        Muted("The agent adds facts here when you tell it something worth keeping. Edit or delete anything.")
        OutlinedTextField(text, { text = it }, minLines = 12, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = p.text))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ g.memory.replaceAll(text); back() }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Save") }
            OutlinedButton({ text = "# What I know about you\n\n" }) { Text("Clear all") }
        }
    }
}

// ---- Skills ----------------------------------------------------------------------------------

@Composable
fun SkillsPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    var skills by remember { mutableStateOf(g.skills.list()) }
    var open by remember { mutableStateOf<String?>(null) }
    Page("Skills", back) {
        Muted("Instructions the agent reads before a matching task. It also writes new ones when it figures something out. In Linux they live at /skills.")
        skills.forEach { s ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { open = if (open == s.name) null else s.name }) {
                        Text(s.name, color = p.text, fontWeight = FontWeight.SemiBold)
                        Text(s.description, color = p.muted, fontSize = 13.sp)
                    }
                    TextButton({ g.skills.delete(s.folder.name); skills = g.skills.list() }) { Text("Delete", color = p.bad) }
                }
                if (open == s.name) Text(g.skills.read(s.name).orEmpty(), color = p.text, fontSize = 12.sp, fontFamily = Mono)
            }
        }
        if (skills.isEmpty()) Muted("No skills yet.")
    }
}

// ---- Tools and behavior -------------------------------------------------------------------------

@Composable
fun ToolsPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    val prefs by g.settings.state.collectAsState()
    var searx by remember { mutableStateOf(prefs.searxUrl) }
    var pollen by remember { mutableStateOf(g.settings.secret(Settings.POLLINATIONS_KEY)) }
    Page("Tools and behavior", { g.settings.update { it.copy(searxUrl = searx) }; g.settings.setSecret(Settings.POLLINATIONS_KEY, pollen); back() }) {
        Card {
            ToggleRow("Read replies aloud", "Speak every answer with the phone's voice", prefs.readAloud) { v -> g.settings.update { it.copy(readAloud = v) } }
            ToggleRow("Ask before texts, calls and calendar changes", "Recommended", prefs.confirmRisky) { v -> g.settings.update { it.copy(confirmRisky = v) } }
            ToggleRow("Think before answering", "Slower, better on hard questions (models that support it)", prefs.think) { v -> g.settings.update { it.copy(think = v) } }
        }
        Section("Calls and texts")
        Card {
            Muted("${prefs.persona.name} places calls and sends texts itself (after asking, if that's on). Pick which SIM it uses; you can also say \"call Mom on SIM 2\".")
            listOf(0 to "Phone's default SIM", 1 to "SIM 1", 2 to "SIM 2").forEach { (v, label) ->
                Row(Modifier.fillMaxWidth().clickable { g.settings.update { it.copy(defaultSim = v) } }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(prefs.defaultSim == v, { g.settings.update { it.copy(defaultSim = v) } })
                    Text(label, color = p.text)
                }
            }
        }
        Section("Vision")
        Card {
            listOf(0 to "Guess from the model name", 1 to "The model can see images", 2 to "The model can't see images").forEach { (v, label) ->
                Row(Modifier.fillMaxWidth().clickable { g.settings.update { it.copy(visionOverride = v) } }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(prefs.visionOverride == v, { g.settings.update { it.copy(visionOverride = v) } })
                    Text(label, color = p.text)
                }
            }
        }
        Section("Web search")
        Muted("Works without a key (DuckDuckGo, then Bing). Add your own SearXNG server for more control.")
        OutlinedTextField(searx, { searx = it }, label = { Text("SearXNG URL (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Section("Images")
        Muted("Pictures come from Pollinations. It works without a key; a free key from enter.pollinations.ai lifts the limits.")
        OutlinedTextField(pollen, { pollen = it }, label = { Text("Pollinations key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button({ g.settings.update { it.copy(searxUrl = searx) }; g.settings.setSecret(Settings.POLLINATIONS_KEY, pollen); back() }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Save") }
    }
}

// ---- Scheduled tasks ----------------------------------------------------------------------------

@Composable
fun TasksPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    var tasks by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { tasks = withContext(Dispatchers.IO) { g.scheduler.list() } }
    Page("Scheduled tasks", back) {
        Muted("Ask the agent things like \"every morning at 7, brief me on the news and weather\" and they show up here.")
        if (tasks.isEmpty()) Muted("Nothing scheduled.")
        tasks.forEach { (id, label, whenText) ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, color = p.text)
                        Text(whenText, color = p.muted, fontSize = 12.sp)
                    }
                    TextButton({
                        g.scheduler.cancel(id)
                        scope.launch { tasks = withContext(Dispatchers.IO) { g.scheduler.list() } }
                    }) { Text("Cancel", color = p.bad) }
                }
            }
        }
    }
}

// ---- About -----------------------------------------------------------------------------------

@Composable
fun AboutPage(back: () -> Unit) {
    val p = LocalPalette.current
    Page("About D-Agent", back) {
        Text("D-Agent ${dev.dagent.BuildConfig.VERSION_NAME}", color = p.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Muted("A personal AI agent for your phone, by deezi74. No account and no tracking: chats, memory and keys stay on this phone and go only to the AI provider you pick.")
        Section("Built with")
        Muted("Kotlin and Jetpack Compose. PRoot (GPL-2.0) and Alpine Linux run as separate programs for the Linux computer. llama.cpp (MIT) runs on-phone models. OkHttp, jsoup, Coil and kotlinx libraries (Apache-2.0 / MIT).")
    }
}
