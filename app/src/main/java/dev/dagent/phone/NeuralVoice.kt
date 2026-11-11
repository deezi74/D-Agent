package dev.dagent.phone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dev.dagent.core.voice.VoicePackStore
import dev.dagent.core.voice.VoicePacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Human-sounding voices that run on the phone with no internet (Kokoro, Kitten) through
 * sherpa-onnx. The engine stays loaded between replies so the next answer starts fast.
 */
class NeuralVoice(val packs: VoicePackStore) {
    private val lock = Mutex()
    private var tts: OfflineTts? = null
    private var loadedId = ""

    fun handles(provider: String): Boolean =
        VoicePacks.byId(provider)?.kind == VoicePacks.Kind.TALK && packs.installed(provider)

    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private fun engine(id: String): OfflineTts {
        if (loadedId == id) tts?.let { return it }
        release()
        val pack = VoicePacks.byId(id) ?: error("Unknown voice $id")
        val dir = packs.dir(pack)
        fun f(name: String) = File(dir, name).absolutePath
        val model = when (id) {
            "kokoro" -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = f("model.int8.onnx"), voices = f("voices.bin"), tokens = f("tokens.txt"),
                    dataDir = f("espeak-ng-data"), lexicon = f("lexicon-us-en.txt") + "," + f("lexicon-gb-en.txt"),
                ),
                numThreads = threads(),
            )
            else -> OfflineTtsModelConfig(
                kitten = OfflineTtsKittenModelConfig(
                    model = f("model.fp16.onnx"), voices = f("voices.bin"), tokens = f("tokens.txt"), dataDir = f("espeak-ng-data"),
                ),
                numThreads = threads(),
            )
        }
        return OfflineTts(null, OfflineTtsConfig(model = model, maxNumSentences = 1)).also { tts = it; loadedId = id }
    }

    /** Makes the audio for one sentence (runs on a background thread). */
    suspend fun synth(id: String, voice: Int, speed: Float, text: String): Pair<FloatArray, Int> = withContext(Dispatchers.Default) {
        lock.withLock {
            val e = engine(id)
            val sid = voice.coerceIn(0, (e.numSpeakers() - 1).coerceAtLeast(0))
            val audio = e.generate(text, sid, speed)
            audio.samples to audio.sampleRate
        }
    }

    /** Plays float PCM to the end (or until cancelled). */
    suspend fun play(samples: FloatArray, rate: Int, usage: Int = AudioAttributes.USAGE_ASSISTANT) = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate)) // about a quarter second
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        current = track
        try {
            track.play()
            var i = 0
            val step = rate / 10
            while (i < samples.size) {
                coroutineContext.ensureActive()
                val n = minOf(step, samples.size - i)
                val w = track.write(samples, i, n, AudioTrack.WRITE_BLOCKING)
                if (w <= 0) break
                i += w
            }
            // Let the buffer drain before the next sentence or the mic opens again.
            val total = samples.size
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING && track.playbackHeadPosition < total) {
                coroutineContext.ensureActive()
                kotlinx.coroutines.delay(20)
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            if (current === track) current = null
        }
    }

    @Volatile private var current: AudioTrack? = null

    fun stopPlayback() {
        current?.let { runCatching { it.pause(); it.flush() } }
    }

    fun release() {
        tts?.release()
        tts = null
        loadedId = ""
    }
}

/** Voice pack downloads that keep going when you leave the Settings page. */
class VoiceDownloads(private val store: VoicePackStore) {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, kotlinx.coroutines.Job>()
    /** Pack id to a status line ("" when not downloading). Errors start with "!". */
    val status = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    /** Bumps whenever a pack is installed or deleted so the UI re-checks. */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

    private fun set(id: String, text: String) { status.value = status.value + (id to text) }

    fun busy(id: String) = jobs[id]?.isActive == true

    fun start(pack: VoicePacks.Pack, then: () -> Unit = {}) {
        if (busy(pack.id)) return
        jobs[pack.id] = scope.launch {
            try {
                set(pack.id, "Starting…")
                // Listening packs need the small voice detector too.
                if (pack.kind == VoicePacks.Kind.LISTEN && !store.installed(VoicePacks.SILERO)) store.install(VoicePacks.SILERO)
                store.install(pack) { done, total, _ -> set(pack.id, "Downloading… $done of $total files") }
                set(pack.id, "")
                changes.value++
                kotlinx.coroutines.withContext(Dispatchers.Main) { then() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                set(pack.id, "")
                throw e
            } catch (e: Exception) {
                set(pack.id, "!" + (e.message ?: "Download failed") + " Tap Download to resume.")
            }
        }
    }

    fun cancel(id: String) { jobs[id]?.cancel() }

    fun delete(pack: VoicePacks.Pack) {
        cancel(pack.id)
        store.delete(pack)
        changes.value++
    }
}
