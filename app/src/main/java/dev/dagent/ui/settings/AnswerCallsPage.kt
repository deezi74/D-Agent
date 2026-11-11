package dev.dagent.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.dagent.Graph
import dev.dagent.core.calls.CallAnswering
import dev.dagent.core.calls.CallRules
import dev.dagent.phone.calls.CallAnswerer
import dev.dagent.phone.calls.CallHelperService
import dev.dagent.ui.LocalPalette

/** Settings > Answer my calls: the on/off switch, who gets answered, the greeting and what's still needed. */
@Composable
fun AnswerCallsPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val c = g.connectors
    val name = g.settings.current.persona.name
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    var on by remember { mutableStateOf(c.callAnswerOn) }
    var who by remember { mutableStateOf(c.callAnswerWho) }
    var list by remember { mutableStateOf(c.callAnswerList) }
    var ring by remember { mutableStateOf(c.callAnswerRing.toString()) }
    var max by remember { mutableStateOf(c.callAnswerMax.toString()) }
    var ownerName by remember { mutableStateOf(c.callAnswerOwner) }
    var greeting by remember { mutableStateOf(c.callAnswerGreeting) }
    var notes by remember { mutableStateOf(c.callAnswerNotes) }

    Page("Answer my calls", back) {
        Muted("When someone you allow calls and you don't pick up, $name answers on speakerphone, says it's an AI assistant, takes a message, then hangs up. You get a notification and the whole conversation is saved as a chat. Everything runs on your phone and is free; it uses your AI provider for the replies.")
        Card {
            ToggleRow("Answer my calls", if (on) "On: allowed callers get answered after ${ring.toIntOrNull() ?: 15} seconds" else "Off", on) { on = it; c.callAnswerOn = it }
        }

        val t = tick
        val missing = remember(t) { CallAnswerer.missing(g) }
        Section("Setup")
        Card {
            if (missing.isEmpty()) Text("Ready. Everything $name needs is set up.", color = p.accent)
            else {
                Text("Still needed:", fontWeight = FontWeight.SemiBold, color = p.text)
                missing.forEach { Muted("• $it") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    ask.launch(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS))
                }) { Text("Allow permissions") }
                OutlinedButton({ CallHelperService.openSettings(context) }) { Text(if (t >= 0 && CallHelperService.enabled(context)) "Call helper is on" else "Turn on call helper") }
            }
            Muted("Android only lets an app use the mic during a phone call if it has an accessibility helper turned on. In Accessibility, open Installed apps (or Downloaded apps) > D-Agent call helper and switch it on. It only unlocks the mic; it doesn't read your screen or tap anything.")
            Muted("If the switch is grayed out (Android 13 and up, for apps not from the Play Store): open Android's App info for D-Agent, tap the ⋮ menu at the top right, choose Allow restricted settings, then try again.")
            OutlinedButton({
                context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Open App info") }
            Muted("Listening on calls uses the on-phone listening model from Settings > Voice (Moonshine or Whisper), because Google's speech service can't hear during a call. The voice is your on-phone voice if you picked one, otherwise the phone's own.")
        }

        Section("Who gets answered")
        Card {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CallRules.WHO_LIST to "Only my list", CallRules.WHO_CONTACTS to "My contacts + list", CallRules.WHO_EVERYONE to "Everyone").forEach { (id, label) ->
                    FilterChip(who == id, { who = id; c.callAnswerWho = id }, label = { Text(label) })
                }
            }
            OutlinedTextField(list, { list = it; c.callAnswerList = it }, label = { Text("Allowed numbers (one per line or comma)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            val count = CallAnswering.parseList(list).size
            Muted(when (who) {
                CallRules.WHO_EVERYONE -> "Every caller gets answered, including unknown and hidden numbers. Scam callers will reach $name too."
                CallRules.WHO_CONTACTS -> "Anyone saved in your Contacts, plus the $count number(s) above."
                else -> if (count == 0) "Add at least one number, or nobody will be answered." else "Only these $count number(s) get answered."
            })
        }

        Section("How it answers")
        Card {
            OutlinedTextField(ring, { v -> ring = v.filter { it.isDigit() }.take(2); ring.toIntOrNull()?.let { c.callAnswerRing = it } }, label = { Text("Let it ring this many seconds first") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Muted("Gives you time to pick up yourself. Most carriers send calls to voicemail after 20 to 30 seconds, so keep it under 20.")
            OutlinedTextField(max, { v -> max = v.filter { it.isDigit() }.take(2); max.toIntOrNull()?.let { c.callAnswerMax = it } }, label = { Text("Longest call, in minutes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ownerName, { ownerName = it; c.callAnswerOwner = it }, label = { Text("Your name (who it answers for)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(greeting, { greeting = it; c.callAnswerGreeting = it }, label = { Text("Opening line (blank = default)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Muted("It will say: \"" + CallAnswering.greeting(c.callRules(name).copy(ownerName = ownerName, greeting = greeting)) + "\"")
            Muted("The opening line always says it's an AI assistant. If yours doesn't, that's added in front.")
            OutlinedTextField(notes, { notes = it; c.callAnswerNotes = it }, label = { Text("Things it can tell callers (optional)") }, placeholder = { Text("I'm at work until 5. Call back after 6.") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Muted("$name never shares your address, codes, accounts or schedule beyond what you write here, never agrees to pay for anything, and tells callers with an emergency to hang up and call 911.")
        }

        Section("Good to know")
        Card {
            Muted("This is speakerphone mode: $name hears the caller through the speaker and talks back through it, so the phone's mic carries its voice into the call. Put the phone somewhere quiet. Some phones' echo cancelling makes $name sound quiet or choppy to the caller; test with a friend first.")
            Muted("Calling and recording laws differ by state. $name tells every caller it's an AI at the start of the call.")
        }
    }
}
