package dev.dagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dagent.Graph

/** First run: name your agent and pick where its brain lives. No account, no sign-in. */
@Composable
fun Onboarding(g: Graph, done: () -> Unit) {
    val p = LocalPalette.current
    var name by remember { mutableStateOf(g.settings.current.persona.name) }
    var you by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("freellmapi") }
    var key by remember { mutableStateOf("") }
    val choices = listOf(
        "freellmapi" to "FreeLLMAPI in Termux (free, no key)",
        "openrouter" to "OpenRouter (free models with a key)",
        "gemini" to "Google Gemini (free tier key)",
        "anthropic" to "Anthropic Claude",
        "openai" to "OpenAI",
        "on_phone" to "Offline on this phone (download a model next)",
    )
    Column(
        Modifier.fillMaxSize().background(p.bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        AgentAvatar(name, "", 96.dp, busy = true)
        Text("Meet your agent", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = p.text)
        Text(
            "It searches the live web, runs code on its own Linux computer, drives a browser, and handles your phone: texts, alarms, calendar and more. Nothing to sign up for.",
            color = p.muted, style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(name, { name = it.take(24) }, label = { Text("Your agent's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(you, { you = it.take(40) }, label = { Text("What should it call you? (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Where should its brain run?", color = p.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
        choices.forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (provider == id) p.surface2 else p.surface)
                    .clickable { provider = id }.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(provider == id, { provider = id })
                Text(label, color = p.text)
            }
        }
        val preset = g.settings.provider(provider).preset
        if (preset.keyRequired) {
            OutlinedTextField(
                key, { key = it.trim() }, label = { Text("${preset.name} API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            if (preset.keyUrl.isNotBlank()) Text("Get one at ${preset.keyUrl}", color = p.muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
        } else if (preset.blurb.isNotBlank()) {
            Text(preset.blurb, color = p.muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
        }
        Button(
            onClick = {
                val cfg = g.settings.provider(provider)
                g.settings.saveProvider(provider, cfg.baseUrl, cfg.model, key.ifBlank { cfg.apiKey })
                g.settings.update { it.copy(providerId = provider, persona = it.persona.copy(name = name.ifBlank { "D" }, userName = you.trim())) }
                done()
            },
            enabled = !preset.keyRequired || key.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = p.accent),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Let's go", fontWeight = FontWeight.Bold) }
        Text("You can change all of this later in Settings.", color = p.muted, fontSize = 12.sp)
    }
}
