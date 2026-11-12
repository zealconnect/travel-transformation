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
import kotlinx.coroutines.CancellableContinuation
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

    companion object {
        /** Google's speech recognizer (never our own pass-through one). */
        fun pickRecognizer(ctx: Context): android.content.ComponentName? {
            val all = ctx.packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
                .map { android.content.ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
                .filter { it.packageName != ctx.packageName && !it.packageName.startsWith("com.example.voiceagent") }
            val preferred = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as")
            return preferred.firstNotNullOfOrNull { p -> all.firstOrNull { it.packageName == p } } ?: all.firstOrNull()
        }
    }

    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()
    private var recognizer: SpeechRecognizer? = null
    private var pending: CancellableContinuation<String?>? = null
    /** On-device recognition for background listening; switched off if the phone lacks that language offline. */
    private var offlineOk = true
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

    /**
     * Listen for one sentence. Returns null if nothing was understood.
     * [passive] = quiet background listening for the wake word (no beep, on-device when possible).
     */
    suspend fun listen(passive: Boolean = false): String? = withContext(Dispatchers.Main) {
        lastError = 0
        val comp = pickRecognizer(ctx)
        if (comp == null && !SpeechRecognizer.isRecognitionAvailable(ctx)) {
            lastError = SpeechRecognizer.ERROR_CLIENT
            return@withContext null
        }
        if (!passive) {
            beep()
            delay(180)
        }
        val offline = passive && offlineOk
        val r = if (comp != null) SpeechRecognizer.createSpeechRecognizer(ctx, comp) else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (passive) 1000L else 1500L)
            if (offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
                            if (offline && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) offlineOk = false
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
                    pending = cont
                    r.startListening(intent)
                    cont.invokeOnCancellation { main.post { try { r.cancel() } catch (_: Exception) {} } }
                }
            }
        } finally {
            pending = null
            try { r.destroy() } catch (_: Exception) {}
            if (recognizer === r) recognizer = null
        }
    }

    /** Stop any listening right away (the waiting listen() returns null). */
    fun cancelListening() {
        val r = recognizer
        val run = Runnable {
            try { r?.cancel() } catch (_: Exception) {}
            pending?.let { if (it.isActive) it.resume(null) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    fun stopAll() {
        tts?.stop()
        cancelListening()
    }

    fun shutdown() {
        stopAll()
        tts?.shutdown()
        tts = null
    }
}
