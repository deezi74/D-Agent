package dev.dagent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.dagent.Graph
import dev.dagent.character.Mood
import dev.dagent.core.llm.Role
import dev.dagent.phone.Listener
import dev.dagent.phone.Speaker
import kotlinx.coroutines.launch
import java.io.File

private enum class Phase { LISTENING, THINKING, SPEAKING, PAUSED }

/**
 * Hands-free talk: listens, sends what it heard, reads the reply aloud, then listens again.
 * Tap the face to interrupt. With the camera on, each question goes with a photo of what the
 * camera sees, so the agent can answer about it.
 */
@Composable
fun VoiceMode(g: Graph, onClose: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val state by g.runner.state.collectAsState()
    val prefs by g.settings.state.collectAsState()
    val look by g.character.active.collectAsState()
    val speaker = g.speaker
    val listener = remember { Listener(context) }
    val heard by listener.state.collectAsState()
    var phase by remember { mutableStateOf(Phase.PAUSED) }
    var sentAt by remember { mutableIntStateOf(-1) }
    var quietTries by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var lastReply by remember { mutableStateOf("") }
    var cameraOn by remember { mutableStateOf(false) }
    var front by remember { mutableStateOf(false) }
    val camera = remember { LiveCameraState() }
    val scope = rememberCoroutineScope()
    val name = prefs.persona.name

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) cameraOn = true else note = "Camera permission is needed for the live camera."
    }
    fun toggleCamera() {
        if (cameraOn) cameraOn = false
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cameraOn = true
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    fun listen() {
        speaker.stop()
        note = null
        phase = Phase.LISTENING
        listener.start()
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listen() else note = "$name needs the microphone for voice mode."
    }

    fun begin() {
        if (!listener.available()) {
            note = "No speech recognizer on this phone. Install or turn on Google speech services."
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(Unit) { begin() }
    DisposableEffect(Unit) {
        onDispose {
            listener.stop()
            speaker.stop()
        }
    }
    BackHandler { onClose() }

    LaunchedEffect(heard) {
        when (val h = heard) {
            is Listener.State.Heard -> {
                quietTries = 0
                listener.stop()
                phase = Phase.THINKING
                sentAt = state.session.messages.size
                if (cameraOn) {
                    scope.launch {
                        val frame = camera.snap(File(context.cacheDir, "live"))
                        sentAt = g.runner.state.value.session.messages.size
                        g.runner.send(h.text, listOfNotNull(frame?.let { Uri.fromFile(it) }))
                    }
                } else {
                    g.runner.send(h.text)
                }
            }
            is Listener.State.Failed -> if (phase == Phase.LISTENING) {
                if (h.silent && quietTries < 2) {
                    quietTries++
                    listen()
                } else {
                    quietTries = 0
                    phase = Phase.PAUSED
                    note = h.message + " Tap the mic to talk."
                }
            }
            else -> Unit
        }
    }

    // When the reply is done, read it out and listen again.
    LaunchedEffect(state.running, state.session.messages.size) {
        if (phase != Phase.THINKING || state.running || sentAt < 0) return@LaunchedEffect
        if (state.error.isNotBlank()) {
            phase = Phase.PAUSED
            note = state.error.take(200)
            sentAt = -1
            return@LaunchedEffect
        }
        val reply = state.session.messages.drop(sentAt).lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }?.text
        if (reply.isNullOrBlank()) return@LaunchedEffect
        lastReply = reply
        sentAt = -1
        phase = Phase.SPEAKING
        speaker.speak(reply) { scope.launch { if (phase == Phase.SPEAKING) listen() } }
    }

    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, if (phase == Phase.LISTENING || phase == Phase.SPEAKING) 1.07f else 1f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scale",
    )

    Column(
        Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Voice", color = p.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            CircleButton(Icons.Default.Close, "Close voice mode", p.surface2, p.text, 44.dp) { onClose() }
        }
        if (cameraOn) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().weight(3f).clip(RoundedCornerShape(24.dp))) {
                LiveCamera(camera, front, Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    CircleButton(Icons.Default.Cameraswitch, "Switch camera", Color.Black.copy(alpha = 0.5f), Color.White, 44.dp) { front = !front }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.scale(pulse.value).clip(CircleShape).clickable { if (phase == Phase.SPEAKING || phase == Phase.PAUSED) listen() },
            contentAlignment = Alignment.Center,
        ) {
            AgentAvatar(
                name, prefs.avatarPath, if (cameraOn) 96.dp else 180.dp,
                busy = phase == Phase.THINKING, look = look,
                mood = when (phase) {
                    Phase.THINKING -> Mood.WORKING
                    Phase.SPEAKING -> Mood.HAPPY
                    Phase.LISTENING -> Mood.WAITING
                    Phase.PAUSED -> if (note != null) Mood.ERROR else Mood.IDLE
                },
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            when (phase) {
                Phase.LISTENING -> "Listening..."
                Phase.THINKING -> state.live.values.lastOrNull { it.running }?.title ?: "Thinking..."
                Phase.SPEAKING -> "Speaking · tap to interrupt"
                Phase.PAUSED -> "Paused"
            },
            color = if (phase == Phase.PAUSED) p.muted else p.accent, fontSize = 16.sp,
        )
        Spacer(Modifier.height(10.dp))
        val caption = when {
            note != null -> note!!
            phase == Phase.LISTENING -> (heard as? Listener.State.Listening)?.partial.orEmpty()
            phase == Phase.SPEAKING -> lastReply
            else -> ""
        }
        Text(Speaker.forSpeech(caption).take(400), color = p.text, fontSize = 17.sp, textAlign = TextAlign.Center, maxLines = 8)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            CircleButton(
                if (cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                if (cameraOn) "Turn camera off" else "Show it with the camera",
                if (cameraOn) p.accent else p.surface2, if (cameraOn) Color(0xFF1A0E08) else p.text, 56.dp,
            ) { toggleCamera() }
            CircleButton(
                if (phase == Phase.LISTENING) Icons.Default.Mic else Icons.Default.MicOff, "Talk",
                if (phase == Phase.LISTENING) p.accent else p.surface2, if (phase == Phase.LISTENING) Color(0xFF1A0E08) else p.text, 76.dp,
            ) {
                if (phase == Phase.LISTENING) { listener.stop(); phase = Phase.PAUSED } else begin()
            }
            Spacer(Modifier.size(56.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (cameraOn) "Live camera: each time you talk it sees what you show it (needs a model that can see images)."
            else "Hands-free: it listens again after each answer. Voice: " + (if (g.cloudVoice.active()) prefs.voiceName else "phone") + ".",
            color = p.muted, fontSize = 12.sp, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CircleButton(icon: ImageVector, label: String, bg: Color, tint: Color, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint)
    }
}
