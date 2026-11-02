package dev.dagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dagent.Graph
import dev.dagent.core.llm.Sampling
import dev.dagent.ui.LocalPalette
import kotlin.math.roundToInt

/**
 * Every word-picking knob, plus how the on-phone model is loaded. Anything left on "Default"
 * isn't sent, so the model's own recommended settings apply. A Pal's own settings win.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GenerationPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    val prefs by g.settings.state.collectAsState()
    val s = prefs.sampling
    fun set(block: (Sampling) -> Sampling) = g.settings.update { it.copy(sampling = block(it.sampling)) }
    Page("Generation settings", back) {
        Muted("How the model picks its words. Leave a setting on Default to use the model's own. On your phone and home PC (llama.cpp, LM Studio) every setting works; cloud services use creativity, top P, penalties, seed and length. A Dab's own settings win over these.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ g.settings.update { it.copy(sampling = Sampling(temperature = 0.3, topP = 0.9, minP = 0.05)) } }) { Text("Precise") }
            OutlinedButton({ g.settings.update { it.copy(sampling = Sampling(temperature = 0.7, topP = 0.95, minP = 0.05)) } }) { Text("Balanced") }
            OutlinedButton({ g.settings.update { it.copy(sampling = Sampling(temperature = 1.0, topP = 0.95, minP = 0.05, xtcProbability = 0.3, xtcThreshold = 0.1)) } }) { Text("Creative") }
        }
        TextButton({ g.settings.update { it.copy(sampling = Sampling()) } }) { Text("Reset all to default") }

        Section("Main")
        Card {
            Knob("Creativity (temperature)", "Higher is more surprising, lower is more focused.", s.temperature, 0f..2f, 0.05f) { v -> set { it.copy(temperature = v) } }
            Knob("Top P", "Only consider the most likely words that add up to this share.", s.topP, 0f..1f, 0.01f) { v -> set { it.copy(topP = v) } }
            Knob("Top K", "Only consider this many of the most likely words.", s.topK?.toDouble(), 0f..200f, 1f) { v -> set { it.copy(topK = v?.roundToInt()) } }
            Knob("Min P", "Drop words much less likely than the best one. 0.05 is a good start.", s.minP, 0f..0.5f, 0.01f) { v -> set { it.copy(minP = v) } }
            LengthField(s.maxTokens) { v -> set { it.copy(maxTokens = v) } }
        }

        Section("Repetition")
        Card {
            Knob("Repeat penalty", "Above 1 discourages repeating words.", s.repeatPenalty, 0.8f..2f, 0.01f) { v -> set { it.copy(repeatPenalty = v) } }
            Knob("Look back (repeat last N)", "How many recent tokens the repeat penalty checks.", s.repeatLastN?.toDouble(), 0f..512f, 16f) { v -> set { it.copy(repeatLastN = v?.roundToInt()) } }
            Knob("Presence penalty", "Pushes toward new topics.", s.presencePenalty, -2f..2f, 0.05f) { v -> set { it.copy(presencePenalty = v) } }
            Knob("Frequency penalty", "Pushes toward a wider vocabulary.", s.frequencyPenalty, -2f..2f, 0.05f) { v -> set { it.copy(frequencyPenalty = v) } }
        }

        Section("Advanced (phone and home PC)")
        Card {
            Knob("Typical P", "Locally typical sampling. 1 is off.", s.typicalP, 0f..1f, 0.01f) { v -> set { it.copy(typicalP = v) } }
            Knob("XTC chance", "Sometimes skips the most obvious word for livelier writing. 0 is off.", s.xtcProbability, 0f..1f, 0.05f) { v -> set { it.copy(xtcProbability = v) } }
            Knob("XTC threshold", "Which top words XTC may skip. Above 0.5 turns it off.", s.xtcThreshold, 0f..0.6f, 0.01f) { v -> set { it.copy(xtcThreshold = v) } }
            Text("Mirostat", color = p.text, fontSize = 14.sp)
            Muted("Keeps surprise steady over long answers. Replaces top K/P when on.")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(0, 1, 2).forEachIndexed { i, m ->
                    SegmentedButton((s.mirostat ?: 0) == m, { set { it.copy(mirostat = if (m == 0) null else m, mirostatTau = if (m == 0) null else it.mirostatTau ?: 5.0, mirostatEta = if (m == 0) null else it.mirostatEta ?: 0.1) } },
                        SegmentedButtonDefaults.itemShape(i, 3)) { Text(if (m == 0) "Off" else "v$m") }
                }
            }
            if ((s.mirostat ?: 0) > 0) {
                Knob("Mirostat target (tau)", "", s.mirostatTau, 0f..10f, 0.1f) { v -> set { it.copy(mirostatTau = v) } }
                Knob("Mirostat speed (eta)", "", s.mirostatEta, 0f..1f, 0.01f) { v -> set { it.copy(mirostatEta = v) } }
            }
            SeedField(s.seed) { v -> set { it.copy(seed = v) } }
        }

        Section("On-phone model loading")
        Card {
            Muted("Changes take effect the next time the on-phone model loads.")
            Text("Context size: ${prefs.localContext} tokens", color = p.text, fontSize = 14.sp)
            Slider(prefs.localContext.toFloat(), { v -> g.settings.update { it.copy(localContext = ((v / 1024).roundToInt() * 1024).coerceAtLeast(1024)) } }, valueRange = 1024f..32768f)
            Text("CPU threads: " + (if (prefs.localThreads == 0) "auto" else prefs.localThreads.toString()), color = p.text, fontSize = 14.sp)
            Slider(prefs.localThreads.toFloat(), { v -> g.settings.update { it.copy(localThreads = v.roundToInt()) } }, valueRange = 0f..8f, steps = 7)
            ToggleRow("Lock in memory", "Keeps the whole model in RAM so Android can't swap or compress it. Smoother, but uses more memory.", prefs.localMlock) { on -> g.settings.update { it.copy(localMlock = on) } }
            ToggleRow("Memory-map the file", "Faster start and less RAM up front. Turn off if the phone stutters while answering.", prefs.localMmap) { on -> g.settings.update { it.copy(localMmap = on) } }
            ToggleRow("Compact chat memory", "Stores the chat's working memory at 8 bits: about half the RAM for long chats, tiny quality cost.", prefs.localKvQuant) { on -> g.settings.update { it.copy(localKvQuant = on) } }
            ToggleRow("Unload when in background", "Frees the model's memory when you leave the app. The next message reloads it.", prefs.localUnloadInBackground) { on -> g.settings.update { it.copy(localUnloadInBackground = on) } }
        }
    }
}

/** A setting that is either Default (null) or a value on a slider. */
@Composable
private fun Knob(title: String, help: String, value: Double?, range: ClosedFloatingPointRange<Float>, step: Float, onChange: (Double?) -> Unit) {
    val p = LocalPalette.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value?.let { if (step >= 1f) it.roundToInt().toString() else "%.2f".format(it) } ?: "Default", color = if (value == null) p.muted else p.accent, fontSize = 14.sp)
            if (value != null) TextButton({ onChange(null) }) { Text("Reset") }
        }
        if (help.isNotBlank()) Muted(help)
        Slider(
            (value?.toFloat() ?: range.start).coerceIn(range),
            { v -> onChange(((v / step).roundToInt() * step).toDouble().let { (it * 1000).roundToInt() / 1000.0 }) },
            valueRange = range,
        )
    }
}

@Composable
private fun LengthField(value: Int?, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value?.toString().orEmpty(), { t -> onChange(t.filter(Char::isDigit).take(6).toIntOrNull()?.takeIf { it > 0 }) },
        label = { Text("Longest reply in tokens (empty = default)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SeedField(value: Long?, onChange: (Long?) -> Unit) {
    OutlinedTextField(
        value?.toString().orEmpty(), { t -> onChange(t.filter(Char::isDigit).take(12).toLongOrNull()) },
        label = { Text("Seed (empty = random; same seed repeats answers)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}
