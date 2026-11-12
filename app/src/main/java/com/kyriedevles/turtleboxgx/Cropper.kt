package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Where the crop frame is, in the coordinates of the (unflipped or flipped) preview picture. */
class CropParams(val cx: Float, val cy: Float, val fw: Float, val theta: Float, val flipH: Boolean, val flipV: Boolean)

/**
 * The picture stays still; the crop frame (fixed shape of the art box) is what you move: drag inside it,
 * drag a corner to resize, pinch to resize, and the angle control tilts it.
 */
class CropView(context: Context, base: Bitmap, private val aspect: Float, private val origScale: Float) : View(context) {
    private var bmp = base
    private val bw get() = bmp.width.toFloat()
    private val bh get() = bmp.height.toFloat()
    private var cx = 0f
    private var cy = 0f
    private var fw = 0f
    private var theta = 0f
    private var flipH = false
    private var flipV = false
    private var s = 1f
    private var tx = 0f
    private var ty = 0f
    private val d = resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ff1493")
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        pathEffect = DashPathEffect(floatArrayOf(6f * d, 4f * d), 0f)
    }
    private val grid = Paint().apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 1f
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ff1493")
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }
    var onAngle: ((Float) -> Unit)? = null
    var onInfo: ((String) -> Unit)? = null

    private val sx = floatArrayOf(-1f, 1f, 1f, -1f)
    private val sy = floatArrayOf(-1f, -1f, 1f, 1f)
    private val fh get() = fw / aspect

    private fun rx(x: Float, y: Float, deg: Float): Float = x * Math.cos(Math.toRadians(deg.toDouble())).toFloat() - y * Math.sin(Math.toRadians(deg.toDouble())).toFloat()
    private fun ry(x: Float, y: Float, deg: Float): Float = x * Math.sin(Math.toRadians(deg.toDouble())).toFloat() + y * Math.cos(Math.toRadians(deg.toDouble())).toFloat()

    private fun cornerX(i: Int, cx: Float, cy: Float, w: Float, th: Float) = cx + rx(sx[i] * w / 2f, sy[i] * (w / aspect) / 2f, th)
    private fun cornerY(i: Int, cx: Float, cy: Float, w: Float, th: Float) = cy + ry(sx[i] * w / 2f, sy[i] * (w / aspect) / 2f, th)

    private fun valid(cx: Float, cy: Float, w: Float, th: Float): Boolean {
        for (i in 0..3) {
            val x = cornerX(i, cx, cy, w, th)
            val y = cornerY(i, cx, cy, w, th)
            if (x < -0.5f || y < -0.5f || x > bw + 0.5f || y > bh + 0.5f) return false
        }
        return true
    }

    private fun minW() = minOf(bw, bh * aspect) * 0.05f

    /** The largest width up to [want] that keeps the frame inside the picture, shrinking about the centre. */
    private fun fitAbout(cx: Float, cy: Float, th: Float, want: Float): Float {
        if (valid(cx, cy, want, th)) return want
        var lo = 0f
        var hi = want
        for (i in 0 until 24) {
            val mid = (lo + hi) / 2f
            if (valid(cx, cy, mid, th)) lo = mid else hi = mid
        }
        return lo
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val pad = 20f * d
        s = minOf((w - 2 * pad) / bw, (h - 2 * pad) / bh)
        tx = (w - bw * s) / 2f
        ty = (h - bh * s) / 2f
        if (fw == 0f) reset()
    }

    fun reset() {
        theta = 0f
        fw = minOf(bw, bh * aspect)
        cx = bw / 2f
        cy = bh / 2f
        onAngle?.invoke(0f)
        changed()
    }

    private fun changed() {
        val w = Math.round(fw * origScale)
        val h = Math.round(fw / aspect * origScale)
        onInfo?.invoke("Crop $w × $h px from the original")
        invalidate()
    }

    fun rotateTo(deg: Float) {
        theta = deg
        fw = fitAbout(cx, cy, theta, fw)
        changed()
    }

    /** Mirrors the picture itself (the frame stays on the same part of it). */
    fun flip(vertical: Boolean) {
        val m = Matrix()
        if (vertical) {
            m.postScale(1f, -1f)
            m.postTranslate(0f, bh)
            cy = bh - cy
            flipV = !flipV
        } else {
            m.postScale(-1f, 1f)
            m.postTranslate(bw, 0f)
            cx = bw - cx
            flipH = !flipH
        }
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, if (vertical) Matrix().apply { setScale(1f, -1f) } else Matrix().apply { setScale(-1f, 1f) }, true)
        theta = -theta
        onAngle?.invoke(theta)
        changed()
    }

    fun snapshot() = CropParams(cx, cy, fw, theta, flipH, flipV)

    private enum class Mode { NONE, MOVE, RESIZE }

    private var mode = Mode.NONE
    private var corner = 0
    private var lastX = 0f
    private var lastY = 0f
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val want = (fw * det.scaleFactor).coerceAtLeast(minW())
            fw = fitAbout(cx, cy, theta, want).coerceAtLeast(minW().coerceAtMost(fw))
            changed()
            return true
        }
    })

    private fun toBx(vx: Float) = (vx - tx) / s
    private fun toBy(vy: Float) = (vy - ty) / s

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaler.onTouchEvent(ev)
        if (scaler.isInProgress || ev.pointerCount > 1) {
            mode = Mode.NONE
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.NONE
                val hit = 30f * d
                for (i in 0..3) {
                    val vx = tx + cornerX(i, cx, cy, fw, theta) * s
                    val vy = ty + cornerY(i, cx, cy, fw, theta) * s
                    if (Math.hypot((ev.x - vx).toDouble(), (ev.y - vy).toDouble()) <= hit) {
                        mode = Mode.RESIZE
                        corner = i
                    }
                }
                if (mode == Mode.NONE) {
                    // Inside the frame? Look at the point in the frame's own axes.
                    val px = toBx(ev.x) - cx
                    val py = toBy(ev.y) - cy
                    val lx = rx(px, py, -theta)
                    val ly = ry(px, py, -theta)
                    if (Math.abs(lx) <= fw / 2f && Math.abs(ly) <= fh / 2f) mode = Mode.MOVE
                }
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.MOVE -> {
                    val dx = (ev.x - lastX) / s
                    val dy = (ev.y - lastY) / s
                    lastX = ev.x
                    lastY = ev.y
                    when {
                        valid(cx + dx, cy + dy, fw, theta) -> { cx += dx; cy += dy }
                        valid(cx + dx, cy, fw, theta) -> cx += dx
                        valid(cx, cy + dy, fw, theta) -> cy += dy
                    }
                    changed()
                }
                Mode.RESIZE -> {
                    // The opposite corner stays where it is; the dragged corner follows the finger.
                    val o = (corner + 2) % 4
                    val ox = cornerX(o, cx, cy, fw, theta)
                    val oy = cornerY(o, cx, cy, fw, theta)
                    val px = toBx(ev.x) - ox
                    val py = toBy(ev.y) - oy
                    val lx = rx(px, py, -theta) * sx[corner]
                    val ly = ry(px, py, -theta) * sy[corner]
                    val want = maxOf(lx, ly * aspect)
                    if (want > 0f) {
                        fun ncx(w: Float) = ox + rx(sx[corner] * w / 2f, sy[corner] * (w / aspect) / 2f, theta)
                        fun ncy(w: Float) = oy + ry(sx[corner] * w / 2f, sy[corner] * (w / aspect) / 2f, theta)
                        var w = want.coerceAtLeast(minW())
                        if (!valid(ncx(w), ncy(w), w, theta)) {
                            var lo = 0f
                            var hi = w
                            for (i in 0 until 24) {
                                val mid = (lo + hi) / 2f
                                if (valid(ncx(mid), ncy(mid), mid, theta)) lo = mid else hi = mid
                            }
                            w = lo
                        }
                        if (w >= minW()) {
                            cx = ncx(w)
                            cy = ncy(w)
                            fw = w
                            changed()
                        }
                    }
                }
                Mode.NONE -> {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
        }
        return true
    }

    private fun vx(i: Int) = tx + cornerX(i, cx, cy, fw, theta) * s
    private fun vy(i: Int) = ty + cornerY(i, cx, cy, fw, theta) * s

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        c.save()
        c.translate(tx, ty)
        c.scale(s, s)
        c.drawBitmap(bmp, 0f, 0f, bmpPaint)
        c.restore()
        val frame = Path().apply {
            moveTo(vx(0), vy(0))
            lineTo(vx(1), vy(1))
            lineTo(vx(2), vy(2))
            lineTo(vx(3), vy(3))
            close()
        }
        val outside = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(tx, ty, tx + bw * s, ty + bh * s, Path.Direction.CW)
            addPath(frame)
        }
        c.drawPath(outside, dim)
        for (i in 1..2) {
            val t = i / 3f
            // lines parallel to the top/bottom edges and to the left/right edges
            c.drawLine(vx(0) + (vx(3) - vx(0)) * t, vy(0) + (vy(3) - vy(0)) * t, vx(1) + (vx(2) - vx(1)) * t, vy(1) + (vy(2) - vy(1)) * t, grid)
            c.drawLine(vx(0) + (vx(1) - vx(0)) * t, vy(0) + (vy(1) - vy(0)) * t, vx(3) + (vx(2) - vx(3)) * t, vy(3) + (vy(2) - vy(3)) * t, grid)
        }
        c.drawPath(frame, line)
        for (i in 0..3) {
            c.drawCircle(vx(i), vy(i), 7f * d, handleFill)
            c.drawCircle(vx(i), vy(i), 7f * d, handleLine)
        }
    }
}

object Cropper {
    /** Crops from the ORIGINAL file at its own resolution (up to [maxW] px wide); falls back to the preview if that fails. */
    fun render(p: CropParams, aspect: Float, base: Bitmap, bytes: ByteArray, origW: Int, maxW: Int): Bitmap {
        val r = origW.toFloat() / base.width
        val ow = minOf(Math.round(p.fw * r), maxW).coerceAtLeast(1)
        val oh = (ow / aspect).toInt().coerceAtLeast(1)
        val k = ow / p.fw
        val pw = base.width.toFloat()
        val ph = base.height.toFloat()
        val cosT = Math.cos(Math.toRadians(p.theta.toDouble())).toFloat()
        val sinT = Math.sin(Math.toRadians(p.theta.toDouble())).toFloat()

        fun compose(toPreview: Matrix): Matrix {
            val mat = Matrix(toPreview)
            if (p.flipH) { mat.postScale(-1f, 1f); mat.postTranslate(pw, 0f) }
            if (p.flipV) { mat.postScale(1f, -1f); mat.postTranslate(0f, ph) }
            mat.postTranslate(-p.cx, -p.cy)
            mat.postRotate(-p.theta)
            mat.postScale(k, k)
            mat.postTranslate(ow / 2f, oh / 2f)
            return mat
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        try {
            // Region of the original that the (rotated) frame touches.
            var x0 = Float.MAX_VALUE
            var y0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE
            var y1 = -Float.MAX_VALUE
            val sx = floatArrayOf(-1f, 1f, 1f, -1f)
            val sy = floatArrayOf(-1f, -1f, 1f, 1f)
            for (i in 0..3) {
                val lx = sx[i] * p.fw / 2f
                val ly = sy[i] * (p.fw / aspect) / 2f
                var x = p.cx + lx * cosT - ly * sinT
                var y = p.cy + lx * sinT + ly * cosT
                if (p.flipH) x = pw - x
                if (p.flipV) y = ph - y
                x0 = minOf(x0, x * r); x1 = maxOf(x1, x * r)
                y0 = minOf(y0, y * r); y1 = maxOf(y1, y * r)
            }
            val region = Rect(
                (x0.toInt() - 2).coerceAtLeast(0),
                (y0.toInt() - 2).coerceAtLeast(0),
                (x1.toInt() + 3).coerceAtMost(Math.round(base.width * r)),
                (y1.toInt() + 3).coerceAtMost(Math.round(base.height * r)),
            )
            @Suppress("DEPRECATION")
            val dec = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            val src = dec.decodeRegion(region, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            dec.recycle()
            val toPreview = Matrix()
            toPreview.postTranslate(region.left.toFloat(), region.top.toFloat())
            toPreview.postScale(1f / r, 1f / r)
            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, compose(toPreview), paint)
            return out
        } catch (t: Throwable) {
            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(base, compose(Matrix()), paint)
            return out
        }
    }

    /** Full-screen crop. [onCrop] gets the cropped picture, [onWhole] means "use the whole image" (true = keep the original resolution). */
    fun show(activity: Activity, preview: Bitmap, bytes: ByteArray, origW: Int, aspect: Float, onCrop: (Bitmap) -> Unit, onWhole: (Boolean) -> Unit) {
        val d = activity.resources.displayMetrics.density
        val pink = Color.parseColor("#ff6ec7")
        val dlg = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val info = TextView(activity).apply {
            setTextColor(Color.parseColor("#e7c3d6"))
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, (24 * d).toInt(), 0, (4 * d).toInt())
        }
        root.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val cv = CropView(activity, preview, aspect, origW.toFloat() / preview.width)
        root.addView(cv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        cv.onInfo = { info.text = it }

        fun btn(t: String, strong: Boolean) = Button(activity).apply {
            text = t
            isAllCaps = false
            setTextColor(if (strong) Color.WHITE else pink)
            background = GradientDrawable().apply {
                if (strong) {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(Color.parseColor("#ff1493"), pink)
                } else {
                    setColor(Color.BLACK)
                    setStroke((2 * d).toInt(), Color.parseColor("#ff1493"))
                }
                cornerRadius = 12 * d
            }
        }
        val angleBox = EditText(activity).apply {
            setText("0")
            setTextColor(pink)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            setSelectAllOnFocus(true)
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke((2 * d).toInt(), Color.parseColor("#ff1493"))
                cornerRadius = 10 * d
            }
            setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
        }
        val seek = SeekBar(activity).apply {
            max = 3600
            progress = 1800
            progressTintList = android.content.res.ColorStateList.valueOf(pink)
            thumbTintList = android.content.res.ColorStateList.valueOf(pink)
        }
        var busy = false
        fun fmtA(v: Float): String {
            val r = Math.round(v * 100f) / 100f
            return if (r == Math.round(r).toFloat()) Math.round(r).toString() else r.toString()
        }
        // The frame's tilt in degrees, -180..180: slider in 0.1 steps, or any fraction typed in the box.
        fun setAngle(deg: Float, fromBox: Boolean = false) {
            val v = deg.coerceIn(-180f, 180f)
            cv.rotateTo(v)
            busy = true
            if (!fromBox) angleBox.setText(fmtA(v))
            seek.progress = Math.round((v + 180f) * 10f)
            busy = false
        }
        cv.onAngle = { v ->
            busy = true
            angleBox.setText(fmtA(v))
            seek.progress = Math.round((v + 180f) * 10f).coerceIn(0, 3600)
            busy = false
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && !busy) setAngle(p / 10f - 180f)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        angleBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(t: Editable?) {
                if (busy) return
                val v = t?.toString()?.toFloatOrNull() ?: return
                setAngle(v, fromBox = true)
            }
        })
        val angleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
        }
        angleRow.addView(TextView(activity).apply { text = "Tilt"; setTextColor(pink); setPadding(0, 0, (8 * d).toInt(), 0) })
        angleRow.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        angleRow.addView(angleBox, LinearLayout.LayoutParams((84 * d).toInt(), (42 * d).toInt()))
        angleRow.addView(TextView(activity).apply { text = "°"; setTextColor(pink); setPadding((4 * d).toInt(), 0, 0, 0) })

        val cur = { (seek.progress / 10f - 180f) }
        fun small(t: String, act: () -> Unit) = btn(t, false).apply { setOnClickListener { act() } }
        val tools = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
        }
        for (b in listOf(
            small("↺ 90°") { var v = cur() - 90f; if (v < -180f) v += 360f; setAngle(v) },
            small("↻ 90°") { var v = cur() + 90f; if (v > 180f) v -= 360f; setAngle(v) },
            small("⇋ Mirror") { cv.flip(false) },
            small("⇅ Flip") { cv.flip(true) },
            small("Reset") { cv.reset() },
        )) {
            tools.addView(b, LinearLayout.LayoutParams(0, (44 * d).toInt(), 1f).apply { marginStart = (3 * d).toInt(); marginEnd = (3 * d).toInt() })
        }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (20 * d).toInt())
        }
        val keep = android.widget.CheckBox(activity).apply {
            text = "Keep original resolution (off = smaller copy, 2000 px)"
            isChecked = true
            setTextColor(Color.parseColor("#e7c3d6"))
            buttonTintList = android.content.res.ColorStateList.valueOf(pink)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
        }
        val cancel = btn("Cancel", false).apply { setOnClickListener { dlg.dismiss() } }
        val whole = btn("Whole image", false).apply { setOnClickListener { dlg.dismiss(); onWhole(keep.isChecked) } }
        val ok = btn("Crop", true)
        ok.setOnClickListener {
            val params = cv.snapshot()
            val keepOriginal = keep.isChecked
            ok.text = "Cropping…"
            for (b in listOf(ok, cancel, whole)) b.isEnabled = false
            Thread {
                val out = render(params, aspect, preview, bytes, origW, if (keepOriginal) 8192 else 2000)
                activity.runOnUiThread {
                    dlg.dismiss()
                    onCrop(out)
                }
            }.start()
        }
        for (b in listOf(cancel, whole, ok)) {
            bar.addView(b, LinearLayout.LayoutParams(0, (48 * d).toInt(), 1f).apply { marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt() })
        }
        root.addView(angleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(keep, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg.setContentView(root)
        dlg.show()
    }
}
