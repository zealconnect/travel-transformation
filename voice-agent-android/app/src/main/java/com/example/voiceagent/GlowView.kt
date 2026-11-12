package com.example.voiceagent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/** A soft animated light along the bottom edge: the only sign the agent is listening or thinking. */
class GlowView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(14 * d, BlurMaskFilter.Blur.NORMAL) }
    private var phase = 0f
    private var strength = 1f
    private val colors = intArrayOf(0xFF4285F4.toInt(), 0xFF9B72CB.toInt(), 0xFFD96570.toInt(), 0xFF34A853.toInt(), 0xFF4285F4.toInt())

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    /** Listening = bright; thinking = softer and faster. */
    fun setMode(state: AgentService.State) {
        strength = when (state) {
            AgentService.State.LISTENING -> 1f
            AgentService.State.THINKING -> 0.7f
            else -> 0.45f
        }
        anim.duration = if (state == AgentService.State.THINKING) 1100 else 2400
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val shift = phase * w
        val shader = LinearGradient(-w + shift, 0f, w + shift, 0f, colors, null, Shader.TileMode.MIRROR)
        line.shader = shader; halo.shader = shader
        halo.alpha = (150 * strength).toInt(); line.alpha = (255 * strength).toInt()
        c.drawRoundRect(RectF(0f, h - 16 * d, w, h + 8 * d), 12 * d, 12 * d, halo)
        c.drawRoundRect(RectF(0f, h - 4 * d, w, h), 2 * d, 2 * d, line)
    }
}
