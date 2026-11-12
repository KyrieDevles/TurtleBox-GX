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
import android.widget.SeekBar
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
            val target = (cur * d.scaleFactor).coerceIn(minScale * 0.2f, minScale * 10f)
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
        reset()
    }

    private var angle = 0f

    fun reset() {
        angle = 0f
        m.reset()
        m.postScale(minScale, minScale)
        m.postTranslate(frame.centerX() - bmp.width * minScale / 2f, frame.centerY() - bmp.height * minScale / 2f)
        invalidate()
    }

    /** Free rotation of the picture under the frame, in degrees (fractions allowed). */
    fun rotateTo(deg: Float) {
        m.postRotate(deg - angle, frame.centerX(), frame.centerY())
        angle = deg
        clamp()
        invalidate()
    }

    /** Mirror the picture left-right, or top-bottom when [vertical]. */
    fun flip(vertical: Boolean) {
        if (vertical) m.postScale(1f, -1f, frame.centerX(), frame.centerY()) else m.postScale(-1f, 1f, frame.centerX(), frame.centerY())
        clamp()
        invalidate()
    }

    /** Keeps the image covering the whole frame, also when it is rotated: zooms in if needed, then slides back inside. */
    private fun clamp() {
        val v = FloatArray(9)
        m.getValues(v)
        val sc = Math.hypot(v[Matrix.MSCALE_X].toDouble(), v[Matrix.MSKEW_Y].toDouble()).toFloat()
        if (sc <= 0f) return
        val cs = Math.abs(v[Matrix.MSCALE_X]) / sc
        val sn = Math.abs(v[Matrix.MSKEW_Y]) / sc
        val a = frame.width() / 2f / sc
        val b = frame.height() / 2f / sc
        var ex = a * cs + b * sn
        var ey = a * sn + b * cs
        val bw = bmp.width / 2f
        val bh = bmp.height / 2f
        val k = maxOf(ex / bw, ey / bh)
        if (k > 1f) {
            m.postScale(k, k, frame.centerX(), frame.centerY())
            ex /= k
            ey /= k
        }
        val inv = Matrix()
        m.invert(inv)
        val p = floatArrayOf(frame.centerX(), frame.centerY())
        inv.mapPoints(p)
        val nx = p[0].coerceIn(ex, bmp.width - ex)
        val ny = p[1].coerceIn(ey, bmp.height - ey)
        if (nx != p[0] || ny != p[1]) {
            val q = floatArrayOf(nx, ny)
            m.mapPoints(q)
            m.postTranslate(frame.centerX() - q[0], frame.centerY() - q[1])
        }
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

    /** The part of the image inside the frame (rotation and mirror included), up to 1600 px wide. */
    fun result(): Bitmap {
        val v = FloatArray(9)
        m.getValues(v)
        val sc = Math.hypot(v[Matrix.MSCALE_X].toDouble(), v[Matrix.MSKEW_Y].toDouble()).toFloat().coerceAtLeast(0.0001f)
        val ow = minOf(Math.round(frame.width() / sc), 1600).coerceAtLeast(1)
        val oh = (ow / aspect).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val mat = Matrix(m)
        mat.postTranslate(-frame.left, -frame.top)
        mat.postScale(ow / frame.width(), oh / frame.height())
        Canvas(out).drawBitmap(bmp, mat, paint)
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
            text = "Drag to move, pinch to zoom, slider to rotate"
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
        // Rotation: free angle -180..180 in 0.1 steps, plus quarter turns, mirror and reset.
        val angleText = TextView(activity).apply {
            text = "Rotate 0.0°"
            setTextColor(Color.parseColor("#ff6ec7"))
            gravity = Gravity.CENTER
        }
        val seek = SeekBar(activity).apply {
            max = 3600
            progress = 1800
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#ff6ec7"))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#ff6ec7"))
        }
        fun setAngle(deg: Float) {
            val v = deg.coerceIn(-180f, 180f)
            cv.rotateTo(v)
            angleText.text = "Rotate " + String.format(java.util.Locale.US, "%.1f", v) + "°"
            if (seek.progress != Math.round((v + 180f) * 10f)) seek.progress = Math.round((v + 180f) * 10f)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) setAngle(p / 10f - 180f)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        fun small(t: String, act: () -> Unit) = btn(t, false).apply { setOnClickListener { act() } }
        val tools = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
        }
        val cur = { (seek.progress / 10f - 180f) }
        for (b in listOf(
            small("↺ 90°") { var v = cur() - 90f; if (v < -180f) v += 360f; setAngle(v) },
            small("↻ 90°") { var v = cur() + 90f; if (v > 180f) v -= 360f; setAngle(v) },
            small("⇋ Mirror") { cv.flip(false) },
            small("⇅ Flip") { cv.flip(true) },
            small("Reset") { cv.reset(); setAngle(0f) },
        )) {
            tools.addView(b, LinearLayout.LayoutParams(0, (44 * d).toInt(), 1f).apply { marginStart = (3 * d).toInt(); marginEnd = (3 * d).toInt() })
        }
        val controls = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(angleText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        controls.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (16 * d).toInt(); marginEnd = (16 * d).toInt() })
        controls.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val cancel = btn("Cancel", false).apply { setOnClickListener { dlg.dismiss() } }
        val whole = btn("Whole image", false).apply { setOnClickListener { dlg.dismiss(); onWhole() } }
        val ok = btn("Crop", true).apply { setOnClickListener { dlg.dismiss(); onCrop(cv.result()) } }
        for (b in listOf(cancel, whole, ok)) {
            bar.addView(b, LinearLayout.LayoutParams(0, (48 * d).toInt(), 1f).apply { marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt() })
        }
        val bottom = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(controls)
        bottom.addView(bar)
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        dlg.setContentView(root)
        dlg.show()
    }
}
