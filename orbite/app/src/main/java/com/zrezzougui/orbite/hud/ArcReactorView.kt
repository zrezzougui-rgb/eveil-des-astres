package com.zrezzougui.orbite.hud

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import com.zrezzougui.orbite.Fonts
import com.zrezzougui.orbite.Palette
import kotlin.math.min
import kotlin.math.sin

/**
 * Le « réacteur » de Jarvis : anneaux segmentés qui tournent, cœur lumineux,
 * texte au centre (heure ou état). Trois états : repos, écoute (suit le volume de la voix), parole.
 * Dessiné au Canvas, sans image ni bibliothèque ; l'animation s'arrête quand la vue est cachée.
 */
class ArcReactorView(ctx: Context) : View(ctx) {

    enum class State { IDLE, LISTENING, SPEAKING }

    var state = State.IDLE
        set(v) { field = v; invalidate() }

    /** Texte principal au centre (ex. l'heure) et ligne secondaire. */
    var centerText: String = ""
        set(v) { field = v; invalidate() }
    var subText: String = ""
        set(v) { field = v; invalidate() }

    /** Volume de la voix 0..1 pendant l'écoute. */
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f) }

    private val d = ctx.resources.displayMetrics.density
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t += 1f / 60f; invalidate() }
    }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.PRIMARY }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.LINE_STRONG }
    private val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.SECONDARY; strokeCap = Paint.Cap.ROUND }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT; textAlign = Paint.Align.CENTER; typeface = Fonts.hud(ctx)
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.PRIMARY; textAlign = Paint.Align.CENTER; typeface = Fonts.hud(ctx); letterSpacing = 0.15f
    }
    private val oval = RectF()

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (isShown) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) { if (!anim.isStarted) anim.start() } else anim.cancel()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - 4 * d
        val speed = when (state) { State.IDLE -> 1f; State.LISTENING -> 3f; State.SPEAKING -> 2f }
        val pulse = when (state) {
            State.IDLE -> 0.5f + 0.5f * sin(t * 1.5f)
            State.LISTENING -> 0.4f + 0.6f * level
            State.SPEAKING -> 0.5f + 0.5f * sin(t * 9f)
        }

        // Halo
        glow.shader = RadialGradient(cx, cy, r, intArrayOf(Palette.GLOW, 0x00000000), floatArrayOf(0.35f + 0.1f * pulse, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, glow)

        // Anneau extérieur : fin + graduations
        ring.strokeWidth = 1.2f * d; ring.alpha = 160
        c.drawCircle(cx, cy, r * 0.98f, ring)
        dim.strokeWidth = 2f * d
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 + t * 4 * speed)
            val inner = if (i % 5 == 0) 0.90f else 0.93f
            c.drawLine(
                cx + (r * inner * Math.cos(a)).toFloat(), cy + (r * inner * Math.sin(a)).toFloat(),
                cx + (r * 0.96f * Math.cos(a)).toFloat(), cy + (r * 0.96f * Math.sin(a)).toFloat(), dim,
            )
        }

        // Anneau segmenté (sens horaire)
        ring.strokeWidth = 6f * d; ring.alpha = 230
        val r2 = r * 0.80f
        oval.set(cx - r2, cy - r2, cx + r2, cy + r2)
        for (i in 0 until 8) c.drawArc(oval, i * 45f + t * 30 * speed, 30f, false, ring)

        // Arcs dorés (sens inverse)
        gold.strokeWidth = 3f * d
        val r3 = r * 0.68f
        oval.set(cx - r3, cy - r3, cx + r3, cy + r3)
        for (i in 0 until 3) c.drawArc(oval, i * 120f - t * 45 * speed, 70f, false, gold)

        // Anneau intérieur fin
        ring.strokeWidth = 1.5f * d; ring.alpha = 200
        c.drawCircle(cx, cy, r * 0.58f, ring)

        // Cœur
        val rc = r * (0.50f + 0.04f * pulse)
        core.shader = RadialGradient(cx, cy, rc, intArrayOf(0xCC0E3550.toInt(), 0xEE050B14.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rc, core)
        ring.strokeWidth = 2f * d; ring.alpha = (140 + 115 * pulse).toInt()
        c.drawCircle(cx, cy, rc, ring)

        // Textes
        if (centerText.isNotEmpty()) {
            text.textSize = r * (if (centerText.length > 5) 0.20f else 0.30f)
            c.drawText(centerText, cx, cy + text.textSize * 0.35f, text)
        }
        if (subText.isNotEmpty()) {
            sub.textSize = r * 0.085f
            c.drawText(subText.uppercase(), cx, cy + r * 0.30f, sub)
        }
    }
}
