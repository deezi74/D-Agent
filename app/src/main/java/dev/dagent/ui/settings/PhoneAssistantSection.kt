package dev.dagent.ui.settings

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import dev.dagent.Graph
import dev.dagent.phone.PhoneAssistant
import dev.dagent.ui.LocalPalette
import kotlinx.coroutines.delay

/** Settings > Phone assistant: the switch for using D-Agent as the phone's digital assistant. */
@Composable
fun PhoneAssistantPage(g: Graph, back: () -> Unit) {
    Page("Phone assistant", back) { PhoneAssistantSection(g) }
}

@Composable
fun PhoneAssistantSection(g: Graph) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val prefs by g.settings.state.collectAsState()
    val name = prefs.persona.name
    var on by remember { mutableStateOf(PhoneAssistant.enabled(context)) }
    var isDefault by remember { mutableStateOf(PhoneAssistant.isDefault(context)) }
    var note by remember { mutableStateOf("") }
    // The person picks the assistant in the phone's settings, so check again when they come back.
    LaunchedEffect(on) {
        while (true) {
            isDefault = PhoneAssistant.isDefault(context)
            delay(1500)
        }
    }
    Card {
        ToggleRow(
            "Use as the phone's assistant",
            if (on) "On: $name can be picked as the digital assistant app" else "Off: $name isn't offered as the phone's assistant",
            on,
        ) { v ->
            runCatching { PhoneAssistant.setEnabled(context, v) }
                .onSuccess { on = v; note = "" }
                .onFailure { note = "Couldn't change it: ${it.message}" }
        }
        Muted(
            "When $name is the phone's assistant, long-press the power or home button (or swipe up from a bottom corner, " +
                "depending on the phone) and it opens straight into hands-free voice. Closing voice mode goes back to what you were doing."
        )
        val status = when {
            !on -> "Assistant mode is off."
            isDefault == true -> "$name is your phone's assistant."
            isDefault == false -> "$name isn't picked yet. Tap below, open Digital assistant app, and choose $name."
            else -> "Tap below, open Digital assistant app (or Assist app), and choose $name."
        }
        Text(status, color = if (on && isDefault == true) p.good else p.text, fontSize = 14.sp)
        if (on) Button(
            { if (!PhoneAssistant.openPicker(context)) note = "Couldn't open the phone's settings. Go to Settings > Apps > Default apps > Digital assistant app." },
            colors = ButtonDefaults.buttonColors(containerColor = p.accent),
        ) { Text(if (isDefault == true) "Change the phone's assistant" else "Pick the phone's assistant") }
        if (note.isNotBlank()) Text(note, color = p.bad, fontSize = 13.sp)
    }
}
