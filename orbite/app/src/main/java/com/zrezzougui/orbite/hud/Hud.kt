package com.zrezzougui.orbite.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.zrezzougui.orbite.Fonts
import com.zrezzougui.orbite.Palette
import com.zrezzougui.orbite.dp
import com.zrezzougui.orbite.label
import com.zrezzougui.orbite.matchWrap
import com.zrezzougui.orbite.vertical

/**
 * Briques visuelles du HUD Jarvis, dessinées au Canvas (aucune image) :
 * panneau à coins coupés avec équerres lumineuses, fond étoilé en grille, icônes au trait.
 */

/** Panneau : coins haut-gauche et bas-droit coupés, contour fin cyan, équerres aux deux autres coins. */
class HudPanelDrawable(
    private val density: Float,
    private val fill: Int = Palette.PANEL,
    private val stroke: Int = Palette.LINE,
    private val accent: Int = Palette.PRIMARY,
    private val cut: Float = 10f,
    private val brackets: Boolean = true,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fill }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = stroke; strokeWidth = density }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = accent; strokeWidth = 2 * density; strokeCap = Paint.Cap.SQUARE
    }
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = RectF(bounds)
        val h = density / 2
        b.inset(h, h)
        val k = cut * density
        path.reset()
        path.moveTo(b.left + k, b.top); path.lineTo(b.right, b.top); path.lineTo(b.right, b.bottom - k)
        path.lineTo(b.right - k, b.bottom); path.lineTo(b.left, b.bottom); path.lineTo(b.left, b.top + k); path.close()
        c.drawPath(path, fillPaint)
        if (stroke != 0) c.drawPath(path, strokePaint)
        if (brackets) {
            val l = 10 * density
            val a = density
            c.drawLine(b.right - l, b.top + a, b.right - a, b.top + a, accentPaint)
            c.drawLine(b.right - a, b.top + a, b.right - a, b.top + l, accentPaint)
            c.drawLine(b.left + a, b.bottom - l, b.left + a, b.bottom - a, accentPaint)
            c.drawLine(b.left + a, b.bottom - a, b.left + l, b.bottom - a, accentPaint)
        }
    }

    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getPadding(padding: Rect): Boolean = false
}

/** Fond d'écran : nuit profonde, halo cyan derrière le réacteur, grille de points discrète. */
class HudBackgroundDrawable(private val density: Float, private val glowY: Float = 0.22f) : Drawable() {
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x124FD8FF }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val b = bounds
        c.drawColor(Palette.BG)
        val cx = b.exactCenterX(); val cy = b.height() * glowY
        val r = b.width() * 0.75f
        glow.shader = RadialGradient(cx, cy, r, intArrayOf(0x264FD8FF, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, glow)
        val step = 22 * density
        var y = step / 2
        while (y < b.height()) {
            var x = step / 2
            while (x < b.width()) { c.drawCircle(x, y, density * 0.8f, dot); x += step }
            y += step
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.OPAQUE
}

/** Icônes au trait (grille 24 × 24), même famille visuelle que le reste du HUD. */
class HudIcon(private val kind: Kind, color: Int, private val density: Float, strokeDp: Float = 1.7f) : Drawable() {
    enum class Kind { MIC, GRID, LAYERS, CAMERA, GEAR, BACK, SPEAKER, MUTE, SEND, CLOSE, CALENDAR, MENU }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = color; strokeWidth = strokeDp * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun getIntrinsicWidth() = (24 * density).toInt()
    override fun getIntrinsicHeight() = (24 * density).toInt()

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        c.scale(s, s)
        p.strokeWidth = p.strokeWidth.coerceAtLeast(1f)
        val sw = p.strokeWidth
        p.strokeWidth = sw / s
        path.reset()
        when (kind) {
            Kind.MIC -> {
                c.drawRoundRect(9f, 3f, 15f, 14f, 3f, 3f, p)
                path.moveTo(5f, 11f); path.cubicTo(5f, 20.3f, 19f, 20.3f, 19f, 11f)
                path.moveTo(12f, 18f); path.lineTo(12f, 21f)
            }
            Kind.GRID -> { for (x in listOf(4f, 14f)) for (y in listOf(4f, 14f)) c.drawRect(x, y, x + 6f, y + 6f, p) }
            Kind.LAYERS -> {
                path.moveTo(12f, 3f); path.lineTo(21f, 8f); path.lineTo(12f, 13f); path.lineTo(3f, 8f); path.close()
                path.moveTo(3f, 13f); path.lineTo(12f, 18f); path.lineTo(21f, 13f)
            }
            Kind.CAMERA -> {
                path.moveTo(4f, 8f); path.lineTo(7f, 8f); path.lineTo(9f, 5f); path.lineTo(15f, 5f); path.lineTo(17f, 8f)
                path.lineTo(20f, 8f); path.lineTo(20f, 19f); path.lineTo(4f, 19f); path.close()
                c.drawCircle(12f, 13f, 3.5f, p)
            }
            Kind.GEAR -> {
                c.drawCircle(12f, 12f, 3f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    path.moveTo(12f + 6.5f * Math.cos(a).toFloat(), 12f + 6.5f * Math.sin(a).toFloat())
                    path.lineTo(12f + 9.5f * Math.cos(a).toFloat(), 12f + 9.5f * Math.sin(a).toFloat())
                }
            }
            Kind.BACK -> { path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f) }
            Kind.SPEAKER, Kind.MUTE -> {
                path.moveTo(4f, 10f); path.lineTo(8f, 10f); path.lineTo(13f, 6f); path.lineTo(13f, 18f); path.lineTo(8f, 14f); path.lineTo(4f, 14f); path.close()
                if (kind == Kind.SPEAKER) {
                    path.moveTo(16f, 9f); path.cubicTo(18f, 10.5f, 18f, 13.5f, 16f, 15f)
                    path.moveTo(18.5f, 6.5f); path.cubicTo(22f, 9.5f, 22f, 14.5f, 18.5f, 17.5f)
                } else {
                    path.moveTo(16f, 9f); path.lineTo(21f, 15f); path.moveTo(21f, 9f); path.lineTo(16f, 15f)
                }
            }
            Kind.SEND -> { path.moveTo(4f, 12f); path.lineTo(20f, 12f); path.moveTo(14f, 6f); path.lineTo(20f, 12f); path.lineTo(14f, 18f) }
            Kind.CLOSE -> { path.moveTo(6f, 6f); path.lineTo(18f, 18f); path.moveTo(18f, 6f); path.lineTo(6f, 18f) }
            Kind.CALENDAR -> {
                c.drawRect(4f, 6f, 20f, 20f, p)
                path.moveTo(4f, 10f); path.lineTo(20f, 10f); path.moveTo(8f, 3f); path.lineTo(8f, 7f); path.moveTo(16f, 3f); path.lineTo(16f, 7f)
            }
            Kind.MENU -> { for (y in listOf(7f, 12f, 17f)) { path.moveTo(5f, y); path.lineTo(19f, y) } }
        }
        c.drawPath(path, p)
        p.strokeWidth = sw
        c.restore()
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

// ---------------------------------------------------------------------- aides de construction

fun Context.hudPanel(fill: Int = Palette.PANEL, brackets: Boolean = true) =
    HudPanelDrawable(resources.displayMetrics.density, fill = fill, brackets = brackets)

fun Context.hudBackground(glowY: Float = 0.22f) = HudBackgroundDrawable(resources.displayMetrics.density, glowY)

fun Context.icon(kind: HudIcon.Kind, color: Int = Palette.PRIMARY) = HudIcon(kind, color, resources.displayMetrics.density)

/** Petite étiquette Orbitron en capitales (« MÉTÉO », « MODULES »). */
fun Context.hudLabel(text: String, color: Int = Palette.PRIMARY, size: Float = 10f): TextView =
    label(text.uppercase(), size, color).apply { typeface = Fonts.hud(context); letterSpacing = 0.2f }

/** Titre de section : étiquette + filet qui s'estompe. */
fun Context.hudSection(text: String): View {
    val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    row.addView(hudLabel(text, Palette.MUTED))
    row.addView(View(this).apply {
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.LINE, 0),
        )
    }, LinearLayout.LayoutParams(0, dp(1), 1f).apply { leftMargin = dp(10) })
    return row
}

/** Bouton rond au trait avec son libellé dessous (actions rapides de l'accueil). */
fun Context.hudAction(kind: HudIcon.Kind, text: String, lit: Boolean = false, onClick: (View) -> Unit): View {
    val col = vertical().apply { gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; setOnClickListener(onClick); contentDescription = text }
    val ring = FrameLayout(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Palette.PANEL); setStroke(dp(1), if (lit) Palette.PRIMARY else Palette.LINE)
        }
        addView(ImageView(context).apply { setImageDrawable(icon(kind)) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    }
    col.addView(ring, LinearLayout.LayoutParams(dp(50), dp(50)))
    col.addView(hudLabel(text, Palette.MUTED, 9f).apply { setPadding(0, dp(6), 0, 0) })
    return col
}

/** Bouton carré à icône seule (barre du haut). */
fun Context.iconButton(kind: HudIcon.Kind, description: String, color: Int = Palette.MUTED, onClick: (View) -> Unit): ImageView =
    ImageView(this).apply {
        setImageDrawable(icon(kind, color))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        contentDescription = description
        isClickable = true
        setOnClickListener(onClick)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

/** Bouton micro rond, plein, lumineux. */
fun Context.micButton(kind: HudIcon.Kind = HudIcon.Kind.MIC, description: String = "Parler à Jarvis", onClick: (View) -> Unit): FrameLayout =
    FrameLayout(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Palette.PRIMARY)
        }
        elevation = dp(6).toFloat()
        outlineAmbientShadowColor = Palette.PRIMARY
        outlineSpotShadowColor = Palette.PRIMARY
        addView(ImageView(context).apply { setImageDrawable(icon(kind, Palette.ON_PRIMARY)) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        contentDescription = description
        isClickable = true
        setOnClickListener(onClick)
    }

/** Capteur du HUD : étiquette, grande valeur, ligne secondaire. Renvoie (panneau, valeur, ligne). */
fun Context.hudSensor(title: String): Triple<LinearLayout, TextView, TextView> {
    val box = vertical().apply { background = hudPanel(); setPadding(dp(10), dp(9), dp(10), dp(10)) }
    box.addView(hudLabel(title))
    val v = label("—", 18f, Palette.TEXT).apply { typeface = Fonts.hudBold(context); setPadding(0, dp(6), 0, 0); maxLines = 1 }
    val s = label("", 11f, Palette.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0) }
    box.addView(v, matchWrap()); box.addView(s, matchWrap())
    return Triple(box, v, s)
}
