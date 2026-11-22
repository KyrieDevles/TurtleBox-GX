package com.kyriedevles.turtleboxgx

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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
import android.content.Context
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
    val nav = Color.parseColor("#121214")
    val glow = Color.parseColor("#ff4f9d")

    /** Light pink to crimson, the metallic fill of anything switched on. */
    val metal = intArrayOf(Color.parseColor("#ff9bd0"), hot, crimson)

    /** A field or button box: dark fill, gradient hairline edge, top-right corner cut. */
    fun field(cut: Float, edgePx: Float, shadow: Float = 0f) = CutDrawable(intArrayOf(plane2), cutTR = cut, border = edgePx, shadow = shadow)

    /** A selected option pill or switched-on cell: metallic pink fill. */
    fun on(cut: Float = 0f, slant: Float = 0f, shadow: Float = 0f) = CutDrawable(metal, cutTR = cut, slant = slant, shadow = shadow)

    /** An unselected option pill or switched-off cell. */
    fun off(cut: Float = 0f, slant: Float = 0f, shadow: Float = 0f) = CutDrawable(intArrayOf(plane3), cutTR = cut, slant = slant, shadow = shadow)

    /** The dialog plane: dark with the metallic strip on top and the top-right corner cut. */
    fun dialog(cut: Float, stripPx: Float) = CutDrawable(intArrayOf(plane), cutTR = cut, strip = stripPx)

    /** A slanted tab with the same pink edge as the fields. */
    fun chip(slant: Float, edgePx: Float, shadow: Float) = CutDrawable(intArrayOf(plane2), slant = slant, border = edgePx, shadow = shadow)

    /** A card plane for the Layouts and Sets pages: cut corner, bar down the left edge. `lit` marks the chosen one. */
    fun card(cut: Float, shadow: Float, bar: Float, lit: Boolean = false) =
        CutDrawable(intArrayOf(if (lit) plane2 else plane), cutTR = cut, leftBar = bar, shadow = shadow, border = if (lit) 2f else 0f)

    /** The edit dock: plane with the strip on top and the top-left corner cut; its shadow falls upward. */
    fun dock(cut: Float, stripPx: Float, shadow: Float) =
        CutDrawable(intArrayOf(plane), cutTL = cut, strip = stripPx, shadow = shadow, shadowUp = true)

    /** A dock tile. Tiles added by a template get a steadier, brighter edge. */
    fun tile(cut: Float, edgePx: Float, shadow: Float, steady: Boolean) =
        CutDrawable(intArrayOf(plane2), cutTR = cut, border = edgePx, shadow = shadow, steadyEdge = steady)

    /** Turns off child clipping through a view tree so shadows can spill past a view's edge. Scroll views keep clipping. */
    fun noClip(v: View) {
        if (v is android.view.ViewGroup) {
            if (v !is android.widget.ScrollView && v !is android.widget.HorizontalScrollView) v.clipChildren = false
            for (i in 0 until v.childCount) noClip(v.getChildAt(i))
        }
    }

    /** Raises a view so it casts a shadow with a pink tint. Shapes with cut corners need Android 11 or newer; older phones just skip it. */
    fun lift(v: View, d: CutDrawable, elevationPx: Float) {
        if (Build.VERSION.SDK_INT < 30) return
        v.elevation = elevationPx
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                if (view.width > 0 && view.height > 0) outline.setConvexPath(d.pathFor(view.width.toFloat(), view.height.toFloat()))
            }
        }
        v.outlineSpotShadowColor = Color.BLACK
        v.outlineAmbientShadowColor = Color.argb(255, 255, 79, 157)
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
    private val shadow: Float = 0f,
    private val leftBar: Float = 0f,
    private val shadowUp: Boolean = false,
    private val steadyEdge: Boolean = false,
) : Drawable() {
    private var shadowBmp: Bitmap? = null
    private var shadowPad = 0

    override fun onBoundsChange(b: android.graphics.Rect) {
        shadowBmp = null
    }

    /** A dark blurred copy of the shape, a little lower than the shape, with a faint pink glow around it. Drawn outside the view's own bounds. */
    private fun makeShadow(w: Int, h: Int): Bitmap {
        val pad = (shadow * 3f).toInt()
        shadowPad = pad
        val bmp = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val path = pathFor(w.toFloat(), h.toFloat())
        p.color = Color.argb(56, 255, 79, 157)
        p.maskFilter = BlurMaskFilter(shadow * 2f, BlurMaskFilter.Blur.NORMAL)
        c.save(); c.translate(pad.toFloat(), pad.toFloat()); c.drawPath(path, p); c.restore()
        p.color = Color.argb(240, 0, 0, 0)
        p.maskFilter = BlurMaskFilter(shadow, BlurMaskFilter.Blur.NORMAL)
        c.save(); c.translate(pad + shadow * 0.2f, pad + shadow * (if (shadowUp) -0.9f else 0.9f)); c.drawPath(path, p); c.restore()
        return bmp
    }

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
        if (shadow > 0f) {
            val sb = shadowBmp ?: makeShadow(w.toInt(), h.toInt()).also { shadowBmp = it }
            c.drawBitmap(sb, -shadowPad.toFloat(), -shadowPad.toFloat(), null)
        }
        if (border > 0f) {
            edge.shader = if (steadyEdge) null else LinearGradient(0f, 0f, w, h, intArrayOf(
                Color.argb(235, 255, 107, 179), Color.argb(46, 255, 107, 179), Color.argb(110, 255, 107, 179),
            ), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            if (steadyEdge) edge.color = Color.argb(200, 255, 79, 157)
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
        if (leftBar > 0f) {
            bar.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#ff5fa8"), Theme.deep, Shader.TileMode.CLAMP)
            c.save()
            c.clipPath(body)
            c.drawRect(0f, 0f, leftBar, h, bar)
            c.restore()
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** The diagonal metallic bands in the top-right corner, drawn behind the top bar. */
class BandsView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sh = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) }

    override fun onDraw(c: Canvas) {
        c.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        val d = resources.displayMetrics.density
        // x from the right edge (dp, on a 390 dp wide screen), centre y, thickness
        val bands = arrayOf(floatArrayOf(125f, -8f, 18f), floatArrayOf(86f, -8f, 11f), floatArrayOf(48f, -6f, 6f))
        for (b in bands) {
            val x = width - b[0] * d
            val y = b[1] * d
            val th = b[2] * d
            c.save()
            c.translate(x, y)
            c.rotate(28f)
            c.drawRect(0f, -th / 2f + 2f * d, 700f * d, th / 2f + 4f * d, sh)
            p.shader = LinearGradient(0f, -th / 2f, 0f, th / 2f, intArrayOf(
                Theme.deep, Color.parseColor("#ff5fa8"), Theme.crimson, Theme.deep,
            ), floatArrayOf(0f, 0.45f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, -th / 2f, 700f * d, th / 2f, p)
            c.restore()
        }
    }
}
