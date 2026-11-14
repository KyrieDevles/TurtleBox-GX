package com.kyriedevles.turtleboxgx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Live editing of one layer on the card: drag to move, corner handles scale, edge handles stretch, the round
 * handle rotates, two fingers pinch to scale and rotate. With [grid] on, a fine grid is drawn and moves snap
 * to the card's edges and centre (and then to grid lines).
 */
class LayerEditor(val layer: Layer, val bmp: Bitmap, val cardW: Float, val cardH: Float) {
    var grid = false
    var lock = true
    var onChange: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null
    var onBegin: (() -> Unit)? = null
    var redraw: (() -> Unit)? = null

    fun changed() {
        redraw?.invoke()
        onChange?.invoke()
    }

    private enum class Mode { NONE, MOVE, ROT, CORNER, EDGE, PINCH }

    private var mode = Mode.NONE
    private var l0 = layer.copy()
    private var tx0 = 0f
    private var ty0 = 0f
    private var hsx = 0
    private var hsy = 0
    private var dist0 = 1f
    private var ang0 = 0f
    private var mx0 = 0f
    private var my0 = 0f
    private var guideX: Float? = null
    private var guideY: Float? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imgP = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private fun rad(d: Float) = Math.toRadians(d.toDouble())

    /** local (unrotated, centred) point of layer [l] to card coordinates. */
    private fun world(l: Layer, lx: Float, ly: Float): Pair<Float, Float> {
        val a = rad(l.rot)
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return (l.cx + lx * c - ly * s) to (l.cy + lx * s + ly * c)
    }

    private fun local(l: Layer, wx: Float, wy: Float): Pair<Float, Float> {
        val a = rad(-l.rot)
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        val dx = wx - l.cx
        val dy = wy - l.cy
        return (dx * c - dy * s) to (dx * s + dy * c)
    }

    // ---- drawing (view-space: dest is the card rectangle on screen) ----

    fun draw(c: Canvas, dest: RectF, density: Float) {
        val k = dest.width() / cardW
        fun sx(wx: Float) = dest.left + wx * k
        fun sy(wy: Float) = dest.top + wy * k
        val l = layer
        c.save()
        c.clipRect(dest)
        c.save()
        c.translate(sx(l.cx), sy(l.cy))
        c.rotate(l.rot)
        c.scale(if (l.mx) -1f else 1f, if (l.fy) -1f else 1f)
        imgP.alpha = (l.opacity.coerceIn(0f, 1f) * 255).toInt()
        c.drawBitmap(bmp, null, RectF(-l.w * k / 2f, -l.h * k / 2f, l.w * k / 2f, l.h * k / 2f), imgP)
        c.restore()
        if (grid) {
            val step = cardW / 40f
            line.strokeWidth = 1f
            for (i in 0..40) {
                line.color = if (i % 5 == 0) Color.argb(110, 255, 110, 199) else Color.argb(50, 255, 255, 255)
                c.drawLine(sx(i * step), dest.top, sx(i * step), dest.bottom, line)
            }
            var y = 0f
            var j = 0
            while (y <= cardH + 0.01f) {
                line.color = if (j % 5 == 0) Color.argb(110, 255, 110, 199) else Color.argb(50, 255, 255, 255)
                c.drawLine(dest.left, sy(y), dest.right, sy(y), line)
                y += step
                j++
            }
            line.color = Color.argb(200, 255, 20, 147)
            line.strokeWidth = 2f
            c.drawLine(sx(cardW / 2f), dest.top, sx(cardW / 2f), dest.bottom, line)
            c.drawLine(dest.left, sy(cardH / 2f), dest.right, sy(cardH / 2f), line)
        }
        guideX?.let { line.color = Color.CYAN; line.strokeWidth = 2f; c.drawLine(sx(it), dest.top, sx(it), dest.bottom, line) }
        guideY?.let { line.color = Color.CYAN; line.strokeWidth = 2f; c.drawLine(dest.left, sy(it), dest.right, sy(it), line) }
        c.restore()
        // outline and handles
        val hw = l.w / 2f
        val hh = l.h / 2f
        val corners = listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh).map { world(l, it.first, it.second) }
        line.color = Color.WHITE
        line.strokeWidth = 2f * density
        for (i in 0..3) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            c.drawLine(sx(a.first), sy(a.second), sx(b.first), sy(b.second), line)
        }
        val r = 7f * density
        fun dot(p: Pair<Float, Float>, color: Int) {
            fillP.color = Color.WHITE
            c.drawCircle(sx(p.first), sy(p.second), r, fillP)
            fillP.color = color
            c.drawCircle(sx(p.first), sy(p.second), r - 2.5f * density, fillP)
        }
        for (p in corners) dot(p, Color.parseColor("#ff1493"))
        for ((ex, ey) in listOf(0f to -hh, hw to 0f, 0f to hh, -hw to 0f)) dot(world(l, ex, ey), Color.parseColor("#ff6ec7"))
        val top = world(l, 0f, -hh)
        val rh = world(l, 0f, -hh - 38f * density / k)
        line.strokeWidth = 2f * density
        c.drawLine(sx(top.first), sy(top.second), sx(rh.first), sy(rh.second), line)
        dot(rh, Color.parseColor("#ff1493"))
    }

    // ---- touch ----

    private fun targetsKey(horizontal: Boolean) = if (horizontal) floatArrayOf(0f, cardW / 2f, cardW) else floatArrayOf(0f, cardH / 2f, cardH)

    /** Nearest snap target for value [v]: card edges/centre first, then grid lines. Returns the delta or null. */
    private fun snapDelta(v: Float, horizontal: Boolean): Float? {
        if (!grid) return null
        var best: Float? = null
        for (t in targetsKey(horizontal)) if (abs(t - v) <= 10f && (best == null || abs(t - v) < abs(best))) best = t - v
        if (best != null) return best
        val step = cardW / 40f
        val g = (v / step).roundToInt() * step
        return if (abs(g - v) <= 4f) g - v else null
    }

    private fun applyMoveSnap() {
        guideX = null
        guideY = null
        if (!grid) return
        val l = layer
        val pts = listOf(-l.w / 2f to -l.h / 2f, l.w / 2f to -l.h / 2f, l.w / 2f to l.h / 2f, -l.w / 2f to l.h / 2f).map { world(l, it.first, it.second) }
        val xs = listOf(pts.minOf { it.first }, l.cx, pts.maxOf { it.first })
        val ys = listOf(pts.minOf { it.second }, l.cy, pts.maxOf { it.second })
        var bx = 0f
        var hasX = false
        for (v in xs) {
            val d = snapDelta(v, true) ?: continue
            if (!hasX || abs(d) < abs(bx)) { bx = d; hasX = true }
        }
        var by = 0f
        var hasY = false
        for (v in ys) {
            val d = snapDelta(v, false) ?: continue
            if (!hasY || abs(d) < abs(by)) { by = d; hasY = true }
        }
        l.cx += bx
        l.cy += by
        for (v in xs) for (t in targetsKey(true)) if (abs(v + bx - t) < 0.5f) guideX = t
        for (v in ys) for (t in targetsKey(false)) if (abs(v + by - t) < 0.5f) guideY = t
    }

    fun touch(ev: MotionEvent, dest: RectF, density: Float): Boolean {
        val k = dest.width() / cardW
        fun cx(x: Float) = (x - dest.left) / k
        fun cy(y: Float) = (y - dest.top) / k
        val l = layer
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val wx = cx(ev.x)
                val wy = cy(ev.y)
                val thr = 26f * density / k
                val hw = l.w / 2f
                val hh = l.h / 2f
                mode = Mode.NONE
                val rh = world(l, 0f, -hh - 38f * density / k)
                if (hypot(wx - rh.first, wy - rh.second) <= thr) {
                    mode = Mode.ROT
                } else {
                    var bestD = thr
                    for ((sxn, syn) in listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)) {
                        val p = world(l, sxn * hw, syn * hh)
                        val d = hypot(wx - p.first, wy - p.second)
                        if (d < bestD) { bestD = d; mode = Mode.CORNER; hsx = sxn; hsy = syn }
                    }
                    if (mode == Mode.NONE) {
                        for ((sxn, syn) in listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)) {
                            val p = world(l, sxn * hw, syn * hh)
                            val d = hypot(wx - p.first, wy - p.second)
                            if (d < bestD) { bestD = d; mode = Mode.EDGE; hsx = sxn; hsy = syn }
                        }
                    }
                    if (mode == Mode.NONE) {
                        val (lx, ly) = local(l, wx, wy)
                        if (abs(lx) <= hw && abs(ly) <= hh) mode = Mode.MOVE
                    }
                }
                if (mode == Mode.NONE) return false
                onBegin?.invoke()
                l0 = l.copy()
                tx0 = wx
                ty0 = wy
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    mode = Mode.PINCH
                    l0 = l.copy()
                    val ax = cx(ev.getX(0)); val ay = cy(ev.getY(0)); val bx = cx(ev.getX(1)); val by = cy(ev.getY(1))
                    dist0 = max(1f, hypot(bx - ax, by - ay))
                    ang0 = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()
                    mx0 = (ax + bx) / 2f
                    my0 = (ay + by) / 2f
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                mode = Mode.NONE
                onCommit?.invoke()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) return true
                if (mode == Mode.PINCH && ev.pointerCount >= 2) {
                    val ax = cx(ev.getX(0)); val ay = cy(ev.getY(0)); val bx = cx(ev.getX(1)); val by = cy(ev.getY(1))
                    val d = max(1f, hypot(bx - ax, by - ay))
                    val a = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()
                    val s = d / dist0
                    l.w = max(8f, l0.w * s)
                    l.h = max(8f, l0.h * s)
                    l.rot = l0.rot + (a - ang0)
                    l.cx = l0.cx + (ax + bx) / 2f - mx0
                    l.cy = l0.cy + (ay + by) / 2f - my0
                    changed()
                    return true
                }
                if (ev.pointerCount != 1) return true
                val wx = cx(ev.x)
                val wy = cy(ev.y)
                when (mode) {
                    Mode.MOVE -> {
                        l.cx = l0.cx + (wx - tx0)
                        l.cy = l0.cy + (wy - ty0)
                        applyMoveSnap()
                    }
                    Mode.ROT -> {
                        var a = Math.toDegrees(atan2((wy - l0.cy).toDouble(), (wx - l0.cx).toDouble())).toFloat() + 90f
                        if (grid) {
                            val q = (a / 15f).roundToInt() * 15f
                            if (abs(q - a) <= 4f) a = q
                        }
                        l.rot = a
                    }
                    Mode.CORNER, Mode.EDGE -> {
                        var (tlx, tly) = local(l0, wx, wy)
                        val unrot = abs(l0.rot % 360f) < 0.01f
                        guideX = null
                        guideY = null
                        if (unrot && grid) {
                            if (hsx != 0) snapDelta(l0.cx + tlx, true)?.let { tlx += it; guideX = l0.cx + tlx }
                            if (hsy != 0) snapDelta(l0.cy + tly, false)?.let { tly += it; guideY = l0.cy + tly }
                        }
                        if (mode == Mode.CORNER && lock) {
                            val s = max(0.02f, hypot(tlx, tly) / hypot(l0.w / 2f, l0.h / 2f))
                            l.w = max(8f, l0.w * s)
                            l.h = max(8f, l0.h * s)
                            l.cx = l0.cx
                            l.cy = l0.cy
                        } else {
                            var nw = l0.w
                            var nh = l0.h
                            var shx = 0f
                            var shy = 0f
                            if (hsx != 0) { nw = max(8f, hsx * tlx + l0.w / 2f); shx = hsx * (nw - l0.w) / 2f }
                            if (hsy != 0) { nh = max(8f, hsy * tly + l0.h / 2f); shy = hsy * (nh - l0.h) / 2f }
                            val a = rad(l0.rot)
                            val cs = cos(a).toFloat()
                            val sn = sin(a).toFloat()
                            l.w = nw
                            l.h = nh
                            l.cx = l0.cx + shx * cs - shy * sn
                            l.cy = l0.cy + shx * sn + shy * cs
                        }
                    }
                    else -> {}
                }
                changed()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode != Mode.NONE) onCommit?.invoke()
                mode = Mode.NONE
                guideX = null
                guideY = null
                changed()
                return true
            }
        }
        return true
    }
}
