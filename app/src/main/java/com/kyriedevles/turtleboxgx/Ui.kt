package com.kyriedevles.turtleboxgx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
            "sets" -> {
                addRoundRect(RectF(3f, 3f, 11f, 11f), 1f, 1f, Path.Direction.CW)
                addRoundRect(RectF(13f, 3f, 21f, 11f), 1f, 1f, Path.Direction.CW)
                addRoundRect(RectF(3f, 13f, 11f, 21f), 1f, 1f, Path.Direction.CW)
                addRoundRect(RectF(13f, 13f, 21f, 21f), 1f, 1f, Path.Direction.CW)
            }
        }
    }

    private val shape by lazy { path() }

    override fun onDraw(c: Canvas) {
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
        bgPaint.shader = RadialGradient(width / 2f, height * 0.4f, maxOf(width, height) * 0.7f, Color.parseColor("#2a0a1c"), Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val b = bitmap
        if (b == null) {
            hint.split('\n').forEachIndexed { i, line ->
                c.drawText(line, width / 2f, height / 2f + i * text.textSize * 1.4f, text)
            }
            return
        }
        val pad = 14f * resources.displayMetrics.density
        val k = minOf((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
        val w = b.width * k
        val h = b.height * k
        dest.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        c.drawRoundRect(dest, 6f, 6f, glow)
        c.drawBitmap(b, null, dest, bmpPaint)
        c.drawRoundRect(dest, 6f, 6f, edge)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_UP && bitmap != null && dest.contains(ev.x, ev.y)) {
            onTapCard?.invoke((ev.x - dest.left) / dest.width(), (ev.y - dest.top) / dest.height())
        }
        return true
    }
}
