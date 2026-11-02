package dev.dagent.phone

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Speaks replies. With a cloud voice picked (Settings > Voice) it streams natural
 * MP3 speech sentence by sentence, fetching the next part while the current one
 * plays; otherwise, or if the cloud voice fails, it uses the phone's TTS engine.
 */
class Speaker(
    context: Context,
    private val cloud: CloudVoice? = null,
    private val neural: NeuralVoice? = null,
    /** The on-phone voice to use right now, or null for cloud/phone voices. */
    private val pick: () -> Pick? = { null },
) {
    /** An on-phone voice pack, the speaker number inside it and the talking speed. */
    data class Pick(val pack: String, val voice: Int, val speed: Float)

    /** Reads [text] aloud (used by read-aloud, the phone tool and the speaker button). */
    fun say(text: String) { scope.launch { speak(text) } }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        android.util.Log.w("Speaker", "speech failed", e)
        _speaking.value = false
    })
    private var cloudJob: Job? = null
    private var player: android.media.MediaPlayer? = null
    /** Why the natural voice last failed ("" when it worked), so Settings can show it. */
    private val _voiceError = MutableStateFlow("")
    val voiceError: StateFlow<String> = _voiceError.asStateFlow()
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private var ready = false
    private var pending: Pair<String, () -> Unit>? = null
    private var onDone: (() -> Unit)? = null
    private var lastId = ""

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            pending?.let { (text, done) -> speak(text, done) }
            pending = null
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastId) finish()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                _speaking.value = false
            }
        })
    }

    var rate: Float = 1.0f
        set(value) {
            field = value
            tts.setSpeechRate(value)
        }

    var pitch: Float = 1.0f
        set(value) {
            field = value
            tts.setPitch(value)
        }

    private fun finish() {
        _speaking.value = false
        val cb = onDone
        onDone = null
        cb?.invoke()
    }

    fun speak(text: String, done: () -> Unit = {}) {
        val clean = forSpeech(text)
        if (clean.isBlank()) {
            done()
            return
        }
        val on = pick()
        if (neural != null && on != null) {
            speakNeural(on, clean, done)
            return
        }
        if (cloud != null && cloud.active()) {
            speakCloud(clean, done)
            return
        }
        speakPhone(clean, done)
    }

    /** On-phone voice: makes the next sentence while the current one plays. */
    private fun speakNeural(on: Pick, clean: String, done: () -> Unit) {
        stop()
        val parts = sentences(clean, first = 90, rest = 260)
        cloudJob = scope.launch {
            _speaking.value = true
            var spoken = 0
            try {
                var next = async { neural!!.synth(on.pack, on.voice, on.speed, parts[0]) }
                for (i in parts.indices) {
                    val (pcm, rate) = next.await()
                    if (i + 1 < parts.size) next = async { neural!!.synth(on.pack, on.voice, on.speed, parts[i + 1]) }
                    neural!!.play(pcm, rate)
                    spoken = i + 1
                }
                _speaking.value = false
                _voiceError.value = ""
                done()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _speaking.value = false
                throw e
            } catch (e: Throwable) {
                _voiceError.value = e.message ?: "The on-phone voice failed"
                val rest = parts.drop(spoken).joinToString(" ")
                if (rest.isBlank()) { _speaking.value = false; done() } else speakPhone(rest, done)
            }
        }
    }

    private fun speakCloud(clean: String, done: () -> Unit) {
        stop()
        val parts = sentences(clean)
        cloudJob = scope.launch {
            _speaking.value = true
            var spoken = 0
            try {
                var next = async(Dispatchers.IO) { cloud!!.synthesize(parts[0]) }
                for (i in parts.indices) {
                    val file = next.await()
                    if (i + 1 < parts.size) next = async(Dispatchers.IO) { cloud!!.synthesize(parts[i + 1]) }
                    play(file)
                    file.delete()
                    spoken = i + 1
                }
                _speaking.value = false
                _voiceError.value = ""
                done()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _speaking.value = false
                throw e
            } catch (e: Exception) {
                // Cloud voice failed (no network, bad key): finish with the phone's voice.
                _voiceError.value = e.message ?: "The natural voice failed"
                val rest = parts.drop(spoken).joinToString(" ")
                if (rest.isBlank()) {
                    _speaking.value = false
                    done()
                } else {
                    speakPhone(rest, done)
                }
            }
        }
    }

    private suspend fun play(file: java.io.File) = suspendCancellableCoroutine { cont ->
        val mp = android.media.MediaPlayer()
        player = mp
        var released = false
        cont.invokeOnCancellation { released = true; runCatching { mp.stop() }; runCatching { mp.release() }; if (player === mp) player = null }
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
            mp.setOnErrorListener { m, what, _ ->
                m.release()
                if (player === m) player = null
                if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("playback error $what")))
                true
            }
            mp.setOnPreparedListener { if (!released) runCatching { it.start() } }
            mp.prepareAsync()
        } catch (e: Exception) {
            mp.release()
            if (player === mp) player = null
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }
    }

    private fun speakPhone(clean: String, done: () -> Unit) {
        if (!ready) {
            pending = clean to done
            return
        }
        tts.stop()
        onDone = done
        tts.language = Locale.US
        val chunks = chunk(clean)
        chunks.forEachIndexed { i, part ->
            val id = "u${System.nanoTime()}_$i"
            if (i == chunks.lastIndex) lastId = id
            tts.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    fun stop() {
        onDone = null
        pending = null
        cloudJob?.cancel()
        cloudJob = null
        neural?.stopPlayback()
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        tts.stop()
        _speaking.value = false
    }

    fun shutdown() {
        stop()
        scope.cancel()
        tts.shutdown()
    }

    companion object {
        /** Drops markdown, links and code so replies sound natural. */
        fun forSpeech(text: String): String = text
            .replace(Regex("```[\\s\\S]*?```"), " (code shown on screen) ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("https?://\\S+"), "the link on screen")
            .replace(Regex("[*_#>|~]+"), "")
            .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        /**
         * Splits text into parts for the cloud voice: a short first part so speech
         * starts fast, then about 350 characters each, cut at sentence ends.
         */
        fun sentences(text: String, first: Int = 140, rest: Int = 350): List<String> {
            val pieces = Regex("(?<=[.!?])\\s+").split(text).filter { it.isNotBlank() }
            val out = ArrayList<String>()
            val cur = StringBuilder()
            for (p in pieces) {
                val limit = if (out.isEmpty()) first else rest
                if (cur.isNotEmpty() && cur.length + p.length + 1 > limit) {
                    out += cur.toString()
                    cur.clear()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(p)
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out.flatMap { if (it.length > 3500) chunk(it) else listOf(it) }.ifEmpty { listOf(text) }
        }

        private fun chunk(text: String, max: Int = 3500): List<String> {
            if (text.length <= max) return listOf(text)
            val out = ArrayList<String>()
            var rest = text
            while (rest.length > max) {
                val cut = rest.lastIndexOf(". ", max).takeIf { it > max / 2 } ?: max
                out += rest.substring(0, cut + 1)
                rest = rest.substring(cut + 1).trimStart()
            }
            if (rest.isNotBlank()) out += rest
            return out
        }
    }
}

