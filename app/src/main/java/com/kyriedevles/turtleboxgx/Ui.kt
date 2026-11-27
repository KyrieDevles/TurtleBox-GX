package com.kyriedevles.turtleboxgx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View

object Hot {
    val pink = Color.parseColor("#ff4da6")
    val pinkLight = Color.parseColor("#ff6ec7")
    val pinkDeep = Color.parseColor("#ff1493")
    val pinkEdge = Color.parseColor("#ff00aa")
    val muted = Color.parseColor("#e7c3d6")
    val dim = Color.parseColor("#c9a6b9")
    val inactive = Color.parseColor("#d9b3c8")
    val line = Color.parseColor("#3a1a2c")
    val ok = Color.parseColor("#7be8a8")
    val bad = Color.parseColor("#ff8da1")

    fun gradient(radius: Float, horizontal: Boolean = false) = GradientDrawable(
        if (horizontal) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.TL_BR,
        intArrayOf(pinkLight, pinkDeep, pinkEdge),
    ).apply { cornerRadius = radius }

    fun outline(radius: Float, strokePx: Int, color: Int, fill: Int = Color.BLACK) = GradientDrawable().apply {
        setColor(fill)
        setStroke(strokePx, color)
        cornerRadius = radius
    }
}

/** The icons from the mockup, drawn with the hot pink gradient (or one flat colour when inactive). */
class HotIcon(context: Context, private val kind: String) : View(context) {
    var gradient = true
        set(v) {
            field = v
            invalidate()
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun path(): Path = Path().apply {
        when (kind) {
            "save" -> {
                moveTo(5f, 3f); lineTo(16f, 3f); lineTo(19f, 6f); lineTo(19f, 21f); lineTo(5f, 21f); close()
                moveTo(8f, 3f); lineTo(8f, 9f); lineTo(15f, 9f); lineTo(15f, 3f)
                addRect(8f, 13f, 16f, 21f, Path.Direction.CW)
            }
            "eye" -> {
                moveTo(2f, 12f); quadTo(12f, -2f, 22f, 12f); quadTo(12f, 26f, 2f, 12f); close()
                addCircle(12f, 12f, 3f, Path.Direction.CW)
            }
            "image" -> {
                addRoundRect(RectF(3f, 4f, 21f, 20f), 2f, 2f, Path.Direction.CW)
                addCircle(9f, 10f, 1.5f, Path.Direction.CW)
                moveTo(21f, 16f); lineTo(16f, 11f); lineTo(7f, 20f)
            }
            "clear" -> {
                moveTo(5f, 7f); lineTo(19f, 7f)
                moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
                moveTo(7f, 7f); lineTo(8f, 21f); lineTo(16f, 21f); lineTo(17f, 7f)
                moveTo(10f, 11f); lineTo(10f, 17f)
                moveTo(14f, 11f); lineTo(14f, 17f)
            }
            "card" -> {
                addRoundRect(RectF(5f, 3f, 19f, 21f), 2f, 2f, Path.Direction.CW)
                moveTo(9f, 8f); lineTo(15f, 8f)
                moveTo(9f, 12f); lineTo(15f, 12f)
            }
            "layouts" -> {
                moveTo(4f, 7f); lineTo(20f, 7f)
                moveTo(4f, 12f); lineTo(20f, 12f)
                moveTo(4f, 17f); lineTo(14f, 17f)
            }
            "text" -> {
                moveTo(4f, 6f); lineTo(20f, 6f)
                moveTo(4f, 11f); lineTo(20f, 11f)
                moveTo(4f, 16f); lineTo(14f, 16f)
            }
            "name" -> {
                moveTo(5f, 19f); lineTo(12f, 5f); lineTo(19f, 19f)
                moveTo(8f, 14f); lineTo(16f, 14f)
            }
            "stats" -> {
                moveTo(12f, 3f); lineTo(20f, 6f); lineTo(20f, 12f)
                cubicTo(20f, 17f, 16f, 20f, 12f, 21f)
                cubicTo(8f, 20f, 4f, 17f, 4f, 12f)
                lineTo(4f, 6f); close()
            }
            "frame" -> {
                addRoundRect(RectF(4f, 4f, 20f, 20f), 2f, 2f, Path.Direction.CW)
                addRoundRect(RectF(8f, 8f, 16f, 16f), 1f, 1f, Path.Direction.CW)
            }
            "layers" -> {
                moveTo(12f, 3f); lineTo(21f, 8f); lineTo(12f, 13f); lineTo(3f, 8f); close()
                moveTo(3f, 12f); lineTo(12f, 17f); lineTo(21f, 12f)
                moveTo(3f, 16f); lineTo(12f, 21f); lineTo(21f, 16f)
            }
            "star" -> {
                moveTo(12f, 3f); lineTo(14.6f, 8.6f); lineTo(20.6f, 9.3f); lineTo(16.1f, 13.4f)
                lineTo(17.3f, 19.4f); lineTo(12f, 16.4f); lineTo(6.7f, 19.4f); lineTo(7.9f, 13.4f)
                lineTo(3.4f, 9.3f); lineTo(9.4f, 8.6f); close()
            }
            "link" -> {
                moveTo(7f, 17f); lineTo(17f, 7f)
                moveTo(9f, 7f); lineTo(17f, 7f); lineTo(17f, 15f)
            }
            "plus" -> {
                moveTo(12f, 5f); lineTo(12f, 19f)
                moveTo(5f, 12f); lineTo(19f, 12f)
            }
            "tag" -> {
                addRoundRect(RectF(3f, 8f, 21f, 16f), 2f, 2f, Path.Direction.CW)
            }
            "up" -> {
                moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f)
            }
            "down" -> {
                moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
            }
            "right" -> {
                moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
            }
            "sets" -> {
                addRoundRect(RectF(3f, 3f, 11f, 11f), 1f, 1f, Path.Direction.CW)
                addRoundRect(RectF(13f, 3f, 21f, 11f), 1f, 1f, Path.Direction.CW)
                addRoundRect(RectF(3f, 13f, 11f, 21f), 1f, 1f, Path.Direction.CW)
                addRoundRect(RectF(13f, 13f, 21f, 21f), 1f, 1f, Path.Direction.CW)
            }
            else -> addCircle(12f, 12f, 6f, Path.Direction.CW)
        }
    }

    private val shape by lazy { path() }

    /** A picture icon (PNG etc.). Only its transparency is used: it is tinted with the pink gradient. */
    var bitmap: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintOver = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }

    override fun onDraw(c: Canvas) {
        val bmp = bitmap
        if (bmp != null) {
            val side = minOf(width, height).toFloat()
            val dst = RectF((width - side) / 2f, (height - side) / 2f, (width + side) / 2f, (height + side) / 2f)
            val layer = c.saveLayer(dst, null)
            c.drawBitmap(bmp, null, dst, tint)
            tintOver.shader = if (gradient) {
                LinearGradient(dst.left, dst.top, dst.right, dst.bottom, intArrayOf(Hot.pinkLight, Hot.pinkDeep, Hot.pinkEdge), null, Shader.TileMode.CLAMP)
            } else {
                null
            }
            tintOver.color = Hot.inactive
            c.drawRect(dst, tintOver)
            c.restoreToCount(layer)
            return
        }
        val s = minOf(minOf(width, height) / 24f, resources.displayMetrics.density)
        c.save()
        c.translate((width - 24f * s) / 2f, (height - 24f * s) / 2f)
        c.scale(s, s)
        val shader = if (gradient) {
            LinearGradient(0f, 0f, 24f, 24f, intArrayOf(Hot.pinkLight, Hot.pinkDeep, Hot.pinkEdge), null, Shader.TileMode.CLAMP)
        } else {
            null
        }
        stroke.shader = shader
        fill.shader = shader
        stroke.color = Hot.inactive
        fill.color = Hot.inactive
        if (kind == "more") {
            c.drawCircle(12f, 5f, 2f, fill)
            c.drawCircle(12f, 12f, 2f, fill)
            c.drawCircle(12f, 19f, 2f, fill)
        } else {
            c.drawPath(shape, stroke)
        }
        c.restore()
    }
}

/** Shows the drawn card centred on a dark radial glow. Taps inside the card come back as fractions of it. */
class CardView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }
    var hint: String = ""
        set(v) {
            field = v
            invalidate()
        }
    var onTapCard: ((Float, Float) -> Unit)? = null
    var insetBottom = 0
        set(v) {
            field = v
            invalidate()
        }
    var editor: LayerEditor? = null
        set(v) {
            field = v
            v?.redraw = { invalidate() }
            invalidate()
        }

    /** A text box being moved or resized on the card. */
    var boxEditor: BoxEditor? = null
        set(v) {
            field = v
            v?.redraw = { invalidate() }
            invalidate()
        }

    private val dest = RectF()
    private val bgPaint = Paint()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        setShadowLayer(40f, 0f, 10f, Color.argb(90, 255, 20, 147))
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Hot.pinkDeep
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Hot.muted
        textAlign = Paint.Align.CENTER
        textSize = 15f * resources.displayMetrics.scaledDensity
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(c: Canvas) {
        if (Theme.glass) {
            c.drawColor(Color.argb(80, 0, 0, 0))
        } else {
            bgPaint.shader = RadialGradient(width / 2f, height * 0.4f, maxOf(width, height) * 0.7f, Color.parseColor("#26141d"), Theme.ground, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }
        val b = bitmap
        if (b == null) {
            hint.split('\n').forEachIndexed { i, line ->
                c.drawText(line, width / 2f, height / 2f + i * text.textSize * 1.4f, text)
            }
            return
        }
        val pad = 14f * resources.displayMetrics.density
        val k = minOf((width - 2 * pad) / b.width, (height - insetBottom - 2 * pad) / b.height)
        val w = b.width * k
        val h = b.height * k
        fit.set((width - w) / 2f, (height - insetBottom - h) / 2f, (width + w) / 2f, (height - insetBottom + h) / 2f)
        clampPan()
        val zw = w * zoom
        val zh = h * zoom
        val cx = fit.centerX() + panX
        val cy = fit.centerY() + panY
        dest.set(cx - zw / 2f, cy - zh / 2f, cx + zw / 2f, cy + zh / 2f)
        c.drawBitmap(b, null, dest, bmpPaint)
        editor?.draw(c, dest, resources.displayMetrics.density)
        boxEditor?.draw(c, dest, resources.displayMetrics.density)
        if (zoom > 1.01f) drawChip(c)
    }

    // ---- Zoom and move (pinch, drag when zoomed, double-tap) ----

    /** The card's zoom over its fitted size (1 = fitted) and how far it is moved from the middle, in view px. */
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val fit = RectF()
    private val chip = RectF()
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 20, 12, 18) }
    private val chipEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Hot.pinkDeep; strokeWidth = 2f }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = 13f * resources.displayMetrics.scaledDensity
    }

    /** Back to the fitted card (a different card was opened, or Fit was chosen). */
    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun clampPan() {
        // Keep at least part of the card on screen.
        val maxX = maxOf(0f, (fit.width() * zoom - fit.width()) / 2f + fit.width() * 0.4f)
        val maxY = maxOf(0f, (fit.height() * zoom - fit.height()) / 2f + fit.height() * 0.4f)
        if (zoom <= 1.001f) { panX = 0f; panY = 0f; return }
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    /** Zooms to [z] keeping the card point under ([fx], [fy]) where it is. */
    private fun zoomTo(z: Float, fx: Float, fy: Float) {
        val nz = z.coerceIn(1f, 8f)
        val cx = fit.centerX() + panX
        val cy = fit.centerY() + panY
        val r = nz / zoom
        panX = fx - (fx - cx) * r - fit.centerX()
        panY = fy - (fy - cy) * r - fit.centerY()
        zoom = nz
        clampPan()
        invalidate()
    }

    private fun drawChip(c: Canvas) {
        val d = resources.displayMetrics.density
        val label = (if (zoom < 9.95f) String.format(java.util.Locale.US, "%.1f×", zoom) else "${zoom.toInt()}×").replace(".0×", "×")
        val w = chipText.measureText(label) + 22f * d
        val h = 28f * d
        chip.set(width - w - 10f * d, 10f * d, width - 10f * d, 10f * d + h)
        c.drawRoundRect(chip, 8f * d, 8f * d, chipBg)
        c.drawRoundRect(chip, 8f * d, 8f * d, chipEdge)
        c.drawText(label, chip.centerX(), chip.centerY() - (chipText.ascent() + chipText.descent()) / 2f, chipText)
    }

    private fun chipMenu() {
        val pm = android.widget.PopupMenu(context, this, android.view.Gravity.END)
        pm.menu.add(0, 1, 0, "Fit")
        pm.menu.add(0, 2, 1, "2×")
        pm.menu.add(0, 4, 2, "4×")
        pm.menu.add(0, 8, 3, "8×")
        pm.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) resetZoom() else zoomTo(item.itemId.toFloat(), fit.centerX() + panX, fit.centerY() + panY)
            true
        }
        pm.show()
    }

    private val scaler = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            zoomTo(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val gestures = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (zoom > 1.01f && chip.contains(e.x, e.y)) { chipMenu(); return true }
            if (bitmap != null && dest.contains(e.x, e.y)) {
                onTapCard?.invoke((e.x - dest.left) / dest.width(), (e.y - dest.top) / dest.height())
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.01f) resetZoom() else zoomTo(2f, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1.01f || e2.pointerCount > 1) return false
            panX -= dx
            panY -= dy
            clampPan()
            invalidate()
            return true
        }
    })

    /** Two fingers on the card while a text box is being edited: zoom and move the card instead. */
    private var twoFinger = false

    /** This touch became a pinch. */
    private var multi = false

    /** Two-finger move (pan) while pinching. */
    private fun twoFingerPan(ev: MotionEvent) {
        if (ev.pointerCount < 2) return
        val fx = (ev.getX(0) + ev.getX(1)) / 2f
        val fy = (ev.getY(0) + ev.getY(1)) / 2f
        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            panX += fx - lastFocusX
            panY += fy - lastFocusY
            clampPan()
            invalidate()
        }
        lastFocusX = fx
        lastFocusY = fy
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Moving a layer on the card uses its own two-finger pinch, so it keeps every touch.
        editor?.let { e ->
            if (bitmap != null) return e.touch(ev, dest, resources.displayMetrics.density)
        }
        boxEditor?.let { e ->
            if (bitmap != null) {
                if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !twoFinger) {
                    // Second finger: stop the box drag and zoom instead.
                    twoFinger = true
                    val cancel = MotionEvent.obtain(ev)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    e.touch(cancel, dest, resources.displayMetrics.density)
                    cancel.recycle()
                }
                if (twoFinger) {
                    scaler.onTouchEvent(ev)
                    twoFingerPan(ev)
                    if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) twoFinger = false
                    return true
                }
                return e.touch(ev, dest, resources.displayMetrics.density)
            }
        }
        if (bitmap == null) return true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) multi = false
        scaler.onTouchEvent(ev)
        if (ev.pointerCount >= 2 || scaler.isInProgress) {
            if (!multi) {
                // A pinch started: the one-finger gesture (tap, drag) is over.
                multi = true
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                gestures.onTouchEvent(cancel)
                cancel.recycle()
            }
            twoFingerPan(ev)
            return true
        }
        // After a pinch, the finger left on the card does nothing until it is lifted.
        if (multi) return true
        gestures.onTouchEvent(ev)
        return true
    }
}
