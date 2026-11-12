package com.example.voiceagent

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.TextView

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

/** Shown when you hold the power button: listens for one command, then gets out of the way. */
class AgentSession(ctx: Context) : VoiceInteractionSession(ctx) {

    private var label: TextView? = null
    private val listener: (AgentService.State) -> Unit = { s ->
        when (s) {
            AgentService.State.LISTENING -> label?.text = context.tr("🎤 Listening…", "🎤 ऐकतोय…")
            AgentService.State.WORKING, AgentService.State.IDLE -> hide()
        }
    }

    override fun onCreateContentView(): View {
        val d = context.resources.displayMetrics.density
        val tv = TextView(context).apply {
            text = context.tr("🎤 Listening…", "🎤 ऐकतोय…")
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding((24 * d).toInt(), (18 * d).toInt(), (24 * d).toInt(), (18 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 28 * d; setColor(Color.parseColor("#E6303F9F")) }
        }
        label = tv
        return FrameLayout(context).apply {
            addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (96 * d).toInt() })
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
