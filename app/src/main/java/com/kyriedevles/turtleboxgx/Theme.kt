package com.kyriedevles.turtleboxgx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider

/**
 * The "pink planes" look: charcoal planes, metallic pink fills, cut corners and slanted tabs.
 * Colours live here so the whole app can be re-skinned from one place.
 */
object Theme {
    val ground = Color.parseColor("#161616")
    val plane = Color.parseColor("#1d1d20")
    val plane2 = Color.parseColor("#242427")
    val plane3 = Color.parseColor("#2d2d31")
    val pink = Color.parseColor("#ff6bb3")
    val hot = Color.parseColor("#ff4f9d")
    val crimson = Color.parseColor("#c4165f")
    val deep = Color.parseColor("#7a0b3a")
    val text = Color.parseColor("#f4eaf0")
    val label = Color.parseColor("#c9bcc4")
    val muted = Color.parseColor("#a89aa3")
    val onPink = Color.parseColor("#2b0a1a")
    val glow = Color.parseColor("#ff4f9d")

    /** Light pink to crimson, the metallic fill of anything switched on. */
    val metal = intArrayOf(Color.parseColor("#ff9bd0"), hot, crimson)

    /** A field or button box: dark fill, gradient hairline edge, top-right corner cut. */
    fun field(cut: Float, edgePx: Float) = CutDrawable(intArrayOf(plane2), cutTR = cut, border = edgePx)

    /** A selected option pill or switched-on cell: metallic pink fill. */
    fun on(cut: Float = 0f, slant: Float = 0f) = CutDrawable(metal, cutTR = cut, slant = slant)

    /** An unselected option pill or switched-off cell. */
    fun off(cut: Float = 0f, slant: Float = 0f) = CutDrawable(intArrayOf(plane3), cutTR = cut, slant = slant)

    /** The dialog plane: dark with the metallic strip on top and the top-right corner cut. */
    fun dialog(cut: Float, stripPx: Float) = CutDrawable(intArrayOf(plane), cutTR = cut, strip = stripPx)

    /** Raises a view so it casts a shadow with a pink tint. Shapes with cut corners need Android 11 or newer; older phones just skip it. */
    fun lift(v: View, d: CutDrawable, elevationPx: Float) {
        if (Build.VERSION.SDK_INT < 30) return
        v.elevation = elevationPx
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                if (view.width > 0 && view.height > 0) outline.setConvexPath(d.pathFor(view.width.toFloat(), view.height.toFloat()))
            }
        }
        v.outlineSpotShadowColor = Color.argb(255, 255, 79, 157)
        v.outlineAmbientShadowColor = Color.BLACK
    }
}

/** A flat shape with one or two cut corners, or slanted sides, filled solid or with the metallic gradient. */
class CutDrawable(
    private val colors: IntArray,
    private val cutTR: Float = 0f,
    private val cutTL: Float = 0f,
    private val slant: Float = 0f,
    private val border: Float = 0f,
    private val strip: Float = 0f,
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun pathFor(w: Float, h: Float, inset: Float = 0f): Path {
        val l = inset
        val t = inset
        val r = w - inset
        val b = h - inset
        val k = inset * 0.586f
        return Path().apply {
            if (slant > 0f) {
                moveTo(l + slant, t); lineTo(r, t); lineTo(r - slant, b); lineTo(l, b)
            } else {
                val tl = if (cutTL > 0f) maxOf(cutTL - k, 0f) else 0f
                val tr = if (cutTR > 0f) maxOf(cutTR - k, 0f) else 0f
                if (tl > 0f) { moveTo(l, t + tl); lineTo(l + tl, t) } else moveTo(l, t)
                if (tr > 0f) { lineTo(r - tr, t); lineTo(r, t + tr) } else lineTo(r, t)
                lineTo(r, b); lineTo(l, b)
            }
            close()
        }
    }

    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        c.save()
        c.translate(bounds.left.toFloat(), bounds.top.toFloat())
        if (border > 0f) {
            edge.shader = LinearGradient(0f, 0f, w, h, intArrayOf(
                Color.argb(179, 255, 107, 179), Color.argb(15, 255, 107, 179), Color.argb(64, 255, 107, 179),
            ), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(pathFor(w, h), edge)
        }
        val body = pathFor(w, h, border)
        fill.shader = if (colors.size > 1) LinearGradient(0f, 0f, w, h, colors, null, Shader.TileMode.CLAMP) else null
        if (colors.size == 1) fill.color = colors[0]
        c.drawPath(body, fill)
        if (strip > 0f) {
            bar.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(
                Theme.deep, Color.parseColor("#ff5fa8"), Theme.crimson, Theme.deep,
            ), floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.save()
            c.clipPath(body)
            c.drawRect(0f, 0f, w, strip, bar)
            c.restore()
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
