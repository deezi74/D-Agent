package dev.dagent.phone

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads text aloud with the phone's own voice (US English). */
class Speaker(context: Context) {
    @Volatile private var ready = false
    private val pending = mutableListOf<String>()
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = Locale.US
                val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
                queued.forEach { say(it) }
            }
        }
    }

    fun say(text: String) {
        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), " (code) ")
            .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("[*_#>`|]"), "")
            .replace(Regex("https?://\\S+"), "")
            .trim()
        if (clean.isEmpty()) return
        if (!ready) { synchronized(pending) { pending += clean }; return }
        clean.chunked(3500).forEachIndexed { i, part ->
            tts.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "dagent-$i")
        }
    }

    fun stop() { if (ready) tts.stop() }
}
