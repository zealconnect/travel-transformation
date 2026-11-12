package com.example.voiceagent

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Ears and mouth, both built into Android and free:
 * SpeechRecognizer for listening, TextToSpeech for talking.
 */
class Voice(private val ctx: Context, private val lang: () -> String) {

    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    /** Last SpeechRecognizer error code, so we can explain problems like a missing mic permission. */
    var lastError: Int = 0
        private set

    fun init() {
        tts = TextToSpeech(ctx.applicationContext) { status -> ttsReady.complete(status == TextToSpeech.SUCCESS) }
    }

    private fun chooseVoice(t: TextToSpeech, text: String) {
        val marathi = lang().startsWith("mr") || Text.hasDevanagari(text)
        val tags = if (marathi) listOf("mr-IN", "hi-IN", "en-IN") else listOf("en-IN", "en-US")
        for (tag in tags) {
            val r = t.setLanguage(Locale.forLanguageTag(tag))
            if (r >= TextToSpeech.LANG_AVAILABLE) return
        }
    }

    /** Say something and wait until it has been said. */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        val ok = withTimeoutOrNull(5000) { ttsReady.await() } ?: false
        val t = tts ?: return
        if (!ok) return
        chooseVoice(t, text)
        val id = UUID.randomUUID().toString()
        withTimeoutOrNull(5000L + text.length * 120L) {
            suspendCancellableCoroutine { cont ->
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(u: String?) {}
                    override fun onDone(u: String?) { if (u == id && cont.isActive) cont.resume(Unit) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(u: String?) { if (u == id && cont.isActive) cont.resume(Unit) }
                    override fun onError(u: String?, errorCode: Int) { if (u == id && cont.isActive) cont.resume(Unit) }
                    override fun onStop(u: String?, interrupted: Boolean) { if (u == id && cont.isActive) cont.resume(Unit) }
                })
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                cont.invokeOnCancellation { t.stop() }
            }
        }
    }

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            main.postDelayed({ tg.release() }, 400)
        } catch (_: Exception) {}
    }

    /** Listen for one sentence. Returns null if nothing was understood. */
    suspend fun listen(): String? = withContext(Dispatchers.Main) {
        lastError = 0
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            lastError = SpeechRecognizer.ERROR_CLIENT
            return@withContext null
        }
        beep()
        delay(180)
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try {
            withTimeoutOrNull(25_000) {
                suspendCancellableCoroutine<String?> { cont ->
                    r.setRecognitionListener(object : RecognitionListener {
                        override fun onResults(b: Bundle?) {
                            val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (cont.isActive) cont.resume(text)
                        }
                        override fun onError(error: Int) {
                            lastError = error
                            if (cont.isActive) cont.resume(null)
                        }
                        override fun onReadyForSpeech(p: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(v: Float) {}
                        override fun onBufferReceived(b: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(b: Bundle?) {}
                        override fun onEvent(t: Int, b: Bundle?) {}
                    })
                    r.startListening(intent)
                    cont.invokeOnCancellation { main.post { try { r.cancel() } catch (_: Exception) {} } }
                }
            }
        } finally {
            try { r.destroy() } catch (_: Exception) {}
            if (recognizer === r) recognizer = null
        }
    }

    fun stopAll() {
        tts?.stop()
        recognizer?.let { r -> main.post { try { r.cancel() } catch (_: Exception) {} } }
    }

    fun shutdown() {
        stopAll()
        tts?.shutdown()
        tts = null
    }
}
