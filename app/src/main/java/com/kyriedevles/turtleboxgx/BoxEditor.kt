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
        line.strokeWidth = 2f * density
        c.drawRect(r, fill)
        c.drawRect(r, line)
        val k = 7f * density
        knobEdge.strokeWidth = 1.5f * density
        for ((x, y) in handles(r)) {
            c.drawRect(x - k, y - k, x + k, y + k, knob)
            c.drawRect(x - k, y - k, x + k, y + k, knobEdge)
        }
    }

    private fun handles(r: RectF) = listOf(
        r.left to r.top, r.centerX() to r.top, r.right to r.top,
        r.left to r.centerY(), r.right to r.centerY(),
        r.left to r.bottom, r.centerX() to r.bottom, r.right to r.bottom,
    )

    private fun modes() = listOf(3, 2, 6, 1, 4, 9, 8, 12)

    fun touch(ev: MotionEvent, dest: RectF, density: Float): Boolean {
        val r = toView(dest)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                lastY = ev.y
                val reach = 30f * density
                var best = -1
                var bestD = Float.MAX_VALUE
                handles(r).forEachIndexed { i, (x, y) ->
                    val d = maxOf(abs(ev.x - x), abs(ev.y - y))
                    if (d < reach && d < bestD) { best = i; bestD = d }
                }
                mode = when {
                    best >= 0 -> modes()[best]
                    r.contains(ev.x, ev.y) -> 15
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
                    val l = (rect.left + dx).coerceIn(0f, cardW - w)
                    val t = (rect.top + dy).coerceIn(0f, cardH - h)
                    rect.set(l, t, l + w, t + h)
                } else {
                    if (mode and 1 != 0) rect.left = (rect.left + dx).coerceIn(0f, rect.right - min)
                    if (mode and 2 != 0) rect.top = (rect.top + dy).coerceIn(0f, rect.bottom - min)
                    if (mode and 4 != 0) rect.right = (rect.right + dx).coerceIn(rect.left + min, cardW)
                    if (mode and 8 != 0) rect.bottom = (rect.bottom + dy).coerceIn(rect.top + min, cardH)
                }
                redraw?.invoke()
                onChange?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }
}
