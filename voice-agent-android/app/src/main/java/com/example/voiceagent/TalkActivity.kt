package com.example.voiceagent

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * A small see-through "Listening…" card. Opened from the Quick Settings tile, the notification's
 * Talk button or the app icon's Talk shortcut, so it works without "Display over other apps".
 */
class TalkActivity : Activity() {

    private lateinit var label: TextView
    private var started = false
    private val listener: (AgentService.State) -> Unit = { s ->
        runOnUiThread {
            when (s) {
                AgentService.State.LISTENING -> label.text = tr("🎤 Listening…", "🎤 ऐकतोय…")
                AgentService.State.WORKING -> label.text = tr("⏳ Working… (tap to stop)", "⏳ काम करतोय… (थांबवायला टॅप करा)")
                AgentService.State.IDLE -> if (started) finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        label = TextView(this).apply {
            text = tr("🎤 Starting…", "🎤 सुरू करतोय…")
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding((24 * d).toInt(), (18 * d).toInt(), (24 * d).toInt(), (18 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 28 * d; setColor(Color.parseColor("#E6303F9F")) }
        }
        val root = FrameLayout(this).apply {
            addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (96 * d).toInt() })
            setOnClickListener { AgentService.instance?.stop(); finish() }
        }
        setContentView(root)
        AgentService.start(this)
        AgentService.whenReady { svc ->
            svc.stateListeners += listener
            started = true
            svc.talk()
        }
    }

    override fun onStart() { super.onStart(); Ui.visible++ }
    override fun onStop() { super.onStop(); Ui.visible-- ; if (started && AgentService.instance?.busy != true) finish() }

    override fun onDestroy() {
        AgentService.instance?.stateListeners?.remove(listener)
        super.onDestroy()
    }
}
