package dev.dagent.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import dev.dagent.Graph
import dev.dagent.ui.LocalPalette

/** One thing the agent can be allowed to do, in plain words. */
private data class Access(val title: String, val why: String, val perms: List<String>)

private fun accessList(name: String): List<Access> = buildList {
    add(Access("Location", "Weather, nearby places and \"how far is...\" for where you are.", listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
    add(Access("Contacts", "Find a number when you say \"text Mom\" or \"call John\".", listOf(Manifest.permission.READ_CONTACTS)))
    add(Access("Text messages", "Send texts you OK first.", listOf(Manifest.permission.SEND_SMS)))
    add(Access("Phone calls", "Place calls you OK first, and pick SIM 1 or SIM 2.", listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)))
    add(Access("Calendar", "Read and add events.", listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)))
    add(Access("Microphone", "Voice mode and talking to $name.", listOf(Manifest.permission.RECORD_AUDIO)))
    add(Access("Camera", "Show $name things in voice mode and take photos for chat.", listOf(Manifest.permission.CAMERA)))
    if (Build.VERSION.SDK_INT >= 33) add(Access("Notifications", "Tell you when a background task or your PC finishes.", listOf(Manifest.permission.POST_NOTIFICATIONS)))
}

private fun granted(c: Context, a: Access) = a.perms.any { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun PermissionsPage(g: Graph, back: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val list = remember { accessList(g.settings.current.persona.name) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    // Coming back from Android's settings screen updates the list.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    fun openAppSettings() = context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    Page("Permissions", back) {
        val t = tick // read so the list redraws after a change
        val missing = list.filter { t >= 0 && !granted(context, it) }
        Muted("Allow what you want ${g.settings.current.persona.name} to use. Each one is only used when you ask for something that needs it, and texts, calls and orders still ask you first.")
        if (missing.isNotEmpty()) Button({ ask.launch(missing.flatMap { it.perms }.toTypedArray()) }) { Text("Allow all (${missing.size})") }
        else Muted("Everything is allowed.")
        list.forEach { a ->
            val on = t >= 0 && granted(context, a)
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.title, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(a.why, color = p.muted, fontSize = 13.sp)
                    }
                    if (on) Text("Allowed", color = p.accent, fontSize = 13.sp)
                    else OutlinedButton({ ask.launch(a.perms.toTypedArray()) }) { Text("Allow") }
                }
            }
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your phone's files", color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Lets ${g.settings.current.persona.name} look through Download, DCIM, Documents and more, and organize or clean up when you ask. Moves and deletes always ask first, and deleted files sit in a Trash for 30 days.", color = p.muted, fontSize = 13.sp)
                }
                if (t >= 0 && dev.dagent.phone.AllFiles.granted(context)) Text("Allowed", color = p.accent, fontSize = 13.sp)
                else OutlinedButton({ dev.dagent.phone.AllFiles.open(context) }) { Text("Allow") }
            }
        }
        Muted("If Allow does nothing, Android has stopped asking. Turn it on in the app's settings instead.")
        OutlinedButton({ openAppSettings() }) { Text("Open Android app settings") }
    }
}
