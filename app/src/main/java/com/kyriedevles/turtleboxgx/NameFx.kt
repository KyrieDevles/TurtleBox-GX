package com.kyriedevles.turtleboxgx

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
 * Keys: fill, font, shadow (0/1), shcolor, shx, shy, shblur, outline (0/1), olcolor, olw, olx, oly,
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
                val intensity = (base / 2f * 1.8f).coerceIn(-0.9f, 0.9f).toFloat()
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

    private fun gradientShader(stops: String, angle: Float, left: Float, top: Float, right: Float, bottom: Float): Shader? {
        val list = ArrayList<Pair<Float, Int>>()
        for (part in stops.split(',')) {
            val p = part.split('|')
            if (p.size == 2) {
                val off = p[0].trim().toFloatOrNull() ?: continue
                list.add(off.coerceIn(0f, 1f) to col(p[1].trim(), Color.BLACK))
            }
        }
        if (list.size < 2) return null
        list.sortBy { it.first }
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
        val olR = if (on("outline")) fl("olw", 0f).coerceAtLeast(0f) / 2f * u else 0f
        val olx = if (on("outline")) fl("olx", 0f) * u else 0f
        val oly = if (on("outline")) fl("oly", 0f) * u else 0f
        val shBlur = if (on("shadow")) fl("shblur", 0f).coerceAtLeast(0f) * u else 0f
        val shx = if (on("shadow")) fl("shx", 0f) * u else 0f
        val shy = if (on("shadow")) fl("shy", 0f) * u else 0f
        val pad = Math.ceil((thick * k / 2f + olR + max(abs(olx), abs(oly)) + shBlur * 2f + max(abs(shx), abs(shy)) + 12f).toDouble()).toInt()
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
        if (on("grad")) {
            fp.shader = gradientShader(m["gstops"] ?: "", fl("gangle", 0f), x, top, x + drawW, bottom)
        }
        if (thick > 0f) {
            fp.style = Paint.Style.FILL_AND_STROKE
            fp.strokeWidth = thick
            fp.strokeJoin = Paint.Join.ROUND
        }
        lc.drawText(text, x, y, fp)

        // Pattern: an image from the asset zip laid over the text with a blend mode, kept inside the letters.
        val pn = m["pattern"].orEmpty()
        if (pn.isNotEmpty() && pn != "none") {
            val item = layout.datas["namepatterns"]?.items?.firstOrNull { it.first == pn }
            val parts = item?.second?.split('|')
            val pbmp = parts?.getOrNull(0)?.let { assets.bitmap(it) }
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
                tc.drawBitmap(pbmp, null, RectF(x, top - 1f, x + drawW, bottom + 1f), pp)
                val mp = Paint()
                mp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                Canvas(tmp).drawBitmap(layer, 0f, 0f, mp)
                layer = tmp
            }
        }

        if (on("emboss")) emboss(layer, fl("epitch", 90f), fl("eyaw", 90f))

        // Outline: copies of the letters' shape stamped around a circle, behind the text.
        var shape = layer
        if (olR > 0.01f) {
            val o = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
            val oc = Canvas(o)
            val mask = layer.extractAlpha()
            val op = Paint(Paint.FILTER_BITMAP_FLAG)
            op.color = col(m["olcolor"], Color.BLACK)
            oc.drawBitmap(mask, olx, oly, op)
            for (ring in 1..3) {
                val rr = olR * ring / 3f
                val n = min(48, max(8, Math.round(rr * 4f)))
                for (i in 0 until n) {
                    val a = 2.0 * Math.PI * i / n
                    oc.drawBitmap(mask, olx + (rr * cos(a)).toFloat(), oly + (rr * sin(a)).toFloat(), op)
                }
            }
            val s = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
            val sc = Canvas(s)
            sc.drawBitmap(o, 0f, 0f, null)
            sc.drawBitmap(layer, 0f, 0f, null)
            shape = s
        }

        val fin = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        val fc = Canvas(fin)
        if (on("shadow")) {
            val bp = Paint()
            if (shBlur > 0.5f) bp.maskFilter = BlurMaskFilter(shBlur, BlurMaskFilter.Blur.NORMAL)
            val off = IntArray(2)
            val a = shape.extractAlpha(bp, off)
            val sp = Paint(Paint.FILTER_BITMAP_FLAG)
            sp.color = col(m["shcolor"], Color.BLACK)
            fc.drawBitmap(a, off[0] + shx, off[1] + shy, sp)
        }
        fc.drawBitmap(shape, 0f, 0f, null)

        c.save()
        c.translate(rect.left - pad / k, rect.top - pad / k)
        c.scale(1f / k, 1f / k)
        c.drawBitmap(fin, 0f, 0f, null)
        c.restore()
    }
}
