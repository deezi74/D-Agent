@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.dagent.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dagent.Graph
import dev.dagent.core.agent.GuardRule
import dev.dagent.core.agent.Risk
import dev.dagent.data.SecretVault
import dev.dagent.ui.LocalPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The tools a person can set rules for, in plain words. */
private val GUARDED_TOOLS = listOf(
    "browser" to "Web browser (clicking, typing, ordering)",
    "phone" to "Phone actions (texts, calls, alarms, calendar)",
    "ai_call" to "AI phone calls",
    "email" to "Email",
    "github" to "GitHub",
    "app_builder" to "App builder",
    "pc" to "Your PC (tasks and commands)",
    "shell" to "Linux computer commands",
    "termux" to "Termux commands",
    "file_write" to "Writing files",
    "schedule" to "Scheduling tasks",
    "web_fetch" to "Opening web pages",
    "make_image" to "Making pictures",
    "make_video" to "Making videos",
)

@Composable
fun GuardPage(g: Graph, open: (String) -> Unit, back: () -> Unit) {
    val p = LocalPalette.current
    val rules by g.guardSettings.rules.collectAsState()
    var reviewer by remember { mutableStateOf(g.guardSettings.reviewer) }
    var mail by remember { mutableStateOf(g.guardSettings.mailFilter) }
    val names by g.vault.namesFlow.collectAsState()
    val log by g.activity.entries.collectAsState()
    Page("Guard", back) {
        Muted("Every step the agent takes goes through the guard first. It follows your rules below, keeps steps that reach other people or spend money from running while you're away, hides your saved secrets, and logs everything.")
        NavRow(Icons.Default.History, "Activity log", "${log.size} steps recorded") { open("activity") }
        NavRow(Icons.Default.Key, "Secret vault", if (names.isEmpty()) "Passwords and cards the agent can use without seeing" else "${names.size} saved") { open("vault") }
        Section("Checks")
        Card {
            ToggleRow("Second look", "Before a text, call, post or order, a quick check with your AI model makes sure it matches what you asked. If not, you're asked. Uses a little more of your AI quota.", reviewer) {
                reviewer = it; g.guardSettings.reviewer = it
            }
            ToggleRow("Hide codes and sign-in links in mail", "One-time codes and password-reset links never reach the agent, so a trick email can't get it to hand them over.", mail) {
                mail = it; g.guardSettings.mailFilter = it
            }
        }
        Section("Rules per tool")
        Muted("Normal: lookups just run, and risky steps ask you as usual. Always ask: you OK every use. Never: the agent can't use it.")
        GUARDED_TOOLS.forEach { (tool, label) ->
            val rule = rules[tool] ?: GuardRule.AUTO
            Card {
                Text(label, color = p.text, fontSize = 14.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GuardRule.entries.forEach { r ->
                        FilterChip(selected = r == rule, onClick = { g.guardSettings.set(tool, r) }, label = { Text(r.label) })
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    val log by g.activity.entries.collectAsState()
    var filter by remember { mutableStateOf<Risk?>(null) }
    val time = remember { SimpleDateFormat("MMM d, h:mm a", Locale.US) }
    Page("Activity log", back) {
        Muted("Everything the agent did or tried, newest first. Saved secrets show as {{vault:name}}, never the real value.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
            Risk.entries.forEach { r -> FilterChip(selected = filter == r, onClick = { filter = r }, label = { Text(r.label) }) }
        }
        val shown = log.asReversed().filter { filter == null || it.risk == filter }.take(300)
        if (shown.isEmpty()) Muted("Nothing yet.")
        shown.forEach { e ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(e.verdict, color = if (e.ok) p.muted else p.bad, fontSize = 12.sp)
                }
                Text("${time.format(Date(e.time))} · ${e.tool} · ${e.risk.label}${if (e.millis > 0) " · ${"%.1f".format(e.millis / 1000.0)} s" else ""}", color = p.muted, fontSize = 12.sp)
                if (e.detail.isNotBlank()) Text(e.detail, color = p.muted, fontSize = 12.sp, maxLines = 4)
                if (e.result.isNotBlank()) Text(e.result, color = if (e.ok) p.text else p.bad, fontSize = 12.sp, maxLines = 3)
            }
        }
        if (log.isNotEmpty()) TextButton({ g.activity.clear() }) { Text("Clear the log", color = p.bad) }
    }
}

@Composable
fun VaultPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    val names by g.vault.namesFlow.collectAsState()
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    Page("Secret vault", back) {
        Muted("Save a password, card number or other secret under a short name like amazon or visa. The agent only ever sees the name. When it fills in a web page it writes {{vault:amazon}}, and the guard types the real value at the last moment and wipes it from anything that comes back. Values are encrypted with a key kept in this phone's secure hardware.")
        if (!g.vault.available) Text("This phone's secure storage isn't working, so the vault is off rather than saving secrets unprotected.", color = p.bad)
        Section("Add a secret")
        Card {
            OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. amazon)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value, { value = it }, label = { Text("Secret") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedButton({
                val key = SecretVault.clean(name)
                status = if (g.vault.put(key, value)) { name = ""; value = ""; "Saved as {{vault:$key}}." } else "Add a name and a secret."
            }, enabled = g.vault.available) { Text("Save") }
            if (status.isNotBlank()) Muted(status)
        }
        Section("Saved")
        if (names.isEmpty()) Muted("Nothing saved yet.")
        names.forEach { n ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n, color = p.text)
                        Text("{{vault:$n}} · value hidden", color = p.muted, fontSize = 12.sp)
                    }
                    TextButton({ g.vault.remove(n) }) { Text("Delete", color = p.bad) }
                }
            }
        }
    }
}
