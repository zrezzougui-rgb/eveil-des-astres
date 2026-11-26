package com.zrezzougui.orbite.hud

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Le « cœur » de Jarvis, d'après la démo TechEnClair : un nuage de particules en 3D qui tourne,
 * des fils de points en orbite, et un noyau lumineux d'où partent des rayons.
 * Trois états : veille (rotation lente), écoute (le nuage respire avec la voix), parole (rayons qui pulsent).
 * Tout est calculé et dessiné au Canvas, en lots (drawPoints / drawLines) pour rester fluide.
 */
class ArcReactorView(ctx: Context) : View(ctx) {

    enum class State { IDLE, LISTENING, SPEAKING }

    var state = State.IDLE
        set(v) { field = v; invalidate() }

    /** Conservés pour compatibilité ; le nuage n'affiche pas de texte. */
    var centerText: String = ""
    var subText: String = ""

    /** Volume de la voix 0..1 pendant l'écoute. */
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f) }

    private val d = ctx.resources.displayMetrics.density
    private val rnd = Random(7)

    // ---------------------------------------------------------------- géométrie
    private val nCloud = 720          // points répartis dans une coquille sphérique épaisse et irrégulière
    private val nStrands = 14         // fils (arcs de cercle inclinés)
    private val perStrand = 46
    private val n = nCloud + nStrands * perStrand
    private val px = FloatArray(n); private val py = FloatArray(n); private val pz = FloatArray(n)
    private val rays = IntArray(70) { it }          // indices des particules reliées au noyau
    private val pts = FloatArray(n * 2)
    private val depth = FloatArray(n)
    private val lines = FloatArray((nStrands * (perStrand - 1) + rays.size) * 4)

    private var t = 0f
    private var smoothLevel = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t += 1f / 60f; invalidate() }
    }

    private val near = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8FE3FF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FA8D8.toInt(); strokeCap = Paint.Cap.ROUND }
    private val far = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A5F86.toInt(); strokeCap = Paint.Cap.ROUND }
    private val strandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FD8FF.toInt(); alpha = 40 }
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9BE8FF.toInt() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // Nuage : directions uniformes (spirale de Fibonacci) et rayon aléatoire dans une coquille épaisse.
        val golden = PI * (3 - sqrt(5.0))
        for (i in 0 until nCloud) {
            val y = 1 - 2.0 * (i + 0.5) / nCloud
            val r = sqrt(1 - y * y)
            val th = golden * i
            val radius = 0.3 + 0.78 * sqrt(rnd.nextDouble())
            px[i] = (cos(th) * r * radius).toFloat(); py[i] = (y * radius).toFloat(); pz[i] = (sin(th) * r * radius).toFloat()
        }
        // Fils : arcs de cercle sur des plans aléatoires, légèrement bruités.
        var k = nCloud
        repeat(nStrands) {
            val a = rnd.nextDouble() * 2 * PI; val b = acos(2 * rnd.nextDouble() - 1)
            val nx = sin(b) * cos(a); val ny = cos(b); val nz = sin(b) * sin(a)
            // base orthonormée (u, v) du plan perpendiculaire à n
            var ux = -nz; var uy = 0.0; var uz = nx
            val ul = sqrt(ux * ux + uz * uz).coerceAtLeast(1e-6); ux /= ul; uz /= ul
            val vx = ny * uz - nz * uy; val vy = nz * ux - nx * uz; val vz = nx * uy - ny * ux
            val radius = 0.75 + 0.25 * rnd.nextDouble()
            val start = rnd.nextDouble() * 2 * PI; val span = PI * (0.5 + rnd.nextDouble())
            for (j in 0 until perStrand) {
                val ang = start + span * j / (perStrand - 1)
                val jitter = 1 + (rnd.nextDouble() - 0.5) * 0.06
                px[k] = ((ux * cos(ang) + vx * sin(ang)) * radius * jitter).toFloat()
                py[k] = ((uy * cos(ang) + vy * sin(ang)) * radius * jitter).toFloat()
                pz[k] = ((uz * cos(ang) + vz * sin(ang)) * radius * jitter).toFloat()
                k++
            }
        }
        for (i in rays.indices) rays[i] = rnd.nextInt(nCloud)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (isShown) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) { if (!anim.isStarted) anim.start() } else anim.cancel()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f; val cy = height / 2f
        val baseR = min(width, height) * 0.44f

        smoothLevel += (level - smoothLevel) * 0.25f
        val speed = when (state) { State.IDLE -> 0.12f; State.LISTENING -> 0.35f; State.SPEAKING -> 0.22f }
        val breath = when (state) {
            State.IDLE -> 1f + 0.015f * sin(t * 1.3f)
            State.LISTENING -> 1f + 0.10f * smoothLevel
            State.SPEAKING -> 1f + 0.03f * sin(t * 11f)
        }
        val ay = t * speed
        val ax = 0.35f + 0.08f * sin(t * 0.21f)
        val cyA = cos(ay); val syA = sin(ay); val cxA = cos(ax); val sxA = sin(ax)
        val r = baseR * breath
        val cam = 2.6f

        // Projection perspective de toutes les particules
        for (i in 0 until n) {
            val x1 = px[i] * cyA + pz[i] * syA
            val z1 = -px[i] * syA + pz[i] * cyA
            val y2 = py[i] * cxA - z1 * sxA
            val z2 = py[i] * sxA + z1 * cxA
            val p = cam / (cam - z2)
            pts[i * 2] = cx + x1 * r * p
            pts[i * 2 + 1] = cy + y2 * r * p
            depth[i] = z2
        }

        // Halo central
        val coreR = baseR * (0.22f + 0.06f * when (state) { State.SPEAKING -> 0.5f + 0.5f * sin(t * 9f); State.LISTENING -> smoothLevel; else -> 0.3f })
        glow.shader = RadialGradient(cx, cy, coreR * 2.2f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xCC9BE8FF.toInt(), 0x334FD8FF, 0x00000000), floatArrayOf(0f, 0.08f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, coreR * 2.2f, glow)

        // Fils d'orbite + rayons vers le noyau (un seul appel drawLines par famille)
        var li = 0
        var k = nCloud
        repeat(nStrands) {
            for (j in 0 until perStrand - 1) {
                val a = k + j; val b = a + 1
                lines[li++] = pts[a * 2]; lines[li++] = pts[a * 2 + 1]; lines[li++] = pts[b * 2]; lines[li++] = pts[b * 2 + 1]
            }
            k += perStrand
        }
        strandPaint.strokeWidth = 0.8f * d
        c.drawLines(lines, 0, li, strandPaint)

        val rayStart = li
        val rayAlpha = when (state) {
            State.IDLE -> 50
            State.LISTENING -> (60 + 140 * smoothLevel).toInt()
            State.SPEAKING -> (70 + 90 * (0.5f + 0.5f * sin(t * 9f))).toInt()
        }
        for (idx in rays) {
            lines[li++] = cx; lines[li++] = cy; lines[li++] = pts[idx * 2]; lines[li++] = pts[idx * 2 + 1]
        }
        rayPaint.alpha = rayAlpha.coerceIn(0, 255)
        rayPaint.strokeWidth = 0.7f * d
        c.drawLines(lines, rayStart, li - rayStart, rayPaint)

        // Particules en trois couches de profondeur (taille et luminosité)
        drawLayer(c, far, -2f, -0.25f, 1.6f * d, 150)
        drawLayer(c, mid, -0.25f, 0.35f, 2.2f * d, 210)
        drawLayer(c, near, 0.35f, 2f, 2.9f * d, 255)

        // Noyau
        glow.shader = RadialGradient(cx, cy, coreR * 0.5f, intArrayOf(0xFFFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, coreR * 0.5f, glow)
    }

    private val layer = FloatArray(n * 2)
    private fun drawLayer(c: Canvas, p: Paint, from: Float, to: Float, size: Float, alpha: Int) {
        var m = 0
        for (i in 0 until n) if (depth[i] in from..to) { layer[m++] = pts[i * 2]; layer[m++] = pts[i * 2 + 1] }
        p.strokeWidth = size; p.alpha = alpha
        c.drawPoints(layer, 0, m, p)
    }
}
