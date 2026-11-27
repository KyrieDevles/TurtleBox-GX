package com.kyriedevles.turtleboxgx

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Moves and resizes one text box on the card: drag inside to move, drag a corner or an edge handle to resize.
 * [rect] is in card pixels; [onChange] is called as it changes so the card can be redrawn.
 */
class BoxEditor(val rect: RectF, val cardW: Float, val cardH: Float) {
    var onChange: (() -> Unit)? = null
    var redraw: (() -> Unit)? = null

    /**
     * Rotation of the box in degrees, about its centre (a rotated text layer). The box is edited in its own unrotated
     * frame, as in Photoshop: the handles turn with it, and pulling a side keeps the opposite side where it is.
     */
    var rot = 0f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Theme.hot
        pathEffect = DashPathEffect(floatArrayOf(14f, 9f), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.hot; alpha = 36 }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.pink }
    private val knobEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE }

    // Which sides move: bit 1 = left, 2 = top, 4 = right, 8 = bottom; 15 = the whole box.
    private var mode = 0
    private var lastX = 0f
    private var lastY = 0f

    private fun toView(dest: RectF) = RectF(
        dest.left + rect.left / cardW * dest.width(),
        dest.top + rect.top / cardH * dest.height(),
        dest.left + rect.right / cardW * dest.width(),
        dest.top + rect.bottom / cardH * dest.height(),
    )

    fun draw(c: Canvas, dest: RectF, density: Float) {
        val r = toView(dest)
        c.save()
        c.rotate(rot, r.centerX(), r.centerY())
        line.strokeWidth = 2f * density
        c.drawRect(r, fill)
        c.drawRect(r, line)
        val k = 7f * density
        knobEdge.strokeWidth = 1.5f * density
        for ((x, y) in handles(r)) {
            c.drawRect(x - k, y - k, x + k, y + k, knob)
            c.drawRect(x - k, y - k, x + k, y + k, knobEdge)
        }
        c.restore()
    }

    private fun handles(r: RectF) = listOf(
        r.left to r.top, r.centerX() to r.top, r.right to r.top,
        r.left to r.centerY(), r.right to r.centerY(),
        r.left to r.bottom, r.centerX() to r.bottom, r.right to r.bottom,
    )

    private fun modes() = listOf(3, 2, 6, 1, 4, 9, 8, 12)

    /** A point turned by -[rot] about (cx, cy): screen position into the box's own frame. */
    private fun unrotate(x: Float, y: Float, cx: Float, cy: Float): Pair<Float, Float> {
        val a = Math.toRadians(-rot.toDouble())
        val cs = Math.cos(a).toFloat()
        val sn = Math.sin(a).toFloat()
        val dx = x - cx
        val dy = y - cy
        return (cx + dx * cs - dy * sn) to (cy + dx * sn + dy * cs)
    }

    fun touch(ev: MotionEvent, dest: RectF, density: Float): Boolean {
        val r = toView(dest)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                lastY = ev.y
                val (px, py) = unrotate(ev.x, ev.y, r.centerX(), r.centerY())
                val reach = 30f * density
                var best = -1
                var bestD = Float.MAX_VALUE
                handles(r).forEachIndexed { i, (x, y) ->
                    val d = maxOf(abs(px - x), abs(py - y))
                    if (d < reach && d < bestD) { best = i; bestD = d }
                }
                mode = when {
                    best >= 0 -> modes()[best]
                    r.contains(px, py) -> 15
                    else -> 0
                }
            }
            MotionEvent.ACTION_MOVE -> if (mode != 0) {
                val dx = (ev.x - lastX) / dest.width() * cardW
                val dy = (ev.y - lastY) / dest.height() * cardH
                lastX = ev.x
                lastY = ev.y
                val min = 24f
                if (mode == 15) {
                    val w = rect.width()
                    val h = rect.height()
                    val l = if (rot == 0f) (rect.left + dx).coerceIn(0f, cardW - w) else rect.left + dx
                    val t = if (rot == 0f) (rect.top + dy).coerceIn(0f, cardH - h) else rect.top + dy
                    rect.set(l, t, l + w, t + h)
                } else if (rot == 0f) {
                    if (mode and 1 != 0) rect.left = (rect.left + dx).coerceIn(0f, rect.right - min)
                    if (mode and 2 != 0) rect.top = (rect.top + dy).coerceIn(0f, rect.bottom - min)
                    if (mode and 4 != 0) rect.right = (rect.right + dx).coerceIn(rect.left + min, cardW)
                    if (mode and 8 != 0) rect.bottom = (rect.bottom + dy).coerceIn(rect.top + min, cardH)
                } else {
                    // The drag in the box's own frame; each moved side shifts the centre by half its move (turned back).
                    val (lx, ly) = unrotate(dx, dy, 0f, 0f)
                    var w = rect.width()
                    var h = rect.height()
                    var mx = 0f
                    var my = 0f
                    if (mode and 1 != 0) { val d = lx.coerceAtMost(w - min); w -= d; mx += d / 2f }
                    if (mode and 4 != 0) { val d = lx.coerceAtLeast(min - w); w += d; mx += d / 2f }
                    if (mode and 2 != 0) { val d = ly.coerceAtMost(h - min); h -= d; my += d / 2f }
                    if (mode and 8 != 0) { val d = ly.coerceAtLeast(min - h); h += d; my += d / 2f }
                    val a = Math.toRadians(rot.toDouble())
                    val cs = Math.cos(a).toFloat()
                    val sn = Math.sin(a).toFloat()
                    val cx = rect.centerX() + mx * cs - my * sn
                    val cy = rect.centerY() + mx * sn + my * cs
                    rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                }
                redraw?.invoke()
                onChange?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }
}
