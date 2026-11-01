package dev.dagent.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import dev.dagent.Graph
import dev.dagent.ui.settings.SettingsHost

/** Where the person is: the chat, or a settings page. */
sealed interface Screen {
    data object Chat : Screen
    data class Settings(val page: String = "home") : Screen
    data object Voice : Screen
    data object Discover : Screen
}

@Composable
fun AppRoot(g: Graph, shared: MutableState<Pair<String, Uri?>?>) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Chat) }
    val prefs by g.settings.state.collectAsState()
    val confirm by g.interactions.confirm.collectAsState()

    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1) { back() }

    if (!prefs.onboarded) {
        Onboarding(g) { g.settings.update { it.copy(onboarded = true) } }
        return
    }

    when (val top = stack.last()) {
        Screen.Chat -> ChatScreen(g, shared, openSettings = { go(Screen.Settings(it)) }, openVoice = { go(Screen.Voice) }, openDiscover = { go(Screen.Discover) })
        Screen.Voice -> VoiceMode(g) { back() }
        Screen.Discover -> DiscoverScreen(g, back = { back() }, startChat = { prompt -> g.runner.newChat(); shared.value = prompt to null; back() })
        is Screen.Settings -> SettingsHost(g, top.page, open = { go(Screen.Settings(it)) }, back = { back() })
    }

    // After a crash, show what happened once so it can be copied and sent.
    var crash by remember { mutableStateOf(runCatching { java.io.File(g.context.filesDir, "last-crash.txt").takeIf { it.isFile }?.readText() }.getOrNull()) }
    crash?.let { text ->
        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        fun dismiss() { runCatching { java.io.File(g.context.filesDir, "last-crash.txt").delete() }; crash = null }
        AlertDialog(
            onDismissRequest = { dismiss() },
            title = { Text("D-Agent closed unexpectedly") },
            text = {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text("Copy this and send it so it can be fixed:", fontSize = 13.sp)
                    Text(text.take(3000), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(text)); dismiss() }) { Text("Copy and close") } },
            dismissButton = { TextButton({ dismiss() }) { Text("Close") } },
        )
    }

    confirm?.let { req ->
        AlertDialog(
            onDismissRequest = { g.interactions.answerConfirm(req, false) },
            title = { Text(req.title) },
            text = { Text(req.detail) },
            confirmButton = { TextButton(onClick = { g.interactions.answerConfirm(req, true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { g.interactions.answerConfirm(req, false) }) { Text("Don't") } },
        )
    }
}
