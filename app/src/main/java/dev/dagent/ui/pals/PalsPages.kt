package dev.dagent.ui.pals

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dagent.Graph
import dev.dagent.core.pals.Pal
import dev.dagent.core.pals.PalFormat
import dev.dagent.core.pals.PalModel
import dev.dagent.core.voice.VoicePacks
import dev.dagent.pals.DsHub
import dev.dagent.ui.LocalPalette
import dev.dagent.ui.settings.Card
import dev.dagent.ui.settings.Muted
import dev.dagent.ui.settings.Page
import dev.dagent.ui.settings.Section
import dev.dagent.ui.settings.ToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pal faces in a row, for the empty chat. Tap one to put it in charge. */
@Composable
fun PalStrip(g: Graph, openPals: () -> Unit) {
    val p = LocalPalette.current
    val pals = remember { g.pals.all() }
    Text("Or talk to a Pal", color = p.muted, fontSize = 12.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        pals.forEach { pal ->
            Column(Modifier.width(64.dp).clickable { g.settings.update { it.copy(activePal = pal.id) } }, horizontalAlignment = Alignment.CenterHorizontally) {
                PalFace(pal, 48)
                Text(pal.name, color = p.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.width(64.dp).clickable(onClick = openPals), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(p.surface2), contentAlignment = Alignment.Center) { Text("+", color = p.accent, fontSize = 22.sp) }
            Text("More", color = p.text, fontSize = 11.sp)
        }
    }
}

@Composable
fun PalFace(pal: Pal, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(pal.color)), contentAlignment = Alignment.Center) {
        Text(pal.emoji, fontSize = (size * 0.45f).sp)
    }
}

/** Your Pals: use, edit, share, delete, make new, import; and the way into D's Hub. */
@Composable
fun PalsPage(g: Graph, open: (String) -> Unit, back: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val prefs by g.settings.state.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val pals = remember(version) { g.pals.all() }
    var note by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            note = runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.reader().readText() } }
                val pal = g.pals.install(PalFormat.decode(text))
                version++
                "Added ${pal.name}."
            }.getOrElse { "That file isn't a Pal: ${it.message}" }
        }
    }
    Page("Pals", back) {
        Muted("A Pal is a ready-made companion with its own job, personality and starter questions. It can also use its own model, word settings and voice. Pick one to put it in charge of new messages.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ open("hub") }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("Browse D's Hub") }
            OutlinedButton({ open("pal-edit:") }) { Text("Make a Pal") }
            TextButton({ importer.launch(arrayOf("*/*")) }) { Text("Import") }
        }
        if (note.isNotBlank()) Text(note, color = p.accent, fontSize = 13.sp)
        PalRow(
            emoji = "🙂", color = 0xFF2A2F3A, title = prefs.persona.name, subtitle = "Your everyday assistant (no Pal)",
            selected = prefs.activePal.isBlank(), onUse = { g.settings.update { it.copy(activePal = "") } },
        )
        Section("Built in")
        pals.filter { it.builtin }.forEach { pal -> PalCard(g, pal, prefs.activePal == pal.id, open) { version++ } }
        val mine = pals.filter { !it.builtin }
        if (mine.isNotEmpty()) {
            Section("Yours")
            mine.forEach { pal -> PalCard(g, pal, prefs.activePal == pal.id, open) { version++ } }
        }
    }
}

@Composable
private fun PalCard(g: Graph, pal: Pal, selected: Boolean, open: (String) -> Unit, changed: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface)
            .border(1.dp, if (selected) p.accent else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable { g.settings.update { it.copy(activePal = pal.id) } }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PalFace(pal, 44)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(pal.name, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(pal.tagline, color = p.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Text("In charge", color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        val extras = listOfNotNull(
            pal.model?.let { m -> listOf(m.provider, m.model, m.local).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }?.let { "Model: $it" } },
            pal.voicePack.takeIf { it.isNotBlank() }?.let { "Own voice" },
            if (pal.camera) "Camera" else null,
            pal.sampling?.temperature?.let { "Temp $it" },
        )
        if (extras.isNotEmpty()) Muted(extras.joinToString("  ·  "))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ open("pal-edit:" + pal.id) }) { Text(if (pal.builtin) "Copy and edit" else "Edit") }
            TextButton({
                val f = File(context.cacheDir, "${pal.id}.${PalFormat.EXT}").apply { writeText(PalFormat.encode(pal)) }
                dev.dagent.ui.shareFile(context, f)
            }) { Text("Share") }
            if (!pal.builtin) TextButton({
                if (g.settings.current.activePal == pal.id) g.settings.update { it.copy(activePal = "") }
                g.pals.delete(pal.id); changed()
            }, colors = ButtonDefaults.textButtonColors(contentColor = p.bad)) { Text("Delete") }
        }
    }
}

@Composable
private fun PalRow(emoji: String, color: Long, title: String, subtitle: String, selected: Boolean, onUse: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface)
            .border(1.dp, if (selected) p.accent else Color.Transparent, RoundedCornerShape(16.dp)).clickable(onClick = onUse).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color(color)), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 20.sp) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = p.muted, fontSize = 12.sp)
        }
        if (selected) Text("In charge", color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private val EMOJIS = listOf("✨", "🤖", "💻", "🔌", "🛡️", "📈", "📚", "😎", "👁️", "📝", "🎮", "🎨", "🍳", "💪", "🎵", "🧠", "🚀", "🐉", "🦊", "⚡")
private val COLORS = listOf(0xFF2A2F3A, 0xFF1E3A5F, 0xFF3D2A0E, 0xFF14342B, 0xFF3B1E3F, 0xFF233044, 0xFF2E2A1A, 0xFF1F3A3A, 0xFF4A1F1F, 0xFF33302A)

/** Make a new Pal, or edit one (built-in Pals are copied first). */
@Composable
fun PalEditor(g: Graph, id: String, back: () -> Unit) {
    val p = LocalPalette.current
    val start = remember(id) {
        g.pals.get(id)?.let { if (it.builtin) it.copy(id = it.id + "-mine", name = it.name + " (mine)", builtin = false) else it }
            ?: Pal("pal-" + System.currentTimeMillis().toString(36), "")
    }
    var name by remember { mutableStateOf(start.name) }
    var tagline by remember { mutableStateOf(start.tagline) }
    var emoji by remember { mutableStateOf(start.emoji) }
    var color by remember { mutableStateOf(start.color) }
    var instructions by remember { mutableStateOf(start.instructions) }
    var starters by remember { mutableStateOf(start.starters.joinToString("\n")) }
    var provider by remember { mutableStateOf(start.model?.provider.orEmpty()) }
    var model by remember { mutableStateOf(start.model?.model.orEmpty()) }
    var local by remember { mutableStateOf(start.model?.local.orEmpty()) }
    var temp by remember { mutableStateOf(start.sampling?.temperature?.toString().orEmpty()) }
    var voicePack by remember { mutableStateOf(start.voicePack) }
    var voiceId by remember { mutableStateOf(start.voiceId) }
    var camera by remember { mutableStateOf(start.camera) }
    var error by remember { mutableStateOf("") }
    fun save(): Boolean {
        if (name.isBlank()) { error = "Give it a name."; return false }
        val pal = start.copy(
            name = name, tagline = tagline, emoji = emoji, color = color, instructions = instructions,
            starters = starters.lines().map { it.trim() }.filter { it.isNotBlank() },
            model = PalModel(provider, model, local).takeIf { provider.isNotBlank() || model.isNotBlank() || local.isNotBlank() },
            sampling = (start.sampling ?: dev.dagent.core.llm.Sampling()).copy(temperature = temp.toDoubleOrNull()?.coerceIn(0.0, 2.0)).takeUnless { it.isEmpty },
            voicePack = voicePack, voiceId = voiceId, camera = camera, author = start.author.ifBlank { "You" }, builtin = false,
        )
        val saved = g.pals.save(pal)
        g.settings.update { it.copy(activePal = saved.id) }
        return true
    }
    Page(if (g.pals.get(id) == null) "Make a Pal" else "Edit Pal", back) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PalFace(start.copy(emoji = emoji, color = color), 56)
                Column(Modifier.padding(start = 12.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) { EMOJIS.forEach { e -> Text(e, fontSize = 22.sp, modifier = Modifier.clickable { emoji = e }.padding(4.dp)) } }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        COLORS.forEach { c -> Box(Modifier.size(24.dp).clip(CircleShape).background(Color(c)).border(2.dp, if (c == color) p.accent else Color.Transparent, CircleShape).clickable { color = c }) }
                    }
                }
            }
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tagline, { tagline = it.take(160) }, label = { Text("One line about it") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(instructions, { instructions = it }, label = { Text("What it does and how (its instructions)") }, minLines = 5, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(starters, { starters = it }, label = { Text("Starter questions, one per line") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        }
        Section("Brain (optional)")
        Card {
            Muted("Leave these empty to use whatever the app is set to. Provider ids: " + g.settings.allProviders().filter { it.ready }.joinToString { it.id } + ".")
            OutlinedTextField(provider, { provider = it.trim() }, label = { Text("Provider id (e.g. freellmapi, lmstudio, local)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it.trim() }, label = { Text("Model name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(local, { local = it.trim() }, label = { Text("On-phone model file (when provider is local)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(temp, { temp = it.trim() }, label = { Text("Creativity / temperature (0 to 2, empty = default)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Section("Voice and camera")
        Card {
            val packs = VoicePacks.talk.filter { g.voicePacks.installed(it) }
            if (packs.isEmpty()) Muted("Download an on-phone voice in Settings > Voice to give this Pal its own voice.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("App's voice", voicePack.isBlank()) { voicePack = ""; voiceId = null }
                packs.forEach { pk -> pk.voices.forEach { v -> Chip("${pk.name}: ${v.label.substringBefore(" (")}", voicePack == pk.id && voiceId == v.id) { voicePack = pk.id; voiceId = v.id } } }
            }
            ToggleRow("Uses the camera", "Shows a camera hint and works best in voice mode with the live camera.", camera) { camera = it }
        }
        if (error.isNotBlank()) Text(error, color = p.bad)
        Button({ if (save()) back() }, colors = ButtonDefaults.buttonColors(containerColor = p.accent), modifier = Modifier.fillMaxWidth()) { Text("Save and use") }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(label, color = if (on) Color(0xFF1A0E08) else p.text, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.accent else p.bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

/** D's Hub: Pals shared by the D-Agent community. No account needed to browse and install. */
@Composable
fun HubPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var pals by remember { mutableStateOf<List<Pal>?>(null) }
    var error by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        error = ""
        runCatching { g.hub.load(force = reload > 0) }.onSuccess { pals = it.pals }.onFailure { error = it.message ?: "Couldn't load D's Hub." }
    }
    Page("D's Hub", back) {
        Muted("Pals made by the D-Agent community. Install any of them for free. Sign-in and sharing your own straight to the Hub are coming later; for now, share a Pal as a file from the Pals page.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ reload++ }) { Text("Refresh") }
            TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DsHub.SITE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open the website") }
        }
        OutlinedTextField(filter, { filter = it }, label = { Text("Search Pals") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = p.bad, fontSize = 13.sp)
        val list = pals
        if (list == null && error.isBlank()) Muted("Loading…")
        val shown = list.orEmpty().filter { pal -> filter.isBlank() || listOf(pal.name, pal.tagline, pal.tags.joinToString(" ")).any { it.contains(filter, ignoreCase = true) } }
        shown.forEach { pal ->
            val have = remember(version, pal.id) { g.pals.get(pal.id) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PalFace(pal, 44)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(pal.name, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("by ${pal.author} · free", color = p.muted, fontSize = 12.sp)
                    }
                }
                Text(pal.tagline, color = p.text, fontSize = 13.sp)
                if (pal.tags.isNotEmpty()) Muted(pal.tags.joinToString("  ") { "#$it" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (have == null || (!have.builtin && have.version < pal.version)) {
                        Button({ val saved = g.pals.install(pal); g.settings.update { it.copy(activePal = saved.id) }; version++ }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) {
                            Text(if (have == null) "Get free" else "Update")
                        }
                    } else {
                        OutlinedButton({ g.settings.update { it.copy(activePal = have.id) }; back() }) { Text("Use") }
                    }
                }
            }
        }
        if (list != null && list.isEmpty()) Muted("No Pals in the Hub yet.")
    }
}
