package dev.dagent.phone

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.dagent.core.voice.VoicePackStore
import dev.dagent.core.voice.VoicePacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Listening that runs fully on the phone: the Silero detector finds where you start and stop
 * talking, then Moonshine or Whisper writes it down. No Google services or internet needed.
 *
 * In "talk over" mode it listens while the reply is playing (with the phone's echo canceller on)
 * and calls [onInterrupt] as soon as you've clearly started talking, then keeps that same
 * recording as your next question, so the first words aren't lost.
 */
class OnPhoneEars(private val packs: VoicePackStore) {
    private var recognizer: OfflineRecognizer? = null
    private var recognizerId = ""
    private var vad: Vad? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<Listener.State>(Listener.State.Idle)
    val state: StateFlow<Listener.State> = _state.asStateFlow()

    fun ready(packId: String) = packs.installed(packId) && packs.installed(VoicePacks.SILERO)

    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private fun recognizerFor(id: String): OfflineRecognizer {
        if (recognizerId == id) recognizer?.let { return it }
        recognizer?.release()
        recognizer = null
        val pack = VoicePacks.byId(id) ?: error("Unknown listening model $id")
        val dir = packs.dir(pack)
        fun f(name: String) = File(dir, name).absolutePath
        val model = if (id.startsWith("moonshine")) OfflineModelConfig(
            moonshine = OfflineMoonshineModelConfig(
                preprocessor = f("preprocess.onnx"), encoder = f("encode.int8.onnx"),
                uncachedDecoder = f("uncached_decode.int8.onnx"), cachedDecoder = f("cached_decode.int8.onnx"),
            ),
            tokens = f("tokens.txt"), numThreads = threads(),
        ) else {
            val size = if (id == "whisper-small") "small" else "base"
            OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = f("$size.en-encoder.int8.onnx"), decoder = f("$size.en-decoder.int8.onnx"), language = "en"),
                tokens = f("$size.en-tokens.txt"), numThreads = threads(),
            )
        }
        return OfflineRecognizer(null, OfflineRecognizerConfig(featConfig = FeatureConfig(16000, 80), modelConfig = model))
            .also { recognizer = it; recognizerId = id }
    }

    private fun detector(): Vad = vad ?: Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = File(packs.dir(VoicePacks.SILERO), "silero_vad.onnx").absolutePath,
                threshold = 0.5f, minSilenceDuration = 0.7f, minSpeechDuration = 0.2f, windowSize = 512, maxSpeechDuration = 25f,
            ),
            sampleRate = RATE, numThreads = 1,
        ),
    ).also { vad = it }

    /** Loads the models ahead of time so the first turn doesn't wait. */
    fun warm(packId: String) {
        if (!ready(packId)) return
        scope.launch { runCatching { recognizerFor(packId); detector() } }
    }

    /**
     * Starts one turn. With [talkOver] it waits for you to clearly start speaking over the reply,
     * calls [onInterrupt], then records the rest of what you say.
     */
    @SuppressLint("MissingPermission")
    fun start(packId: String, talkOver: Boolean = false, onInterrupt: () -> Unit = {}) {
        stop()
        _state.value = Listener.State.Listening("")
        job = scope.launch {
            var record: AudioRecord? = null
            val effects = mutableListOf<android.media.audiofx.AudioEffect>()
            try {
                val rec = recognizerFor(packId)
                val v = detector().apply { reset() }
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val source = if (talkOver) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
                record = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
                if (record.state != AudioRecord.STATE_INITIALIZED) error("Couldn't open the microphone.")
                if (talkOver) {
                    if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.let { it.enabled = true; effects += it }
                    if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.let { it.enabled = true; effects += it }
                }
                record.startRecording()
                val buf = ShortArray(WINDOW)
                val chunk = FloatArray(WINDOW)
                var heardMs = 0
                var quietMs = 0
                var interrupted = !talkOver
                val started = System.currentTimeMillis()
                while (isActive) {
                    val n = record.read(buf, 0, WINDOW)
                    if (n <= 0) continue
                    for (i in 0 until n) chunk[i] = buf[i] / 32768f
                    v.acceptWaveform(if (n == WINDOW) chunk else chunk.copyOf(n))
                    val ms = n * 1000 / RATE
                    if (v.isSpeechDetected()) { heardMs += ms; quietMs = 0 } else { quietMs += ms; if (!interrupted) heardMs = 0 }
                    if (!interrupted && heardMs >= 350) {
                        interrupted = true
                        withContext(Dispatchers.Main) { onInterrupt() }
                    }
                    if (interrupted && heardMs > 0) _state.value = Listener.State.Listening("…")
                    if (!v.empty()) {
                        val seg = v.front()
                        v.pop()
                        if (!interrupted) continue // speech that ended before it counted as talking over
                        _state.value = Listener.State.Listening("Writing it down…")
                        val stream = rec.createStream()
                        stream.acceptWaveform(seg.samples, RATE)
                        rec.decode(stream)
                        val text = rec.getResult(stream).text.trim()
                        stream.release()
                        if (text.isNotBlank() && !NOISE.matches(text)) {
                            _state.value = Listener.State.Heard(text)
                            return@launch
                        }
                        heardMs = 0
                        _state.value = Listener.State.Listening("")
                    }
                    if (interrupted && heardMs == 0 && quietMs > 8000 && !talkOver) {
                        _state.value = Listener.State.Failed("I didn't hear anything.", silent = true)
                        return@launch
                    }
                    if (talkOver && !interrupted && System.currentTimeMillis() - started > 10 * 60_000) return@launch
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = Listener.State.Failed("On-phone listening failed: ${e.message ?: e.javaClass.simpleName}", silent = false)
            } finally {
                effects.forEach { runCatching { it.release() } }
                record?.let { runCatching { it.stop() }; runCatching { it.release() } }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = Listener.State.Idle
    }

    fun release() {
        stop()
        recognizer?.release(); recognizer = null; recognizerId = ""
        vad?.release(); vad = null
    }

    companion object {
        const val RATE = 16000
        private const val WINDOW = 512
        /** What the models write for coughs, music or silence. */
        private val NOISE = Regex("^[\\s.,!?…-]*$|^\\[[^\\]]*]$|^\\([^)]*\\)$", RegexOption.IGNORE_CASE)
    }
}
