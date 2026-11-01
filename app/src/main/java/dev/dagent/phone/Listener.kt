package dev.dagent.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** On-device speech recognition for hands-free voice mode (needs RECORD_AUDIO). Use from the main thread. */
class Listener(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data class Listening(val partial: String) : State
        data class Heard(val text: String) : State
        data class Failed(val message: String, val silent: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var recognizer: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = State.Listening("")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _state.value = State.Listening(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _state.value = if (text.isBlank()) State.Failed("I didn't catch that.", silent = true) else State.Heard(text)
            }

            override fun onError(error: Int) {
                val silent = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                _state.value = State.Failed(
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection on this phone."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy. Tap to try again."
                        else -> if (silent) "I didn't hear anything." else "Speech recognition error $error."
                    },
                    silent
                )
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        )
    }

    fun stop() {
        recognizer?.run {
            runCatching { cancel() }
            runCatching { destroy() }
        }
        recognizer = null
        _state.value = State.Idle
    }
}
