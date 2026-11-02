package dev.dagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dagent.Graph
import dev.dagent.core.voice.VoicePacks
import dev.dagent.ui.LocalPalette

/** Free voices and listening that run on the phone, no internet once downloaded. */
@Composable
fun OnPhoneVoiceSection(g: Graph) {
    val p = LocalPalette.current
    val prefs by g.settings.state.collectAsState()
    val status by g.voiceDownloads.status.collectAsState()
    @Suppress("UNUSED_VARIABLE") val changes by g.voiceDownloads.changes.collectAsState()
    val ramGb = g.local.totalRamGb()

    Section("On-phone voices · free, works offline")
    Card {
        Muted("Human-sounding voices that run right on your phone. Download one once (Wi-Fi recommended), then it talks with no internet, no key and no Termux.")
        VoicePacks.talk.forEach { pack ->
            val installed = g.voicePacks.installed(pack)
            val selected = prefs.voiceProvider == pack.id && installed
            PackRow(
                title = pack.name, blurb = pack.blurb,
                facts = "${pack.approxMb} MB download · about ${pack.ramMb} MB memory while talking" +
                    if (ramGb > 0 && ramGb < 4 && pack.ramMb > 400) " · may be slow on this phone" else "",
                installed = installed, selected = selected, status = status[pack.id].orEmpty(), busy = g.voiceDownloads.busy(pack.id),
                onUse = { g.settings.update { it.copy(voiceProvider = pack.id, voiceName = pack.voices.first().id.toString()) } },
                onDownload = { g.voiceDownloads.start(pack) { g.settings.update { it.copy(voiceProvider = pack.id, voiceName = pack.voices.first().id.toString()) } } },
                onCancel = { g.voiceDownloads.cancel(pack.id) },
                onDelete = {
                    if (prefs.voiceProvider == pack.id) g.settings.update { it.copy(voiceProvider = "phone") }
                    g.neuralVoice.release()
                    g.voiceDownloads.delete(pack)
                },
            )
            if (selected) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pack.voices.forEach { v ->
                        val on = prefs.voiceName == v.id.toString()
                        Text(v.label, color = if (on) Color(0xFF1A0E08) else p.text, fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.accent else p.bg)
                                .clickable { g.settings.update { it.copy(voiceName = v.id.toString()) }; g.speaker.say("Hi, I'm ${prefs.persona.name}. This is how I sound.") }
                                .padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                Text("Speed ${"%.2f".format(prefs.voiceSpeed)}x", color = p.text, fontSize = 13.sp)
                Slider(prefs.voiceSpeed, { v -> g.settings.update { it.copy(voiceSpeed = (v * 20).toInt() / 20f) } }, valueRange = 0.7f..1.4f)
            }
        }
    }

    Section("Listening")
    Card {
        Muted("How it hears you in voice mode. On-phone listening works offline and lets you talk over the reply to interrupt it.")
        Row(Modifier.fillMaxWidth().clickable { g.settings.update { it.copy(listenEngine = "android") } }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(prefs.listenEngine == "android", { g.settings.update { it.copy(listenEngine = "android") } })
            Column {
                Text("Phone speech service", color = p.text)
                Muted("Google's recognizer. Usually needs internet.")
            }
        }
        VoicePacks.listen.forEach { pack ->
            val installed = g.voicePacks.installed(pack) && g.voicePacks.installed(VoicePacks.SILERO)
            PackRow(
                title = pack.name, blurb = pack.blurb, facts = "${pack.approxMb} MB download · about ${pack.ramMb} MB memory",
                installed = installed, selected = prefs.listenEngine == pack.id && installed, status = status[pack.id].orEmpty(), busy = g.voiceDownloads.busy(pack.id),
                onUse = { g.settings.update { it.copy(listenEngine = pack.id) } },
                onDownload = { g.voiceDownloads.start(pack) { g.settings.update { it.copy(listenEngine = pack.id) } } },
                onCancel = { g.voiceDownloads.cancel(pack.id) },
                onDelete = {
                    if (prefs.listenEngine == pack.id) g.settings.update { it.copy(listenEngine = "android") }
                    g.ears.release()
                    g.voiceDownloads.delete(pack)
                },
            )
        }
        ToggleRow("Talk over it", "While it's speaking, just start talking and it stops to listen (on-phone listening only). Works best with earbuds or lower volume.", prefs.talkOver) { on ->
            g.settings.update { it.copy(talkOver = on) }
        }
    }
}

@Composable
private fun PackRow(
    title: String, blurb: String, facts: String, installed: Boolean, selected: Boolean, status: String, busy: Boolean,
    onUse: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (selected) p.accent else p.muted.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .clickable(enabled = installed) { onUse() }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (selected) Text("In use", color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(blurb, color = p.text, fontSize = 13.sp)
        Muted(facts)
        if (status.isNotBlank()) Text(status.removePrefix("!"), color = if (status.startsWith("!")) p.bad else p.accent, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                busy -> TextButton(onCancel) { Text("Cancel") }
                installed -> {
                    if (!selected) OutlinedButton(onUse) { Text("Use") }
                    TextButton(onDelete, colors = ButtonDefaults.textButtonColors(contentColor = p.bad)) { Text("Delete") }
                }
                else -> OutlinedButton(onDownload) { Text("Download") }
            }
        }
    }
}
