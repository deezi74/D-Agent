package dev.dagent.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dagent.Graph
import dev.dagent.core.llm.Bench
import dev.dagent.core.llm.Providers
import dev.dagent.ui.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** How fast each on-phone model runs here: prompt reading (PP) and writing (TG) speed. */
@Composable
fun BenchmarkPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val models = remember { g.local.installed() }
    var pick by remember { mutableStateOf(models.firstOrNull { it.name == g.settings.current.localModelFile } ?: models.firstOrNull()) }
    var pp by remember { mutableIntStateOf(512) }
    var tg by remember { mutableIntStateOf(128) }
    var reps by remember { mutableIntStateOf(3) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val history = File(g.context.filesDir, "benchmarks.txt")
    var past by remember { mutableStateOf(runCatching { history.readLines().filter { it.isNotBlank() }.reversed() }.getOrDefault(emptyList())) }

    fun go(fast: Boolean) {
        val file = pick ?: return
        if (fast) { pp = 128; tg = 64; reps = 1 }
        busy = true
        scope.launch {
            status = "Loading ${file.nameWithoutExtension}…"
            try {
                val prefs = g.settings.current
                g.local.ensureRunning(file, maxOf(prefs.localContext, pp + tg + 64), prefs.localThreads, g.loadOpts())
                val r = withContext(Dispatchers.IO) {
                    Bench(g.http, "http://127.0.0.1:${Providers.LOCAL_PORT}").run(pp, tg, reps) { i -> status = "Run $i of $reps…" }
                }
                val line = "${file.nameWithoutExtension} · PP ${"%.1f".format(r.pp)} t/s · TG ${"%.1f".format(r.tg)} t/s · pp$pp tg$tg ×$reps · ${Build.MODEL} · " +
                    java.time.LocalDate.now()
                history.appendText(line + "\n")
                past = listOf(line) + past
                status = "Reads ${"%.1f".format(r.pp)} tokens/s (±${"%.1f".format(r.ppSpread)}), writes ${"%.1f".format(r.tg)} tokens/s (±${"%.1f".format(r.tgSpread)})."
            } catch (e: Exception) {
                status = "Benchmark failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Page("Benchmark", back) {
        Muted("Tests how fast an on-phone model runs on this phone. PP is how fast it reads your message and its instructions; TG is how fast it writes the answer (about 5+ tokens/s feels usable, 15+ feels quick).")
        if (models.isEmpty()) {
            Card { Muted("Download an on-phone model first in Settings > On-phone models.") }
            return@Page
        }
        Section("Model")
        Card {
            models.forEach { f ->
                Row(Modifier.fillMaxWidth().clickable { pick = f }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(pick == f, { pick = f })
                    Text(f.nameWithoutExtension, color = p.text, fontSize = 14.sp)
                    Text("  ${(f.length() / 1_000_000)} MB", color = p.muted, fontSize = 12.sp)
                }
            }
        }
        Section("Test")
        Card {
            Text("Prompt tokens (PP): $pp", color = p.text, fontSize = 14.sp)
            Slider(pp.toFloat(), { pp = ((it / 64).roundToInt() * 64).coerceAtLeast(64) }, valueRange = 64f..2048f)
            Text("Tokens to write (TG): $tg", color = p.text, fontSize = 14.sp)
            Slider(tg.toFloat(), { tg = ((it / 16).roundToInt() * 16).coerceAtLeast(16) }, valueRange = 16f..512f)
            Text("Repetitions: $reps", color = p.text, fontSize = 14.sp)
            Slider(reps.toFloat(), { reps = it.roundToInt() }, valueRange = 1f..5f, steps = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ go(false) }, enabled = !busy && pick != null, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text(if (busy) "Running…" else "Run benchmark") }
                OutlinedButton({ go(true) }, enabled = !busy && pick != null) { Text("Quick test") }
            }
            if (status.isNotBlank()) Text(status, color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        if (past.isNotEmpty()) {
            Section("Past results")
            Card {
                past.take(20).forEach { Text(it, color = p.text, fontSize = 13.sp) }
                TextButton({ history.delete(); past = emptyList() }) { Text("Clear") }
            }
        }
    }
}
