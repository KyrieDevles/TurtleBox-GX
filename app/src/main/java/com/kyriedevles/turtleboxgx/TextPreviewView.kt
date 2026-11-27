package com.kyriedevles.turtleboxgx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * The text box being edited, as the card draws it, with a highlight like any text selection: long-press a word, drag the
 * two handles to widen or narrow it, tap to clear. The selection is in visible characters of the text field (see
 * [com.kyriedevles.turtleboxgx.text.TagEdit]); [onSelect] reports it (from == to when cleared).
 */
class TextPreviewView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private var rects: Array<RectF?> = emptyArray()
    private var engineText = ""
    /** Engine character index to visible index (-1 = added by the engine). */
    private var e2v = IntArray(0)

    var selFrom = 0
        private set
    var selTo = 0
        private set
    var onSelect: ((Int, Int) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val hi = Paint().apply { color = Theme.hot; alpha = 95 }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.hot }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.hot; style = Paint.Style.STROKE; alpha = 160 }

    private companion object {
        const val MAX_ZOOM = 1.6f
    }

    private var checkerPaint: Paint? = null

    private fun checker(): Paint {
        checkerPaint?.let { return it }
        val n = (6f * dp).toInt().coerceAtLeast(4)
        val tile = Bitmap.createBitmap(n * 2, n * 2, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tile)
        tc.drawColor(0xFFE6E6E6.toInt())
        val dark = Paint().apply { color = 0xFFB4B4B4.toInt() }
        tc.drawRect(0f, 0f, n.toFloat(), n.toFloat(), dark)
        tc.drawRect(n.toFloat(), n.toFloat(), 2f * n, 2f * n, dark)
        val p = Paint().apply { shader = android.graphics.BitmapShader(tile, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT) }
        checkerPaint = p
        return p
    }

    private var k = 1f
    private var ox = 0f
    private var oy = 0f

    private var overset = false
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0303A.toInt() }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }

    fun setPreview(b: Bitmap, r: Array<RectF?>, text: String, map: IntArray, overset: Boolean = false) {
        this.overset = overset
        bmp = b
        rects = r
        engineText = text
        e2v = map
        requestLayout()
        invalidate()
    }

    /** Sets the highlight without reporting it. */
    fun setSelection(from: Int, to: Int) {
        selFrom = from
        selTo = to
        invalidate()
    }

    val hasPreview: Boolean get() = bmp != null

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val b = bmp
        val maxH = (resources.displayMetrics.heightPixels * 0.22f).toInt()
        // Enlarged to fill the width, but at most 1.6x, so a small box is not blown up.
        val h = if (b == null) 0 else minOf(maxH, (b.height * minOf(w.toFloat() / b.width, MAX_ZOOM)).toInt() + (8 * dp).toInt())
        setMeasuredDimension(w, h)
    }

    /** Pinch zoom of the preview (1 = fitted); while zoomed, [ox] and [oy] keep where the picture sits. */
    private var zoom = 1f

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        val base = minOf(width.toFloat() / b.width, (height - 8 * dp) / b.height, MAX_ZOOM)
        k = base * zoom
        if (zoom <= 1.001f) {
            ox = (width - b.width * k) / 2f
            oy = 4 * dp
        } else {
            // Zoomed: keep the picture covering the view (or centred on an axis where it is still smaller).
            val bw = b.width * k
            val bh = b.height * k
            ox = if (bw > width) ox.coerceIn(width - bw, 0f) else (width - bw) / 2f
            oy = if (bh > height) oy.coerceIn(height - bh, 0f) else (height - bh) / 2f
        }
        c.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        // A light checkerboard behind, as on the card: a text layer is drawn alone, so dark text stays readable on the
        // dark menu and see-through parts show as such. (Card text boxes come with the card behind them.)
        c.drawRect(ox, oy, ox + b.width * k, oy + b.height * k, checker())
        c.save()
        c.translate(ox, oy)
        c.scale(k, k)
        c.drawBitmap(b, 0f, 0f, null)
        c.restore()
        frame.strokeWidth = 1.5f * dp
        c.drawRect(ox, oy, ox + b.width * k, oy + b.height * k, frame)
        if (overset) {
            // Photoshop's overset mark: a red box with a plus at the bottom right of the text box.
            val s = 9f * dp
            val x = ox + b.width * k - s - 2f * dp
            val y = oy + b.height * k - s - 2f * dp
            c.drawRect(x, y, x + s, y + s, red)
            white.strokeWidth = 1.6f * dp
            c.drawLine(x + s / 2f, y + 2f * dp, x + s / 2f, y + s - 2f * dp, white)
            c.drawLine(x + 2f * dp, y + s / 2f, x + s - 2f * dp, y + s / 2f, white)
        }
        if (selTo <= selFrom) return
        var first: RectF? = null
        var last: RectF? = null
        for (i in rects.indices) {
            val v = e2v.getOrElse(i) { -1 }
            if (v < selFrom || v >= selTo) continue
            val r = rects[i] ?: continue
            val s = view(r)
            c.drawRect(s, hi)
            if (first == null) first = s
            last = s
        }
        knobRing.strokeWidth = 2f * dp
        first?.let { drawKnob(c, it.left, it.bottom) }
        last?.let { drawKnob(c, it.right, it.bottom) }
    }

    private fun drawKnob(c: Canvas, x: Float, y: Float) {
        val r = 7f * dp
        c.drawRect(x - 1f * dp, y - (y - oy).coerceAtMost(2f * dp), x + 1f * dp, y + r, knob)
        c.drawCircle(x, y + r, r, knob)
        c.drawCircle(x, y + r, r, knobRing)
    }

    private fun view(r: RectF) = RectF(ox + r.left * k, oy + r.top * k, ox + r.right * k, oy + r.bottom * k)

    /** The engine character under (or nearest to) a touch, or -1. */
    private fun charAt(x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in rects.indices) {
            val r = rects[i] ?: continue
            if (e2v.getOrElse(i) { -1 } < 0) continue
            val s = view(r)
            val dx = if (x < s.left) s.left - x else if (x > s.right) x - s.right else 0f
            val dy = if (y < s.top) s.top - y else if (y > s.bottom) y - s.bottom else 0f
            val d = dx + dy * 3f
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

    private fun report() {
        invalidate()
        onSelect?.invoke(selFrom, selTo)
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) {
            val i = charAt(e.x, e.y)
            if (i < 0) return
            var a = i
            var b = i + 1
            if (isWordChar(engineText[i])) {
                while (a > 0 && isWordChar(engineText[a - 1])) a--
                while (b < engineText.length && isWordChar(engineText[b])) b++
            }
            val vs = (a until b).map { e2v.getOrElse(it) { -1 } }.filter { it >= 0 }
            if (vs.isEmpty()) return
            selFrom = vs.first()
            selTo = vs.last() + 1
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            report()
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (selTo > selFrom) { selFrom = 0; selTo = 0; report() }
            return true
        }
    })

    /** 1 = dragging the start handle, 2 = the end handle. */
    private var dragging = 0

    private fun handlePos(start: Boolean): Pair<Float, Float>? {
        var pick: RectF? = null
        for (i in rects.indices) {
            val v = e2v.getOrElse(i) { -1 }
            if (v < selFrom || v >= selTo) continue
            val r = rects[i] ?: continue
            if (start) { pick = r; break } else pick = r
        }
        val r = view(pick ?: return null)
        return (if (start) r.left else r.right) to r.bottom + 7f * dp
    }

    private val scaler = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            val nz = (zoom * d.scaleFactor).coerceIn(1f, 5f)
            // Zoom around the fingers.
            val r = nz / zoom
            ox = d.focusX - (d.focusX - ox) * r
            oy = d.focusY - (d.focusY - oy) * r
            zoom = nz
            invalidate()
            return true
        }
    })
    private var pinching = false
    private var lastFx = 0f
    private var lastFy = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null) return false
        // Two fingers: zoom the preview (for small text) and move it; the highlight is left as it is.
        scaler.onTouchEvent(e)
        if (e.pointerCount >= 2 || scaler.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val fx = (e.getX(0) + e.getX(minOf(1, e.pointerCount - 1))) / 2f
            val fy = (e.getY(0) + e.getY(minOf(1, e.pointerCount - 1))) / 2f
            if (pinching && e.actionMasked == MotionEvent.ACTION_MOVE && !scaler.isInProgress) {
                ox += fx - lastFx
                oy += fy - lastFy
                invalidate()
            }
            if (!pinching) {
                pinching = true
                dragging = 0
                val cancel = MotionEvent.obtain(e)
                cancel.action = MotionEvent.ACTION_CANCEL
                gestures.onTouchEvent(cancel)
                cancel.recycle()
            }
            lastFx = fx
            lastFy = fy
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
        if (pinching) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = 0
                if (selTo > selFrom) {
                    val reach = 26f * dp
                    val s = handlePos(true)
                    val t = handlePos(false)
                    val ds = s?.let { hypot(e.x - it.first, e.y - it.second) } ?: Float.MAX_VALUE
                    val dt = t?.let { hypot(e.x - it.first, e.y - it.second) } ?: Float.MAX_VALUE
                    if (minOf(ds, dt) < reach) dragging = if (ds <= dt) 1 else 2
                }
                if (dragging != 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging != 0) {
                // The handle sits below the text: aim a little above the finger.
                val i = charAt(e.x, e.y - 14f * dp)
                if (i >= 0) {
                    val r = view(rects[i]!!)
                    val v = e2v[i]
                    val after = e.x > (r.left + r.right) / 2f
                    if (dragging == 1) {
                        val nv = if (after) v + 1 else v
                        if (nv < selTo) selFrom = nv
                    } else {
                        val nv = if (after) v + 1 else v
                        if (nv > selFrom) selTo = nv
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging != 0) {
                dragging = 0
                report()
                return true
            }
        }
        return gestures.onTouchEvent(e)
    }
}
