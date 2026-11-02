package dev.dagent.ui.pals

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.dagent.Graph
import dev.dagent.core.pals.Pal
import dev.dagent.core.pals.PalFormat
import dev.dagent.pals.DsHub
import dev.dagent.pals.coverUrl
import dev.dagent.ui.LocalPalette
import dev.dagent.ui.settings.Muted
import dev.dagent.ui.settings.Page
import java.io.File

/** A Pal's banner picture, with its color and emoji underneath while it loads (or if it has none). */
@Composable
fun PalCover(pal: Pal, modifier: Modifier = Modifier, emojiSize: Int = 34) {
    val base = Color(pal.color)
    Box(
        modifier.background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.55f), Color(0xFF0B0D12)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(pal.emoji, fontSize = emojiSize.sp)
        AsyncImage(model = coverUrl(pal), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Small rounded picture for lists. */
@Composable
fun PalThumb(pal: Pal, width: Int = 72) {
    PalCover(pal, Modifier.size(width.dp, (width * 0.62f).dp).clip(RoundedCornerShape(12.dp)), emojiSize = (width * 0.3f).toInt())
}

private enum class HubFilter(val label: String) {
    ALL("All"), FREE("Free"), SECURITY("Security"), FUN("Fun"), LEARN("Learning"), CREATE("Creative"), LIFE("Everyday"), PREMIUM("Premium");

    fun matches(pal: Pal): Boolean {
        val t = pal.tags.map { it.lowercase() }
        fun any(vararg k: String) = t.any { tag -> k.any { tag.contains(it) } }
        return when (this) {
            ALL -> true
            FREE -> !pal.premium
            PREMIUM -> pal.premium
            SECURITY -> any("hack", "security", "ctf", "bug bounty", "recon", "malware", "reverse", "decod", "crypto", "blue team", "network", "wireshark", "owasp", "mobile")
            FUN -> any("fun", "games", "comedy", "music", "story")
            LEARN -> any("learning", "thinking", "language")
            CREATE -> any("creative", "ai", "video", "music", "story", "content")
            LIFE -> !SECURITY.matches(pal) && !FUN.matches(pal)
        }
    }
}

/** D's Hub: picture cards, filters, search and a page for each Pal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var pals by remember { mutableStateOf<List<Pal>?>(null) }
    var error by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(HubFilter.ALL) }
    var version by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Pal?>(null) }
    LaunchedEffect(reload) {
        error = ""
        runCatching { g.hub.load(force = reload > 0) }.onSuccess { pals = it.pals }.onFailure { error = it.message ?: "Couldn't load D's Hub." }
    }
    Page("D's Hub", back) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Discover Pals", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Free for everyone. Accounts and your own uploads come later.", color = p.muted, fontSize = 12.sp)
            }
            TextButton({ searching = !searching }) { Icon(Icons.Default.Search, "Search", tint = p.text) }
        }
        if (searching) OutlinedTextField(search, { search = it }, label = { Text("Search Pals") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubFilter.entries.forEach { f ->
                val on = f == filter
                Text(
                    (if (on) "✓ " else "") + f.label, color = if (on) Color(0xFF1A0E08) else p.text, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.accent else p.surface)
                        .border(1.dp, if (on) p.accent else p.surface2, RoundedCornerShape(50))
                        .clickable { filter = f }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        if (error.isNotBlank()) Text(error, color = p.bad, fontSize = 13.sp)
        val list = pals
        if (list == null && error.isBlank()) Muted("Loading…")
        val shown = list.orEmpty().filter { pal ->
            filter.matches(pal) && (search.isBlank() || listOf(pal.name, pal.tagline, pal.description, pal.tags.joinToString(" ")).any { it.contains(search, ignoreCase = true) })
        }
        shown.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { pal ->
                    val have = remember(version, pal.id) { g.pals.get(pal.id) }
                    HubCard(pal, installed = have != null, Modifier.weight(1f)) { open = pal }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (list != null && shown.isEmpty()) Muted(if (filter == HubFilter.PREMIUM) "Premium Pals are coming later. Everything here is free for now." else "No Pals match.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ reload++ }) { Text("Refresh") }
            TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DsHub.SITE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open the website") }
        }
    }
    open?.let { pal ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { open = null }, sheetState = sheet, containerColor = p.bg) {
            PalDetail(g, pal, version, changed = { version++ }, done = { open = null; back() })
        }
    }
}

@Composable
private fun HubCard(pal: Pal, installed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(p.surface).border(1.dp, p.surface2, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            PalCover(pal, Modifier.fillMaxWidth().aspectRatio(768f / 400f).clip(RoundedCornerShape(12.dp)))
            Badge(if (pal.premium) "Premium" else "Free", if (pal.premium) Color(0xFFB9A8FF) else Color(0xFF7FE0C4), Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (installed) Badge("Installed", p.accent, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        Text("${pal.emoji} ${pal.name}", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("by ${pal.author}", color = p.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(pal.tagline, color = p.text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        if (pal.tags.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pal.tags.first(), color = p.text, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.border(1.dp, p.muted, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
            if (pal.tags.size > 1) Text("  +${pal.tags.size - 1}", color = p.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, color = Color(0xFF101318), fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 8.dp, vertical = 3.dp))
}

/** A Pal's own page: picture, who made it, what it does, what to ask, and Get / Use. */
@Composable
private fun PalDetail(g: Graph, pal: Pal, version: Int, changed: () -> Unit, done: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val have = remember(version, pal.id) { g.pals.get(pal.id) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PalCover(pal, Modifier.fillMaxWidth().aspectRatio(768f / 400f).clip(RoundedCornerShape(16.dp)), emojiSize = 56)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${pal.emoji} ${pal.name}", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("by ${pal.author}", color = p.muted, fontSize = 14.sp)
                }
                Text(if (pal.premium) "Premium" else "Free", color = if (pal.premium) Color(0xFFB9A8FF) else Color(0xFF7FE0C4), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface).padding(vertical = 12.dp)) {
                Stat("${pal.starters.size}", "Starters", Modifier.weight(1f))
                Stat("v${pal.version}", "Version", Modifier.weight(1f))
                Stat(pal.created.ifBlank { "—" }.let { formatDate(it) }, "Added", Modifier.weight(1f))
            }
            Text("Description", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(pal.description.ifBlank { pal.tagline }, color = p.text, fontSize = 14.sp, lineHeight = 20.sp)
            if (pal.starters.isNotEmpty()) {
                Text("Try asking", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                pal.starters.forEach {
                    Text("“$it”", color = p.text, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).padding(12.dp))
                }
            }
            val extras = listOfNotNull(
                pal.model?.local?.takeIf { it.isNotBlank() }?.let { "Prefers on-phone model $it" },
                pal.voicePack.takeIf { it.isNotBlank() }?.let { "Has its own voice" },
                if (pal.camera) "Uses the camera" else null,
            )
            if (extras.isNotEmpty()) Muted(extras.joinToString(" · "))
            if (pal.tags.isNotEmpty()) Muted(pal.tags.joinToString("   ") { "#$it" })
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({
                val f = File(context.cacheDir, "${pal.id}.${PalFormat.EXT}").apply { writeText(PalFormat.encode(pal)) }
                dev.dagent.ui.shareFile(context, f)
            }) { Text("Share") }
            if (have == null || (!have.builtin && have.version < pal.version)) {
                Button(
                    { val saved = g.pals.install(pal); g.settings.update { it.copy(activePal = saved.id) }; changed() },
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent),
                ) { Text(if (have == null) (if (pal.premium) "Get" else "⬇  Get free") else "Update", fontSize = 16.sp) }
            } else {
                Button(
                    { g.settings.update { it.copy(activePal = have.id) }; done() },
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent),
                ) { Text("Talk to ${pal.name}", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = p.muted, fontSize = 12.sp)
    }
}

private fun formatDate(iso: String): String {
    val parts = iso.split('-')
    return if (parts.size == 3) "${parts[1].trimStart('0')}/${parts[2].trimStart('0')}/${parts[0]}" else iso
}
