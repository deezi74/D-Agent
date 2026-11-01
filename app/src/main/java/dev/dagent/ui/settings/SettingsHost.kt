package dev.dagent.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.dagent.Graph
import dev.dagent.linux.LinuxComputer

@Composable
fun SettingsHost(g: Graph, page: String, open: (String) -> Unit, back: () -> Unit) {
    when (page) {
        "providers" -> ProvidersPage(g, back)
        "local" -> LocalModelsPage(g, back)
        "persona" -> PersonaPage(g, back)
        "linux" -> LinuxPage(g, back)
        "memory" -> MemoryPage(g, back)
        "skills" -> SkillsPage(g, back)
        "tools" -> ToolsPage(g, back)
        "voice" -> VoicePage(g, back)
        "connectors" -> ConnectorsPage(g, back)
        "tasks" -> TasksPage(g, back)
        "guard" -> GuardPage(g, open, back)
        "activity" -> ActivityPage(g, back)
        "vault" -> VaultPage(g, back)
        "about" -> AboutPage(back)
        else -> Home(g, open, back)
    }
}

@Composable
private fun Home(g: Graph, open: (String) -> Unit, back: () -> Unit) {
    val prefs by g.settings.state.collectAsState()
    val linux by g.linux.state.collectAsState()
    val provider = g.settings.provider(prefs.providerId)
    Page("Settings", back) {
        NavRow(Icons.Default.Face, "${prefs.persona.name}: look and personality", "Name, character, picture, colors, about you") { open("persona") }
        NavRow(Icons.Default.Cloud, "AI providers", "${provider.preset.name} · ${provider.model}") { open("providers") }
        NavRow(Icons.Default.RecordVoiceOver, "Voice", if (prefs.voiceProvider == "phone") "Phone voice · set up a free natural voice" else "Natural voice · ${prefs.voiceName}") { open("voice") }
        NavRow(Icons.Default.Hub, "Connectors", "Email, GitHub, Telegram, Termux, weather and places") { open("connectors") }
        NavRow(Icons.Default.PhoneAndroid, "On-phone models", "Offline AI with GGUF models") { open("local") }
        NavRow(Icons.Default.Terminal, "Linux computer", when (linux) {
            is LinuxComputer.State.Ready -> "Installed"
            is LinuxComputer.State.Installing -> "Installing..."
            is LinuxComputer.State.Failed -> "Install failed"
            LinuxComputer.State.NotInstalled -> "Not installed"
        }) { open("linux") }
        NavRow(Icons.Default.Shield, "Guard and activity", "Rules, second look, secret vault, activity log") { open("guard") }
        NavRow(Icons.Default.Memory, "Memory", "What ${prefs.persona.name} knows about you") { open("memory") }
        NavRow(Icons.Default.Psychology, "Skills", "Know-how the agent reads before tasks") { open("skills") }
        NavRow(Icons.Default.Alarm, "Scheduled tasks", "Things set to run later") { open("tasks") }
        NavRow(Icons.Default.Tune, "Tools and behavior", "Search, images, read aloud, confirmations") { open("tools") }
        NavRow(Icons.Default.Info, "About D-Agent", "") { open("about") }
        NavRow(Icons.Default.AutoAwesome, "Run setup again", "Pick a name and provider from scratch") { g.settings.update { it.copy(onboarded = false) } }
    }
}
