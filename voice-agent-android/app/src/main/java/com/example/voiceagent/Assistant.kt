package com.example.voiceagent

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/*
 * Optional: Voice Agent as your phone's "Digital assistant app" (Settings → Apps → Default apps).
 * Then holding the power button starts it, and Android lets it open apps (WhatsApp, the dialer…)
 * from the background, which is what the always-listening wake word needs. No restricted setting.
 */

object Assistant {
    fun isDefault(ctx: Context): Boolean = try {
        ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } catch (_: Exception) { false }
}

/** Kept running by Android while Voice Agent is the default assistant. */
class AgentInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        AgentService.start(this)
    }
}

class AgentSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AgentSession(this)
}

/** Shown when you hold the power button: the bottom glow while listening, then it gets out of the way. */
class AgentSession(ctx: Context) : VoiceInteractionSession(ctx) {

    private var glow: GlowView? = null
    private val listener: (AgentService.State) -> Unit = { s ->
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            when (s) {
                AgentService.State.LISTENING, AgentService.State.THINKING -> glow?.setMode(s)
                AgentService.State.WORKING, AgentService.State.IDLE -> hide()
            }
        }
    }

    override fun onCreateContentView(): View {
        val d = context.resources.displayMetrics.density
        val g = GlowView(context).also { glow = it }
        return FrameLayout(context).apply {
            addView(g, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (40 * d).toInt(), Gravity.BOTTOM))
            setOnClickListener { AgentService.instance?.stop(); hide() }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AgentService.start(context)
        AgentService.whenReady { svc ->
            svc.stateListeners += listener
            svc.talk()
        }
    }

    override fun onHide() {
        AgentService.instance?.stateListeners?.remove(listener)
        super.onHide()
    }
}

/**
 * Android requires an assistant app to also offer speech recognition. This one simply hands the
 * work to Google's recognizer, so other apps' voice typing keeps working normally.
 */
class ProxyRecognitionService : RecognitionService() {
    private var inner: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, cb: Callback) {
        inner?.destroy()
        val comp = Voice.pickRecognizer(this)
        if (comp == null) { try { cb.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) {}; return }
        val r = SpeechRecognizer.createSpeechRecognizer(this, comp)
        inner = r
        fun safe(f: () -> Unit) = try { f() } catch (_: Exception) {}
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) = safe { cb.readyForSpeech(p ?: Bundle()) }
            override fun onBeginningOfSpeech() = safe { cb.beginningOfSpeech() }
            override fun onRmsChanged(v: Float) = safe { cb.rmsChanged(v) }
            override fun onBufferReceived(b: ByteArray?) = safe { if (b != null) cb.bufferReceived(b) }
            override fun onEndOfSpeech() = safe { cb.endOfSpeech() }
            override fun onError(e: Int) = safe { cb.error(e) }
            override fun onResults(b: Bundle?) = safe { cb.results(b ?: Bundle()) }
            override fun onPartialResults(b: Bundle?) = safe { cb.partialResults(b ?: Bundle()) }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(intent)
    }

    override fun onCancel(cb: Callback) { inner?.cancel() }
    override fun onStopListening(cb: Callback) { inner?.stopListening() }
    override fun onDestroy() { inner?.destroy(); inner = null; super.onDestroy() }
}

/** Remembers whether one of our screens is showing (Android only lets visible apps open other apps). */
object Ui {
    @Volatile var visible = 0
}
