package com.example.voiceagent

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager

/**
 * Opened from the Quick Settings tile, the notification's Talk button or the app icon's Talk
 * shortcut. Shows only a thin glow at the bottom edge while listening; the rest of the screen
 * keeps working underneath.
 */
class TalkActivity : Activity() {

    private lateinit var glow: GlowView
    private var started = false
    private val listener: (AgentService.State) -> Unit = { s ->
        runOnUiThread { if (s == AgentService.State.IDLE) { if (started) finish() } else glow.setMode(s) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        window.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, (40 * d).toInt())
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        glow = GlowView(this)
        glow.setOnClickListener { AgentService.instance?.stop(); finish() }
        setContentView(glow)
        AgentService.start(this)
        AgentService.whenReady { svc ->
            svc.stateListeners += listener
            started = true
            svc.talk()
        }
    }

    override fun onStart() { super.onStart(); Ui.visible++ }
    override fun onStop() {
        super.onStop(); Ui.visible--
        if (started && AgentService.instance?.busy != true) finish()
    }

    override fun onDestroy() {
        AgentService.instance?.stateListeners?.remove(listener)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { AgentService.instance?.stop(); finish() }
}
