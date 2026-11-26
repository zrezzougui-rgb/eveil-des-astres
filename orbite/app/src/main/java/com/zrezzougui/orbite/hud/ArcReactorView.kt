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

    private val tickMajor = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.PRIMARY; alpha = 200 }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.PRIMARY; strokeCap = Paint.Cap.BUTT }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - 4 * d
        val speed = when (state) { State.IDLE -> 1f; State.LISTENING -> 3f; State.SPEAKING -> 2f }
        val pulse = when (state) {
            State.IDLE -> 0.5f + 0.5f * sin(t * 1.2f)
            State.LISTENING -> 0.35f + 0.65f * level
            State.SPEAKING -> 0.5f + 0.5f * sin(t * 9f)
        }

        // Halo général
        glow.shader = RadialGradient(cx, cy, r, intArrayOf(0x594FD8FF, 0x144FD8FF, 0x00000000),
            floatArrayOf(0f, 0.5f + 0.08f * pulse, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, glow)

        // Cercle extérieur + 72 graduations (une majeure sur six)
        ring.strokeWidth = d; ring.alpha = 130
        c.drawCircle(cx, cy, r * 0.98f, ring)
        dim.strokeWidth = 1.2f * d
        tickMajor.strokeWidth = 1.8f * d
        val rot = t * 3 * speed
        for (i in 0 until 72) {
            val a = Math.toRadians(i * 5.0 + rot)
            val major = i % 6 == 0
            val inner = if (major) 0.87f else 0.91f
            val cos = Math.cos(a).toFloat(); val sin = Math.sin(a).toFloat()
            c.drawLine(cx + r * inner * cos, cy + r * inner * sin, cx + r * 0.95f * cos, cy + r * 0.95f * sin, if (major) tickMajor else dim)
        }

        // Six segments cyan lumineux (sens horaire) : halo large puis trait net
        val r2 = r * 0.78f
        oval.set(cx - r2, cy - r2, cx + r2, cy + r2)
        halo.strokeWidth = r * 0.11f; halo.alpha = (40 + 50 * pulse).toInt()
        ring.strokeWidth = r * 0.055f; ring.alpha = 255
        val segRot = t * 25 * speed
        for (i in 0 until 6) {
            c.drawArc(oval, i * 60f + 10f + segRot, 38f, false, halo)
            c.drawArc(oval, i * 60f + 10f + segRot, 38f, false, ring)
        }

        // Trois arcs dorés (sens inverse)
        gold.strokeWidth = r * 0.025f
        val r3 = r * 0.66f
        oval.set(cx - r3, cy - r3, cx + r3, cy + r3)
        for (i in 0 until 3) c.drawArc(oval, i * 120f + 200f - t * 40 * speed, 70f, false, gold)

        // Anneau intérieur
        ring.strokeWidth = 1.2f * d; ring.alpha = 160
        c.drawCircle(cx, cy, r * 0.57f, ring)

        // Cœur
        val rc = r * (0.49f + 0.02f * pulse)
        core.shader = RadialGradient(cx, cy, rc, intArrayOf(0xE60E3550.toInt(), 0xF203070D.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rc, core)
        halo.strokeWidth = r * 0.06f; halo.alpha = (60 + 80 * pulse).toInt()
        c.drawCircle(cx, cy, rc, halo)
        ring.strokeWidth = 2f * d; ring.alpha = 255
        c.drawCircle(cx, cy, rc, ring)

        // Textes
        if (centerText.isNotEmpty()) {
            text.textSize = r * (if (centerText.length > 5) 0.2f else 0.29f)
            c.drawText(centerText, cx, cy + text.textSize * 0.36f, text)
        }
        if (subText.isNotEmpty()) {
            sub.textSize = r * 0.075f
            c.drawText(subText.uppercase(), cx, cy + r * 0.29f, sub)
        }
    }
}
