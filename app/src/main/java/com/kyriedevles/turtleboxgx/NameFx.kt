package com.kyriedevles.turtleboxgx

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A card name style, ported from lauqerm's YGOcarder (Name Style). A card set stores it in one value:
 * "auto", "predefined|<preset name>" or "custom|key=value;key=value".
 *
 * Keys: fill, font, shadow (0/1), shcolor, shx, shy, shblur, outline (0/1), olcolor, olw, olx, oly (copies 2..5 of
 * shadows, outlines and inner shadows add a suffix, with shcount / olcount / iscount), ishadow + isx isy isblur iscolor
 * isopacity, iglow + igblur igcolor igopacity, glow + glblur glspread glopacity glcolor,
 * grad (0/1), gangle, gstops ("0.000|#eef10b,1.000|#7e20cf"), emboss (0/1), epitch, eyaw, ethick, pattern.
 * All sizes are in YGOcarder pixels (a 813 px wide card) and are scaled to the layout.
 */
class NameStyle(var mode: String = "auto", var preset: String = "", var v: LinkedHashMap<String, String> = LinkedHashMap()) {
    fun serialize(): String = when (mode) {
        "predefined" -> "predefined|$preset"
        "custom" -> "custom|" + v.entries.joinToString(";") { it.key + "=" + it.value }
        else -> "auto"
    }

    companion object {
        fun parse(raw: String): NameStyle {
            val s = raw.trim()
            if (s.startsWith("predefined|")) return NameStyle("predefined", s.substringAfter('|'))
            if (s.startsWith("custom")) return NameStyle("custom", "", kv(s.substringAfter('|', "")))
            return NameStyle()
        }

        fun kv(s: String): LinkedHashMap<String, String> {
            val m = LinkedHashMap<String, String>()
            for (part in s.split(';')) {
                val i = part.indexOf('=')
                if (i > 0) m[part.substring(0, i).trim()] = part.substring(i + 1).trim()
            }
            return m
        }

        /** The values to draw with, or null for "auto" (plain text in the layout's own colour and font). */
        fun resolve(raw: String, layout: Layout, user: String = ""): Map<String, String>? {
            val st = parse(raw)
            return when (st.mode) {
                "custom" -> st.v
                "predefined" -> user.split('\n').firstOrNull { it.substringBefore('\t') == st.preset && it.contains('\t') }?.let { kv(it.substringAfter('\t')) }
                    ?: layout.datas["namepresets"]?.items?.firstOrNull { it.first == st.preset }?.let { kv(it.second) }
                else -> null
            }
        }
    }
}

object NameFx {
    private fun col(s: String?, d: Int): Int = try {
        Color.parseColor(s)
    } catch (e: Exception) {
        d
    }

    /** One row/column of the exact squared distance transform (Felzenszwalb and Huttenlocher). */
    private fun dt1(f: FloatArray, n: Int, out: FloatArray, v: IntArray, z: FloatArray) {
        var k = 0
        v[0] = 0
        z[0] = -1e20f
        z[1] = 1e20f
        for (q in 1 until n) {
            var s = ((f[q] + q.toFloat() * q) - (f[v[k]] + v[k].toFloat() * v[k])) / (2f * q - 2f * v[k])
            while (s <= z[k]) {
                k--
                s = ((f[q] + q.toFloat() * q) - (f[v[k]] + v[k].toFloat() * v[k])) / (2f * q - 2f * v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = 1e20f
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val d = (q - v[k]).toFloat()
            out[q] = d * d + f[v[k]]
        }
    }

    /** Squared distance from every pixel to the nearest empty pixel (0 for empty pixels). */
    private fun distance(fg: BooleanArray, w: Int, h: Int): FloatArray {
        val big = 1e20f
        val g = FloatArray(w * h)
        val n = max(w, h)
        val f = FloatArray(n)
        val o = FloatArray(n)
        val v = IntArray(n + 1)
        val z = FloatArray(n + 2)
        for (x in 0 until w) {
            for (y in 0 until h) f[y] = if (fg[y * w + x]) big else 0f
            dt1(f, h, o, v, z)
            for (y in 0 until h) g[y * w + x] = o[y]
        }
        for (y in 0 until h) {
            for (x in 0 until w) f[x] = g[y * w + x]
            dt1(f, w, o, v, z)
            for (x in 0 until w) g[y * w + x] = o[x]
        }
        return g
    }

    /**
     * Emboss, after YGOcarder: distance to the edge becomes a height map, the height map a normal map (Sobel),
     * the normals are smoothed with a bilateral filter, then lit by one white light. Works in place.
     * [pitch] and [yaw] are used in the same order as YGOcarder uses them (pitch is the horizontal angle).
     */
    private fun emboss(bm: Bitmap, pitch: Float, yaw: Float) {
        val w = bm.width
        val h = bm.height
        val px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        val fg = BooleanArray(w * h) { px[it] != 0 }
        val d2 = distance(fg, w, h)
        val hm = FloatArray(w * h) { min(d2[it] * 31f, 255f) / 255f }

        // Normal map, encoded 0..255 like the original image would be.
        val enc = Array(3) { IntArray(w * h) }
        fun hh(x: Int, y: Int): Float = hm[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (hh(x + 1, y - 1) + 2f * hh(x + 1, y) + hh(x + 1, y + 1)) - (hh(x - 1, y - 1) + 2f * hh(x - 1, y) + hh(x - 1, y + 1))
                val dy = (hh(x - 1, y + 1) + 2f * hh(x, y + 1) + hh(x + 1, y + 1)) - (hh(x - 1, y - 1) + 2f * hh(x, y - 1) + hh(x + 1, y - 1))
                val nx = -255f * dx
                val ny = -255f * dy
                val nz = 205.2f
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                val i = y * w + x
                enc[0][i] = ((nx / len * 0.5f + 0.5f) * 255f).toInt().coerceIn(0, 255)
                enc[1][i] = ((ny / len * 0.5f + 0.5f) * 255f).toInt().coerceIn(0, 255)
                enc[2][i] = ((nz / len * 0.5f + 0.5f) * 255f).toInt().coerceIn(0, 255)
            }
        }

        // Bilateral filter (window 9, colour sigma 70, space sigma 20), only where there is text.
        val r = 4
        val sw = FloatArray(81)
        for (j in -r..r) for (i in -r..r) sw[(j + r) * 9 + (i + r)] = Math.exp(-((i * i + j * j).toDouble()) / (2.0 * 20.0 * 20.0)).toFloat()
        val cw = FloatArray(256) { Math.exp(-(it.toDouble() * it) / (2.0 * 70.0 * 70.0)).toFloat() }

        // Light direction, as in YGOcarder: horizontal angle from the "pitch" value, height from "yaw".
        val ha = Math.toRadians((pitch + 90f).toDouble())
        val va = Math.toRadians((yaw + 90f).toDouble())
        var lx = Math.round(cos(ha) * cos(va) * 100.0) / 100.0
        var ly = Math.round(sin(ha) * cos(va) * 100.0) / 100.0
        var lz = Math.round(sin(va) * 100.0) / 100.0
        val mag = sqrt(lx * lx + ly * ly + lz * lz)
        if (mag < 1e-6) {
            lx = 1.0; ly = 0.0; lz = 0.0
        } else {
            lx /= mag; ly /= mag; lz /= mag
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!fg[i]) continue
                val r0 = enc[0][i]
                val g0 = enc[1][i]
                val b0 = enc[2][i]
                var rs = 0f
                var gs = 0f
                var bs = 0f
                var ws = 0f
                for (j in -r..r) {
                    val yy = y + j
                    if (yy < 0 || yy >= h) continue
                    for (k in -r..r) {
                        val xx = x + k
                        if (xx < 0 || xx >= w) continue
                        val n = yy * w + xx
                        val a = enc[0][n]
                        val b = enc[1][n]
                        val c = enc[2][n]
                        val wt = sw[(j + r) * 9 + (k + r)] * cw[abs(a - r0)] * cw[abs(b - g0)] * cw[abs(c - b0)]
                        rs += a * wt
                        gs += b * wt
                        bs += c * wt
                        ws += wt
                    }
                }
                if (ws <= 0f) continue
                val nx = rs / ws / 127.5f - 1f
                val ny = gs / ws / 127.5f - 1f
                val nz = bs / ws / 127.5f - 1f
                val base = nx * lx + ny * ly + nz * lz
                val intensity = (base / 2.0 * 1.8).coerceIn(-0.9, 0.9).toFloat()
                val p = px[i]
                val a = p ushr 24
                val rr = ((p shr 16) and 255) + Math.round(255f * intensity)
                val gg = ((p shr 8) and 255) + Math.round(255f * intensity)
                val bb = (p and 255) + Math.round(255f * intensity)
                px[i] = (a shl 24) or (rr.coerceIn(0, 255) shl 16) or (gg.coerceIn(0, 255) shl 8) or bb.coerceIn(0, 255)
            }
        }
        bm.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun parseStops(stops: String): List<Pair<Float, Int>> {
        val list = ArrayList<Pair<Float, Int>>()
        for (part in stops.split(',')) {
            val p = part.split('|')
            if (p.size == 2) {
                val off = p[0].trim().toFloatOrNull() ?: continue
                list.add(off.coerceIn(0f, 1f) to col(p[1].trim(), Color.BLACK))
            }
        }
        list.sortBy { it.first }
        return list
    }

    /** Radial gradient overlay: the first stop in the middle, the last at the corners. */
    private fun radialShader(stops: String, r: RectF): Shader? {
        val list = parseStops(stops)
        if (list.size < 2) return null
        val rad = (Math.hypot(r.width().toDouble(), r.height().toDouble()) / 2.0).toFloat().coerceAtLeast(1f)
        return RadialGradient(r.centerX(), r.centerY(), rad, IntArray(list.size) { list[it].second }, FloatArray(list.size) { list[it].first }, Shader.TileMode.CLAMP)
    }

    private fun gradientShader(stops: String, angle: Float, left: Float, top: Float, right: Float, bottom: Float): Shader? {
        val list = parseStops(stops)
        if (list.size < 2) return null
        val a = Math.toRadians(angle.toDouble())
        val w = right - left
        val h = bottom - top
        val half = (w / 2f * abs(sin(a)) + h / 2f * abs(cos(a))).toFloat()
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        // Angle 0 runs top to bottom, 90 right to left, 180 bottom to top, as in YGOcarder.
        val sx = cx + half * sin(a).toFloat()
        val sy = cy - half * cos(a).toFloat()
        val ex = cx - half * sin(a).toFloat()
        val ey = cy + half * cos(a).toFloat()
        if (abs(ex - sx) < 0.01f && abs(ey - sy) < 0.01f) return null
        return LinearGradient(sx, sy, ex, ey, IntArray(list.size) { list[it].second }, FloatArray(list.size) { list[it].first }, Shader.TileMode.CLAMP)
    }

    /**
     * Draws [text] at ([x], [y]) (the baseline) with the style [m]. [k] is the canvas scale the card is rendered at,
     * [layoutW] the layout width; effects are built on an offscreen layer at the real pixel size.
     */
    fun draw(
        c: Canvas,
        text: String,
        x: Float,
        y: Float,
        base: Paint,
        rect: RectF,
        drawW: Float,
        m: Map<String, String>,
        k: Float,
        layoutW: Float,
        layout: Layout,
        assets: Assets,
    ) {
        fun fl(key: String, d: Float) = m[key]?.toFloatOrNull() ?: d
        fun on(key: String) = m[key] == "1"
        val lu = layoutW / 813f // layout px per style pixel
        val u = k * lu // layer px per style pixel
        val thick = if (on("emboss")) fl("ethick", 0f).coerceAtLeast(0f) * lu else 0f
        val pad = Math.ceil((thick * k / 2f + effectReach(m, u) + 12f).toDouble()).toInt()
        val lw = Math.ceil((rect.width() * k).toDouble()).toInt() + 2 * pad
        val lh = Math.ceil((rect.height() * k).toDouble()).toInt() + 2 * pad
        val fillColor = col(m["fill"], base.color)
        if (lw <= 0 || lh <= 0 || lw.toLong() * lh > 9_000_000L) {
            val p = Paint(base)
            p.color = fillColor
            c.drawText(text, x, y, p)
            return
        }

        val bounds = Rect()
        base.getTextBounds(text, 0, text.length, bounds)
        val top = y + bounds.top
        val bottom = y + bounds.bottom

        fun place(cv: Canvas) {
            cv.translate(pad.toFloat(), pad.toFloat())
            cv.scale(k, k)
            cv.translate(-rect.left, -rect.top)
        }

        var layer = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        place(lc)
        val fp = Paint(base)
        fp.color = fillColor
        if (thick > 0f) {
            fp.style = Paint.Style.FILL_AND_STROKE
            fp.strokeWidth = thick
            fp.strokeJoin = Paint.Join.ROUND
        }
        lc.drawText(text, x, y, fp)
        compose(c, layer, lw, lh, pad, k, rect, m, layout, assets, RectF(x, top - 1f, x + drawW, bottom + 1f), u)
    }

    /**
     * The same effects for a whole block of text that draws itself (a box of wrapped lines, with its own colours per
     * word): [drawContent] draws it in card coordinates; the effects go around everything it draws inside [rect].
     * A style's fill colour or gradient replaces the text's colours, as it does for one line.
     */
    fun drawBlock(
        c: Canvas,
        rect: RectF,
        m: Map<String, String>,
        k: Float,
        layoutW: Float,
        layout: Layout,
        assets: Assets,
        /** False for pictures (image layers): the style's text colour does not paint over them. */
        recolor: Boolean = true,
        /** Where gradients and patterns are stretched (default [rect]); one line of text passes its letters' bounds. */
        patRect: RectF? = null,
        drawContent: (Canvas) -> Unit,
    ) {
        fun fl(key: String, d: Float) = m[key]?.toFloatOrNull() ?: d
        fun on(key: String) = m[key] == "1"
        val lu = layoutW / 813f
        val u = k * lu
        val pad = Math.ceil((effectReach(m, u) + 12f).toDouble()).toInt()
        val lw = Math.ceil((rect.width() * k).toDouble()).toInt() + 2 * pad
        val lh = Math.ceil((rect.height() * k).toDouble()).toInt() + 2 * pad
        if (lw <= 0 || lh <= 0 || lw.toLong() * lh > 9_000_000L) return drawContent(c)
        val layer = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        lc.translate(pad.toFloat(), pad.toFloat())
        lc.scale(k, k)
        lc.translate(-rect.left, -rect.top)
        drawContent(lc)
        // The style's text colour over the letters only.
        val fill = m["fill"]?.takeIf { it.isNotBlank() && recolor }
        if (fill != null) {
            val fp = Paint(Paint.ANTI_ALIAS_FLAG)
            fp.color = col(fill, Color.BLACK)
            fp.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            lc.drawRect(RectF(rect.left - pad / k, rect.top - pad / k, rect.right + pad / k, rect.bottom + pad / k), fp)
        }
        compose(c, layer, lw, lh, pad, k, rect, m, layout, assets, patRect ?: rect, u)
    }

    /** Copy suffixes of an effect that can be added several times ("" is the first, then "2".."5"), top first. */
    fun copies(m: Map<String, String>, countKey: String): List<String> {
        val n = (m[countKey]?.toIntOrNull() ?: 1).coerceIn(1, MAX_COPIES)
        return (1..n).map { if (it == 1) "" else it.toString() }
    }

    const val MAX_COPIES = 5

    /** How far the effects reach past the letters, in layer px (the offscreen layer's margin). */
    private fun effectReach(m: Map<String, String>, u: Float): Float {
        fun fl(key: String, d: Float) = m[key]?.toFloatOrNull() ?: d
        var r = 0f
        if (m["outline"] == "1") for (sf in copies(m, "olcount"))
            r = max(r, fl("olw$sf", 0f).coerceAtLeast(0f) / 2f * u + max(abs(fl("olx$sf", 0f)), abs(fl("oly$sf", 0f))) * u)
        var sh = 0f
        if (m["shadow"] == "1") for (sf in copies(m, "shcount"))
            sh = max(sh, (fl("shblur$sf", 0f).coerceAtLeast(0f) * 2f + fl("shspread$sf", 0f).coerceAtLeast(0f)) * u + max(abs(fl("shx$sf", 0f)), abs(fl("shy$sf", 0f))) * u)
        var gl = 0f
        if (m["glow"] == "1") gl = ((m["glblur"]?.toFloatOrNull() ?: 8f) * 2f + (m["glspread"]?.toFloatOrNull() ?: 0f)).coerceAtLeast(0f) * u
        return r + max(sh, gl)
    }

    /** The letters' shape [mask] grown by [radius] and moved by ([dx], [dy]), stamped in [color] onto [cv]. */
    private fun stamp(cv: Canvas, mask: Bitmap, radius: Float, dx: Float, dy: Float, color: Int) {
        val op = Paint(Paint.FILTER_BITMAP_FLAG)
        op.color = color
        cv.drawBitmap(mask, dx, dy, op)
        if (radius <= 0.01f) return
        for (ring in 1..3) {
            val rr = radius * ring / 3f
            val n = min(48, max(8, Math.round(rr * 4f)))
            for (i in 0 until n) {
                val a = 2.0 * Math.PI * i / n
                cv.drawBitmap(mask, dx + (rr * cos(a)).toFloat(), dy + (rr * sin(a)).toFloat(), op)
            }
        }
    }

    /**
     * Satin (Photoshop's): the letters' shape moved both ways along an angle, where the two copies differ, blurred,
     * painted inside the letters. Gives the silky inner shading of metallic text.
     */
    private fun satin(layer: Bitmap, lw: Int, lh: Int, u: Float, m: Map<String, String>) {
        fun fl(key: String, d: Float) = m[key]?.toFloatOrNull() ?: d
        val mask = layer.extractAlpha()
        val d = fl("satdist", 11f) * u
        val a = Math.toRadians(fl("satangle", 19f).toDouble())
        val dx = (d * cos(a)).toFloat()
        val dy = (d * sin(a)).toFloat()
        val t = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        val tc = Canvas(t)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.color = Color.BLACK
        tc.drawBitmap(mask, dx, dy, p)
        tc.drawBitmap(mask, -dx, -dy, Paint(p).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.XOR) })
        var src = t
        if (m["satinvert"] == "1") {
            val inv = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
            val ic = Canvas(inv)
            ic.drawColor(Color.BLACK)
            ic.drawBitmap(t, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            src = inv
        }
        val bp = Paint()
        val size = fl("satsize", 14f).coerceAtLeast(0f) * u
        if (size > 0.5f) bp.maskFilter = BlurMaskFilter(size, BlurMaskFilter.Blur.NORMAL)
        val off = IntArray(2)
        val am = src.extractAlpha(bp, off)
        val cc = col(m["satcolor"], Color.BLACK)
        val sp = Paint(Paint.FILTER_BITMAP_FLAG)
        sp.color = cc
        sp.alpha = (Color.alpha(cc) * fl("satopacity", 50f).coerceIn(0f, 100f) / 100f).toInt()
        sp.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        Canvas(layer).drawBitmap(am, off[0].toFloat(), off[1].toFloat(), sp)
    }

    /**
     * Inner shadow / inner glow (Photoshop's): the outside of the letters, blurred (and moved for a shadow), painted
     * only inside the letters, on top of their fill.
     */
    private fun inner(layer: Bitmap, lw: Int, lh: Int, blur: Float, dx: Float, dy: Float, color: Int, opacity: Float) {
        // Everything that is not letter, as a mask.
        val outside = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        val oc = Canvas(outside)
        oc.drawColor(Color.BLACK)
        val cut = Paint()
        cut.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        oc.drawBitmap(layer, 0f, 0f, cut)
        val bp = Paint()
        if (blur > 0.5f) bp.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        val off = IntArray(2)
        val a = outside.extractAlpha(bp, off)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.color = color
        p.alpha = (Color.alpha(color) * opacity.coerceIn(0f, 100f) / 100f).toInt()
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        Canvas(layer).drawBitmap(a, off[0] + dx, off[1] + dy, p)
    }

    /** Pattern, emboss, outline and shadow around the letters drawn on [layer0], then the result onto [c]. */
    private fun compose(
        c: Canvas, layer0: Bitmap, lw: Int, lh: Int, pad: Int, k: Float, rect: RectF, m: Map<String, String>,
        layout: Layout, assets: Assets, patRect: RectF,
        u: Float,
    ) {
        fun fl(key: String, d: Float) = m[key]?.toFloatOrNull() ?: d
        fun on(key: String) = m[key] == "1"
        fun place(cv: Canvas) {
            cv.translate(pad.toFloat(), pad.toFloat())
            cv.scale(k, k)
            cv.translate(-rect.left, -rect.top)
        }
        var layer = layer0

        // Pattern: an image from the asset zip laid over the text with a blend mode, kept inside the letters.
        val pn = m["pattern"].orEmpty()
        if (pn.isNotEmpty() && pn != "none") {
            // A namepatterns label, else a picture file (one imported into the card, "path|mode").
            val item = layout.datas["namepatterns"]?.items?.firstOrNull { it.first == pn }
            val parts = (item?.second ?: pn).split('|')
            val pbmp = parts.getOrNull(0)?.let { assets.bitmap(it) }
            if (pbmp != null) {
                val mode = when (parts.getOrNull(1)) {
                    "multiply" -> PorterDuff.Mode.MULTIPLY
                    "add" -> PorterDuff.Mode.ADD
                    else -> PorterDuff.Mode.SRC_ATOP
                }
                val tmp = layer.copy(Bitmap.Config.ARGB_8888, true)
                val tc = Canvas(tmp)
                place(tc)
                val pp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                pp.xfermode = PorterDuffXfermode(mode)
                tc.drawBitmap(pbmp, null, patRect, pp)
                val mp = Paint()
                mp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                Canvas(tmp).drawBitmap(layer, 0f, 0f, mp)
                layer = tmp
            }
        }

        // Overlays inside the letters (Photoshop's order, bottom up): gradient, colour, satin.
        val all = RectF(rect.left - pad / k, rect.top - pad / k, rect.right + pad / k, rect.bottom + pad / k)
        fun overlay(p: Paint) {
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            val cv = Canvas(layer)
            place(cv)
            cv.drawRect(all, p)
        }
        if (on("grad")) {
            val sh = if (m["gstyle"] == "radial") radialShader(m["gstops"] ?: "", patRect)
            else gradientShader(m["gstops"] ?: "", fl("gangle", 0f), patRect.left, patRect.top, patRect.right, patRect.bottom)
            if (sh != null) overlay(Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sh; alpha = (255 * fl("gopacity", 100f).coerceIn(0f, 100f) / 100f).toInt() })
        }
        if (on("coverlay")) {
            val cc = col(m["cocolor"], Color.RED)
            overlay(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cc; alpha = (Color.alpha(cc) * fl("coopacity", 100f).coerceIn(0f, 100f) / 100f).toInt() })
        }
        if (on("satin")) satin(layer, lw, lh, u, m)

        if (on("emboss")) emboss(layer, fl("epitch", 90f), fl("eyaw", 90f))

        // Inner shadows and inner glow, inside the letters on top of their fill.
        if (on("ishadow")) for (sf in copies(m, "iscount").reversed()) {
            inner(layer, lw, lh, fl("isblur$sf", 4f).coerceAtLeast(0f) * u, fl("isx$sf", 0f) * u, fl("isy$sf", 0f) * u,
                col(m["iscolor$sf"], Color.BLACK), fl("isopacity$sf", 75f))
        }
        if (on("iglow")) inner(layer, lw, lh, fl("igblur", 6f).coerceAtLeast(0f) * u, 0f, 0f, col(m["igcolor"], Color.WHITE), fl("igopacity", 75f))

        // Outlines (Photoshop's strokes, outside): each is the letters' shape grown by its width; the first is on top.
        // Strokes: outside (behind the letters), inside (over them, within their edge) or centre (half of each).
        var shape = layer
        if (on("outline")) {
            val mask = layer.extractAlpha()
            val outsideMask: Bitmap by lazy {
                val o = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
                val oc = Canvas(o)
                oc.drawColor(Color.BLACK)
                oc.drawBitmap(layer, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
                o.extractAlpha()
            }
            val behind = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
            val bc = Canvas(behind)
            val above = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
            val ac = Canvas(above)
            for (sf in copies(m, "olcount").reversed()) {
                // How far the stroke reaches from the edge (the same as the outside outline always was).
                val full = fl("olw$sf", 0f).coerceAtLeast(0f) / 2f * u
                if (full <= 0.02f) continue
                val pos = m["olpos$sf"] ?: "out"
                val color = col(m["olcolor$sf"], Color.BLACK)
                val alpha = (255 * fl("olopacity$sf", 100f).coerceIn(0f, 100f) / 100f).toInt()
                val dx = fl("olx$sf", 0f) * u
                val dy = fl("oly$sf", 0f) * u
                val outW = when (pos) { "in" -> 0f; "center" -> full / 2f; else -> full }
                val inW = when (pos) { "in" -> full; "center" -> full / 2f; else -> 0f }
                if (pos != "in") {
                    val t = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
                    stamp(Canvas(t), mask, outW, dx, dy, color)
                    bc.drawBitmap(t, 0f, 0f, Paint().apply { this.alpha = alpha })
                }
                if (inW > 0.01f) {
                    // The outside grown inwards by the width, kept inside the letters.
                    val t = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
                    val tc = Canvas(t)
                    stamp(tc, outsideMask, inW, dx, dy, color)
                    tc.drawBitmap(layer, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                    ac.drawBitmap(t, 0f, 0f, Paint().apply { this.alpha = alpha })
                }
            }
            val s = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
            val sc = Canvas(s)
            sc.drawBitmap(behind, 0f, 0f, null)
            sc.drawBitmap(layer, 0f, 0f, null)
            sc.drawBitmap(above, 0f, 0f, null)
            shape = s
        }

        val fin = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        val fc = Canvas(fin)
        // Drop shadows, bottom one first; the first is on top.
        if (on("shadow")) for (sf in copies(m, "shcount").reversed()) {
            val blur = fl("shblur$sf", 0f).coerceAtLeast(0f) * u
            val spread = fl("shspread$sf", 0f).coerceAtLeast(0f) * u
            var src = shape
            if (spread > 0.01f) {
                src = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
                stamp(Canvas(src), shape.extractAlpha(), spread, 0f, 0f, Color.BLACK)
            }
            val bp = Paint()
            if (blur > 0.5f) bp.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            val off = IntArray(2)
            val a = src.extractAlpha(bp, off)
            val sp = Paint(Paint.FILTER_BITMAP_FLAG)
            val sc0 = col(m["shcolor$sf"], Color.BLACK)
            sp.color = sc0
            sp.alpha = (Color.alpha(sc0) * fl("shopacity$sf", 100f).coerceIn(0f, 100f) / 100f).toInt()
            fc.drawBitmap(a, off[0] + fl("shx$sf", 0f) * u, off[1] + fl("shy$sf", 0f) * u, sp)
        }
        // Outer glow (Photoshop's): the letters' shape spread by [glspread], blurred by [glblur], in [glcolor], behind them.
        if (on("glow")) {
            val gBlur = fl("glblur", 8f).coerceAtLeast(0f) * u
            val gSpread = fl("glspread", 0f).coerceAtLeast(0f) * u
            var src = shape
            if (gSpread > 0.01f) {
                val sp = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
                val scv = Canvas(sp)
                val mask = shape.extractAlpha()
                val mp = Paint(Paint.FILTER_BITMAP_FLAG)
                scv.drawBitmap(mask, 0f, 0f, mp)
                for (ring in 1..3) {
                    val rr = gSpread * ring / 3f
                    val n = min(48, max(8, Math.round(rr * 4f)))
                    for (i in 0 until n) {
                        val a = 2.0 * Math.PI * i / n
                        scv.drawBitmap(mask, (rr * cos(a)).toFloat(), (rr * sin(a)).toFloat(), mp)
                    }
                }
                src = sp
            }
            val bp = Paint()
            if (gBlur > 0.5f) bp.maskFilter = BlurMaskFilter(gBlur, BlurMaskFilter.Blur.NORMAL)
            val off = IntArray(2)
            val a = src.extractAlpha(bp, off)
            val gp = Paint(Paint.FILTER_BITMAP_FLAG)
            gp.color = col(m["glcolor"], Color.WHITE)
            fl("glopacity", 100f).coerceIn(0f, 100f).let { gp.alpha = (Color.alpha(gp.color) * it / 100f).toInt() }
            fc.drawBitmap(a, off[0].toFloat(), off[1].toFloat(), gp)
        }
        fc.drawBitmap(shape, 0f, 0f, null)

        c.save()
        c.translate(rect.left - pad / k, rect.top - pad / k)
        c.scale(1f / k, 1f / k)
        c.drawBitmap(fin, 0f, 0f, null)
        c.restore()
    }
}
