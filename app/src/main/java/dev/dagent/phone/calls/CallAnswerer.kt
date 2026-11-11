package dev.dagent.phone.calls

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.dagent.DAgentApp
import dev.dagent.Graph
import dev.dagent.R
import dev.dagent.core.calls.CallAnswering
import dev.dagent.core.calls.CallRules
import dev.dagent.core.llm.Msg
import dev.dagent.core.voice.VoicePacks
import dev.dagent.data.ChatSession
import dev.dagent.phone.Listener
import dev.dagent.phone.PhoneActions
import dev.dagent.phone.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

/**
 * A tiny accessibility service that only exists so Android lets D-Agent use the microphone while a
 * phone call is active (Android gives call-time mic access to accessibility services). It reads no
 * screen content and performs no actions.
 */
class CallHelperService : AccessibilityService() {
    override fun onServiceConnected() { running = true }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean { running = false; return super.onUnbind(intent) }
    override fun onDestroy() { running = false; super.onDestroy() }

    companion object {
        @Volatile var running = false
            private set

        /** True when the person turned the helper on in Android's accessibility settings. */
        fun enabled(context: Context): Boolean {
            if (running) return true
            val list = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(context, CallHelperService::class.java)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun openSettings(context: Context) = context.startActivity(
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Hears the phone ring (PHONE_STATE broadcast) and hands each change to [CallAnswerer]. */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val app = context.applicationContext as? DAgentApp ?: return
        CallAnswerer.onState(app.graph, state, number)
    }
}

/**
 * Speakerphone call answering: when an allowed caller rings and the person doesn't pick up, it answers,
 * turns on the speaker, says it's an AI assistant, then listens on the mic (on-phone Moonshine/Whisper),
 * asks the AI for a short reply and speaks it, until the caller is done. The transcript is saved as a chat
 * and shown in a notification.
 */
object CallAnswerer {
    private const val TAG = "CallAnswerer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var phoneState = TelephonyManager.EXTRA_STATE_IDLE
    private var ringNumber: String? = null
    private var armJob: Job? = null
    private var callJob: Job? = null
    private var answeredByUs = false
    @Volatile private var hangingUp = false

    /** What's missing before calls can be answered, in plain words (empty = ready). */
    fun missing(g: Graph): List<String> = buildList {
        val c = g.context
        fun has(p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) add("Allow answering calls")
        if (!has(Manifest.permission.READ_PHONE_STATE) || !has(Manifest.permission.READ_CALL_LOG)) add("Allow seeing who's calling")
        if (!has(Manifest.permission.RECORD_AUDIO)) add("Allow the microphone")
        if (!CallHelperService.enabled(c)) add("Turn on the D-Agent call helper in Accessibility")
        if (listenPack(g) == null) add("Download an on-phone listening model (Settings > Voice)")
        if (!g.settings.provider().ready) add("Set up an AI provider")
    }

    /** The on-phone listening model to use: the one picked in Voice settings, else any installed one. */
    fun listenPack(g: Graph): String? {
        val picked = g.settings.current.listenEngine
        if (picked != "android" && g.ears.ready(picked)) return picked
        return VoicePacks.listen.map { it.id }.firstOrNull { g.ears.ready(it) }
    }

    private fun rules(g: Graph): CallRules = g.connectors.callRules(g.settings.current.persona.name)

    fun onState(g: Graph, state: String, number: String?) {
        scope.launch {
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    if (!number.isNullOrBlank()) ringNumber = number
                    if (phoneState == TelephonyManager.EXTRA_STATE_RINGING && armJob != null) return@launch
                    phoneState = state
                    if (!rules(g).on) return@launch
                    armJob?.cancel()
                    armJob = scope.launch { arm(g) }
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    val was = phoneState
                    phoneState = state
                    if (was == TelephonyManager.EXTRA_STATE_RINGING && !answeredByUs) {
                        // The person answered it themselves (or it's an outgoing call): stay out of it.
                        armJob?.cancel(); armJob = null
                    }
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    phoneState = state
                    armJob?.cancel(); armJob = null
                    callJob?.cancel()
                    ringNumber = null
                    answeredByUs = false
                }
            }
        }
    }

    private suspend fun arm(g: Graph) {
        // Some phones send the ringing broadcast twice, once without the number; wait for both.
        delay(700)
        val number = ringNumber
        val rules = rules(g)
        val name = withContext(Dispatchers.IO) { number?.let { contactName(g.context, it) } }
        val decision = CallAnswering.decide(rules, number, inContacts = name != null)
        Log.i(TAG, "ringing ${number?.takeLast(4)}: ${decision.why}")
        if (!decision.answer) return
        val gaps = missing(g)
        if (gaps.isNotEmpty()) {
            notify(g, "Couldn't answer a call", "${g.settings.current.persona.name} needs: ${gaps.joinToString("; ")}. Open Settings > Answer my calls.")
            return
        }
        delay((rules.ringSeconds * 1000L - 700).coerceAtLeast(0))
        if (phoneState != TelephonyManager.EXTRA_STATE_RINGING) return
        val telecom = g.context.getSystemService(TelecomManager::class.java)
        answeredByUs = true
        try {
            @Suppress("DEPRECATION")
            telecom.acceptRingingCall()
        } catch (e: SecurityException) {
            answeredByUs = false
            notify(g, "Couldn't answer a call", "Android refused to answer: ${e.message}")
            return
        }
        // Wait for the line to open.
        withTimeoutOrNull(6000) { while (phoneState != TelephonyManager.EXTRA_STATE_OFFHOOK) delay(100) } ?: run { answeredByUs = false; return }
        callJob?.cancel()
        callJob = scope.launch { talk(g, rules, number, name) }
    }

    private suspend fun talk(g: Graph, rules: CallRules, number: String?, name: String?) {
        val am = g.context.getSystemService(AudioManager::class.java)
        val oldVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val voice = CallVoice(g)
        val turns = ArrayList<Pair<Boolean, String>>()
        val history = ArrayList<Msg>()
        val caller = name ?: number?.takeIf { it.isNotBlank() } ?: "a hidden number"
        val started = System.currentTimeMillis()
        val pack = listenPack(g)!!
        hangingUp = false
        g.ears.warm(pack)
        suspend fun say(text: String) {
            turns += false to text
            voice.speak(text)
        }
        try {
            runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0) }
            speakerOn(am)
            delay(1200) // the line takes a moment to connect
            speakerOn(am) // some dialers reset the route right after answering
            val greeting = CallAnswering.greeting(rules)
            history += Msg.assistant(greeting)
            say(greeting)
            val deadline = started + rules.maxMinutes * 60_000L
            var silences = 0
            while (System.currentTimeMillis() < deadline) {
                val heard = listen(g, pack)
                if (heard == null) {
                    silences++
                    if (silences >= 2) { say(CallAnswering.noAnswerBye(rules)); break }
                    say(CallAnswering.stillThere())
                    continue
                }
                silences = 0
                turns += true to heard
                history += Msg.user(heard)
                val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US))
                val raw = runCatching {
                    withTimeoutOrNull(30_000) { g.runner.callReply(CallAnswering.systemPrompt(rules, caller, now), history.toList()) }
                }.onFailure { Log.w(TAG, "reply failed", it) }.getOrNull()
                val reply = raw?.let { CallAnswering.parseReply(it) }
                if (reply == null || reply.say.isBlank()) { say(CallAnswering.troubleBye(rules)); break }
                history += Msg.assistant(reply.say)
                say(reply.say)
                if (reply.hangUp || CallAnswering.callerSaysBye(heard)) break
            }
            if (System.currentTimeMillis() >= deadline) say(CallAnswering.wrapUp(rules))
            delay(600)
            hangUp(g)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The caller hung up.
        } catch (e: Throwable) {
            Log.w(TAG, "call failed", e)
            turns += false to "(Stopped: ${e.message ?: e.javaClass.simpleName})"
            hangUp(g)
        } finally {
            withContext(NonCancellable) {
                g.ears.stop()
                voice.shutdown()
                runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, oldVolume, 0) }
                if (Build.VERSION.SDK_INT >= 31) runCatching { am.clearCommunicationDevice() }
                save(g, caller, number, turns, started)
            }
        }
    }

    /** Waits for one thing the caller says; null when they stayed quiet. */
    private suspend fun listen(g: Graph, pack: String): String? {
        delay(250) // let the speaker go quiet so the mic doesn't hear our own last word
        g.ears.start(pack)
        val result = withTimeoutOrNull(25_000) {
            g.ears.state.first { it is Listener.State.Heard || it is Listener.State.Failed }
        }
        g.ears.stop()
        return when (result) {
            is Listener.State.Heard -> result.text
            is Listener.State.Failed -> if (result.silent) null else throw IllegalStateException(result.message)
            else -> null
        }
    }

    private fun hangUp(g: Graph) {
        if (hangingUp) return
        hangingUp = true
        runCatching {
            @Suppress("DEPRECATION")
            g.context.getSystemService(TelecomManager::class.java).endCall()
        }.onFailure { Log.w(TAG, "endCall failed", it) }
    }

    @Suppress("DEPRECATION")
    private fun speakerOn(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            }
        }
        runCatching { am.isSpeakerphoneOn = true }
    }

    private suspend fun save(g: Graph, caller: String, number: String?, turns: List<Pair<Boolean, String>>, started: Long) {
        if (turns.isEmpty()) return
        val assistant = g.settings.current.persona.name
        val text = CallAnswering.transcript(turns, assistant)
        val secs = (System.currentTimeMillis() - started) / 1000
        val header = "📞 I answered a call from $caller" + (number?.takeIf { it.isNotBlank() && it != caller }?.let { " ($it)" } ?: "") + " for ${secs / 60}m ${secs % 60}s.\n\n"
        runCatching {
            g.chats.save(ChatSession(title = "📞 Call from $caller", messages = listOf(Msg.assistant(header + text))))
        }
        val heard = turns.filter { it.first }.joinToString(" ") { it.second }
        notify(g, "📞 Call from $caller", if (heard.isBlank()) "They didn't say anything." else "They said: $heard")
    }

    private fun notify(g: Graph, title: String, text: String) {
        runCatching {
            val n = NotificationCompat.Builder(g.context, PhoneActions.CHANNEL_AGENT)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text.take(200))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1500)))
                .setAutoCancel(true)
                .setContentIntent(PhoneActions.launchIntent(g.context))
                .build()
            NotificationManagerCompat.from(g.context).notify((System.currentTimeMillis() % 100000).toInt(), n)
        }
    }

    /** The saved contact's name for [number], or null. */
    fun contactName(context: Context, number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        if (CallAnswering.normalize(number).isEmpty()) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}

/**
 * The voice used on calls. It plays as call audio (USAGE_VOICE_COMMUNICATION) so it comes out of the
 * speaker during the call, where the phone's mic carries it to the caller. Uses the on-phone voice
 * picked in Settings > Voice, else the phone's own text-to-speech.
 */
private class CallVoice(private val g: Graph) {
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean? = null

    private fun neuralPick(): Pair<String, Int>? {
        val s = g.settings.current
        val pal = g.activePal()
        return when {
            pal != null && pal.voicePack.isNotBlank() && g.neuralVoice.handles(pal.voicePack) -> pal.voicePack to (pal.voiceId ?: 0)
            g.neuralVoice.handles(s.voiceProvider) -> s.voiceProvider to (s.voiceName.toIntOrNull() ?: 0)
            else -> null
        }
    }

    suspend fun speak(text: String) {
        val clean = Speaker.forSpeech(text)
        if (clean.isBlank()) return
        val pick = neuralPick()
        if (pick != null) {
            val ok = runCatching {
                for (part in Speaker.sentences(clean, first = 120, rest = 260)) {
                    val (pcm, rate) = g.neuralVoice.synth(pick.first, pick.second, g.settings.current.voiceSpeed, part)
                    g.neuralVoice.play(pcm, rate, AudioAttributes.USAGE_VOICE_COMMUNICATION)
                }
            }
            if (ok.isSuccess) return
            ok.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        phoneSpeak(clean)
    }

    private suspend fun engine(): TextToSpeech? {
        tts?.let { if (ttsReady == true) return it }
        val ready = suspendCancellableCoroutine<Boolean> { cont ->
            tts = TextToSpeech(g.context.applicationContext) { status -> if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS) }
        }
        ttsReady = ready
        return tts.takeIf { ready }?.apply {
            language = Locale.US
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        }
    }

    private suspend fun phoneSpeak(text: String) {
        val t = engine() ?: return
        suspendCancellableCoroutine<Unit> { cont ->
            val id = "call${System.nanoTime()}"
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            })
            cont.invokeOnCancellation { runCatching { t.stop() } }
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    fun shutdown() {
        tts?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        tts = null
    }
}
