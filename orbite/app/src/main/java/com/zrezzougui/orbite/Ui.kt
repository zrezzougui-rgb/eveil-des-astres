package com.zrezzougui.orbite

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.WindowInsets
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Jetons du design system Orbite (thème Nuit) : couleurs, espacements, arrondis, polices.
 * Toute l'interface est construite à partir d'eux, sans fichiers XML ni bibliothèque.
 */
object Palette {
    const val BG = 0xFF0E1124.toInt()
    const val SURFACE = 0xFF1B2033.toInt()
    const val SURFACE_2 = 0xFF262C45.toInt()
    const val LINE_STRONG = 0xFF6B7499.toInt()
    const val TEXT = 0xFFECEFF7.toInt()         // ink
    const val MUTED = 0xFF9AA3BC.toInt()        // ink-muted
    const val PRIMARY = 0xFF7C9CFF.toInt()      // « Orbite »
    const val ON_PRIMARY = 0xFF0E1124.toInt()
    const val PRIMARY_SOFT = 0xFF2A3566.toInt()
    const val SECONDARY = 0xFFFFD27A.toInt()    // « Soleil »
    const val ON_SECONDARY = 0xFF2A1E00.toInt()
    const val ACCENT = 0xFF5EE0C2.toInt()       // « Aurore »
    const val ON_ACCENT = 0xFF06231C.toInt()
    const val SUCCESS = 0xFF6BE39B.toInt()
    const val DANGER = 0xFFFF7A85.toInt()
}

object Space { const val XS = 4; const val S = 8; const val M = 12; const val L = 16; const val XL = 24; const val XXL = 32; const val TOUCH = 48 }
object Radius { const val SM = 8f; const val MD = 14f; const val LG = 20f; const val PILL = 999f }

/** Sora (titres) et Inter (texte), embarquées dans assets/fonts. */
object Fonts {
    private val cache = HashMap<String, Typeface>()
    private fun load(ctx: Context, file: String, fallback: Typeface): Typeface =
        cache.getOrPut(file) { runCatching { Typeface.createFromAsset(ctx.assets, "fonts/$file") }.getOrDefault(fallback) }

    fun body(ctx: Context) = load(ctx, "inter_regular.ttf", Typeface.DEFAULT)
    fun strong(ctx: Context) = load(ctx, "inter_semibold.ttf", Typeface.DEFAULT_BOLD)
    fun display(ctx: Context) = load(ctx, "sora_semibold.ttf", Typeface.DEFAULT_BOLD)
    fun displayLight(ctx: Context) = load(ctx, "sora_light.ttf", Typeface.DEFAULT)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun Context.label(
    text: CharSequence,
    size: Float = 16f,
    color: Int = Palette.TEXT,
    bold: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    typeface = if (bold) Fonts.strong(context) else Fonts.body(context)
}

/** Titre en Sora (styles title / subtitle du design system). */
fun Context.heading(text: CharSequence, size: Float = 22f, color: Int = Palette.TEXT): TextView =
    label(text, size, color).apply { typeface = Fonts.display(context) }

/**
 * Pilule touchable. `filled` = bouton primaire (48dp, fond primary) ;
 * sinon puce / bouton secondaire (36dp, fond surface-2).
 */
fun Context.pill(text: String, filled: Boolean = false, onClick: (View) -> Unit): TextView =
    label(text, if (filled) 15f else 14f, if (filled) Palette.ON_PRIMARY else Palette.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        if (filled) {
            minHeight = dp(Space.TOUCH)
            setPadding(dp(Space.XL), dp(Space.M), dp(Space.XL), dp(Space.M))
        } else {
            minHeight = dp(36)
            setPadding(dp(Space.L), dp(Space.S), dp(Space.L), dp(Space.S))
        }
        background = rounded(if (filled) Palette.PRIMARY else Palette.SURFACE_2, dpf(Radius.PILL))
        isClickable = true
        isFocusable = true
        setOnClickListener(onClick)
    }

fun Context.vertical(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.horizontal(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
fun wrapWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
fun weighted(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply {
    setMargins(l, t, r, b)
}

/** Minuscules sans accents, même longueur que le texte d'origine (indices alignés). */
fun normalize(s: String): String {
    val src = "àâäáãåéèêëíìîïóòôöõúùûüçñÿœ’"
    val dst = "aaaaaaeeeeiiiiooooouuuucnyo'"
    val sb = StringBuilder(s.length)
    for (c in s) {
        val l = c.lowercaseChar()
        val i = src.indexOf(l)
        sb.append(if (i >= 0) dst[i] else l)
    }
    return sb.toString()
}

/** Android 15 affiche l'appli bord à bord : on garde le contenu hors des barres système et du clavier. */
fun View.padForSystemBars(extra: Int = 0) {
    setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left + extra, b.top + extra, b.right + extra, b.bottom + extra)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(
                insets.systemWindowInsetLeft + extra, insets.systemWindowInsetTop + extra,
                insets.systemWindowInsetRight + extra, insets.systemWindowInsetBottom + extra,
            )
        }
        insets
    }
}

/** Même rendu bord à bord sur toutes les versions (Android 15 l'impose de toute façon). */
fun android.app.Activity.edgeToEdge() {
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}

/** Puce sélectionnable : sélectionnée = fond primary-soft + contour primary (pas seulement une couleur). */
fun Context.chip(text: String, selected: Boolean, onClick: (View) -> Unit): TextView =
    pill(text, filled = false, onClick = onClick).apply {
        if (selected) background = rounded(Palette.PRIMARY_SOFT, dpf(Radius.PILL), dp(2), Palette.PRIMARY)
    }
