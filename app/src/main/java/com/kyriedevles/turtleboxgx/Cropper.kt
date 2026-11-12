package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Shows the picked image under a frame with the artwork box's shape. Drag to move, pinch to zoom. */
class CropView(context: Context, private val bmp: Bitmap, private val aspect: Float) : View(context) {
    private val frame = RectF()
    private val m = Matrix()
    private var minScale = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ff1493")
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val grid = Paint().apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 1f
    }
    private var lastX = 0f
    private var lastY = 0f
    private var lastId = -1
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val cur = scaleNow()
            val target = (cur * d.scaleFactor).coerceIn(minScale, minScale * 10f)
            val k = target / cur
            m.postScale(k, k, d.focusX, d.focusY)
            clamp()
            invalidate()
            return true
        }
    })

    private fun scaleNow(): Float {
        val v = FloatArray(9)
        m.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val pad = 24f * resources.displayMetrics.density
        var fw = w - 2 * pad
        var fh = fw / aspect
        if (fh > h - 2 * pad) {
            fh = h - 2 * pad
            fw = fh * aspect
        }
        frame.set((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f)
        minScale = maxOf(fw / bmp.width, fh / bmp.height)
        m.reset()
        m.postScale(minScale, minScale)
        m.postTranslate(frame.centerX() - bmp.width * minScale / 2f, frame.centerY() - bmp.height * minScale / 2f)
    }

    /** Keeps the image covering the whole frame. */
    private fun clamp() {
        val r = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.left > frame.left) dx = frame.left - r.left
        if (r.right < frame.right) dx = frame.right - r.right
        if (r.top > frame.top) dy = frame.top - r.top
        if (r.bottom < frame.bottom) dy = frame.bottom - r.bottom
        m.postTranslate(dx, dy)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaler.onTouchEvent(ev)
        if (scaler.isInProgress) {
            lastId = -1
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val i = if (ev.actionMasked == MotionEvent.ACTION_POINTER_UP) (if (ev.actionIndex == 0) 1 else 0) else 0
                lastId = ev.getPointerId(i)
                lastX = ev.getX(i)
                lastY = ev.getY(i)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = ev.findPointerIndex(lastId)
                if (i >= 0) {
                    m.postTranslate(ev.getX(i) - lastX, ev.getY(i) - lastY)
                    lastX = ev.getX(i)
                    lastY = ev.getY(i)
                    clamp()
                    invalidate()
                }
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        c.drawBitmap(bmp, m, paint)
        c.drawRect(0f, 0f, width.toFloat(), frame.top, dim)
        c.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), dim)
        c.drawRect(0f, frame.top, frame.left, frame.bottom, dim)
        c.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, dim)
        for (i in 1..2) {
            val x = frame.left + frame.width() * i / 3f
            val y = frame.top + frame.height() * i / 3f
            c.drawLine(x, frame.top, x, frame.bottom, grid)
            c.drawLine(frame.left, y, frame.right, y, grid)
        }
        c.drawRect(frame, line)
    }

    /** The part of the image inside the frame, up to 1600 px wide. */
    fun result(): Bitmap {
        val inv = Matrix()
        m.invert(inv)
        val src = RectF(frame)
        inv.mapRect(src)
        val sr = Rect(
            Math.round(src.left).coerceIn(0, bmp.width - 1),
            Math.round(src.top).coerceIn(0, bmp.height - 1),
            Math.round(src.right).coerceIn(1, bmp.width),
            Math.round(src.bottom).coerceIn(1, bmp.height),
        )
        val ow = minOf(sr.width(), 1600).coerceAtLeast(1)
        val oh = (ow / aspect).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, sr, RectF(0f, 0f, ow.toFloat(), oh.toFloat()), paint)
        return out
    }
}

object Cropper {
    /** Full-screen crop. [onCrop] gets the cropped picture, [onWhole] means "use the whole image as it is". */
    fun show(activity: Activity, bmp: Bitmap, aspect: Float, onCrop: (Bitmap) -> Unit, onWhole: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        val dlg = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val cv = CropView(activity, bmp, aspect)
        root.addView(cv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val hint = TextView(activity).apply {
            text = "Drag to move, pinch to zoom"
            setTextColor(Color.parseColor("#e7c3d6"))
            gravity = Gravity.CENTER
            setPadding(0, (28 * d).toInt(), 0, 0)
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        fun btn(t: String, strong: Boolean) = Button(activity).apply {
            text = t
            isAllCaps = false
            setTextColor(if (strong) Color.WHITE else Color.parseColor("#ff6ec7"))
            background = GradientDrawable().apply {
                if (strong) {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(Color.parseColor("#ff1493"), Color.parseColor("#ff6ec7"))
                } else {
                    setColor(Color.BLACK)
                    setStroke((2 * d).toInt(), Color.parseColor("#ff1493"))
                }
                cornerRadius = 12 * d
            }
        }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (20 * d).toInt())
        }
        val cancel = btn("Cancel", false).apply { setOnClickListener { dlg.dismiss() } }
        val whole = btn("Whole image", false).apply { setOnClickListener { dlg.dismiss(); onWhole() } }
        val ok = btn("Crop", true).apply { setOnClickListener { dlg.dismiss(); onCrop(cv.result()) } }
        for (b in listOf(cancel, whole, ok)) {
            bar.addView(b, LinearLayout.LayoutParams(0, (48 * d).toInt(), 1f).apply { marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt() })
        }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        dlg.setContentView(root)
        dlg.show()
    }
}
