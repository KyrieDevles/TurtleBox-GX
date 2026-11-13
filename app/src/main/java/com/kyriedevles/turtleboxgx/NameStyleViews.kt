package com.kyriedevles.turtleboxgx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun hex(c: Int) = String.format("#%06x", c and 0xffffff)
internal fun parseHex(s: String, d: Int = Color.BLACK): Int = try { Color.parseColor(s) } catch (e: Exception) { d }

private fun View.dp(v: Int) = (v * resources.displayMetrics.density)

/** Saturation / value square for one hue, like YGOcarder's colour picker. */
class SvView(c: Context, private val onPick: (Float, Float) -> Unit) : View(c) {
    var hue = 0f
        set(v) { field = v; invalidate() }
    var sat = 0f
    var value = 0f
    private val p = Paint()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val a = LinearGradient(0f, 0f, w, 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hue, 1f, 1f)), Shader.TileMode.CLAMP)
        val b = LinearGradient(0f, 0f, 0f, h, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        p.shader = ComposeShader(a, b, PorterDuff.Mode.SRC_OVER)
        cv.drawRect(0f, 0f, w, h, p)
        val x = sat * w
        val y = (1f - value) * h
        ring.color = Color.BLACK
        cv.drawCircle(x, y, dp(9), ring)
        ring.color = Color.WHITE
        cv.drawCircle(x, y, dp(8), ring)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        sat = (e.x / width).coerceIn(0f, 1f)
        value = 1f - (e.y / height).coerceIn(0f, 1f)
        invalidate()
        onPick(sat, value)
        return true
    }
}

class HueView(c: Context, private val onPick: (Float) -> Unit) : View(c) {
    var hue = 0f
        set(v) { field = v; invalidate() }
    private val p = Paint()
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cols = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        p.shader = LinearGradient(0f, 0f, w, 0f, cols, null, Shader.TileMode.CLAMP)
        cv.drawRoundRect(RectF(0f, h * 0.3f, w, h * 0.7f), 8f, 8f, p)
        val x = hue / 360f * w
        thumb.color = Color.WHITE
        cv.drawCircle(x, h / 2f, h * 0.45f, thumb)
        thumb.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        cv.drawCircle(x, h / 2f, h * 0.3f, thumb)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        hue = (e.x / width).coerceIn(0f, 1f) * 360f
        onPick(hue)
        return true
    }
}

/** Colour picker: optional square + hue bar, the swatch grid and a hex box. */
class ColorPickerView(c: Context, initial: Int, full: Boolean, private val onColor: (Int) -> Unit) : LinearLayout(c) {
    private val hsv = FloatArray(3)
    private var busy = false
    private val sv: SvView?
    private val hueV: HueView?
    private val hexBox: EditText
    private val preview: View
    private val rgb: TextView

    init {
        orientation = VERTICAL
        Color.colorToHSV(initial, hsv)
        sv = if (full) SvView(c) { s, v -> hsv[1] = s; hsv[2] = v; pushed() } else null
        hueV = if (full) HueView(c) { h -> hsv[0] = h; sv?.hue = h; pushed() } else null
        if (sv != null && hueV != null) {
            addView(sv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150).toInt()))
            addView(hueV, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34).toInt()))
            sv.hue = hsv[0]; sv.sat = hsv[1]; sv.value = hsv[2]; hueV.hue = hsv[0]
        }
        val palette = listOf(
            "#444444 #999999 #ffffff #ff8080 #ffd280 #ffff80 #80ff80 #80c080 #80ffff #8080ff #c080c0 #ff80ff",
            "#000000 #808080 #cccccc #ff0000 #ffa500 #ffff00 #00ff00 #008000 #00ffff #0000ff #800080 #ff00ff",
            "#000000 #606060 #b3b3b3 #800000 #804d00 #808000 #008000 #004d00 #008080 #000080 #400040 #800080",
        )
        val grid = LinearLayout(c).apply { orientation = VERTICAL }
        for (row in palette) {
            val r = LinearLayout(c).apply { orientation = HORIZONTAL }
            for (h in row.split(' ')) {
                val cell = View(c).apply {
                    background = GradientDrawable().apply { setColor(Color.parseColor(h)); setStroke(dp(1).toInt().coerceAtLeast(1), Color.parseColor("#777777")) }
                    setOnClickListener { setColor(Color.parseColor(h)); onColor(Color.parseColor(h)) }
                }
                r.addView(cell, LayoutParams(0, dp(26).toInt(), 1f).apply { setMargins(2, 2, 2, 2) })
            }
            grid.addView(r)
        }
        addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8).toInt() })
        val info = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        preview = View(c)
        info.addView(preview, LayoutParams(dp(30).toInt(), dp(30).toInt()).apply { rightMargin = dp(8).toInt() })
        hexBox = EditText(c).apply {
            setSingleLine(true)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setSelectAllOnFocus(true)
            background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); setStroke(2, Color.WHITE); cornerRadius = 8f }
        }
        info.addView(hexBox, LayoutParams(dp(110).toInt(), dp(40).toInt()))
        rgb = TextView(c).apply { setTextColor(Color.parseColor("#bbbbbb")); setPadding(dp(12).toInt(), 0, 0, 0) }
        info.addView(rgb)
        addView(info, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8).toInt() })
        hexBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy) return
                val t = s?.toString()?.trim() ?: return
                if (t.length != 7 || !t.startsWith("#")) return
                try {
                    val col = Color.parseColor(t)
                    Color.colorToHSV(col, hsv)
                    syncViews(false)
                    onColor(col)
                } catch (e: Exception) {
                }
            }
        })
        syncViews(true)
    }

    private fun cur() = Color.HSVToColor(hsv) or (0xff shl 24)

    private fun pushed() {
        syncViews(true)
        onColor(cur())
    }

    private fun syncViews(writeHex: Boolean) {
        busy = true
        val col = cur()
        sv?.let { it.hue = hsv[0]; it.sat = hsv[1]; it.value = hsv[2]; it.invalidate() }
        hueV?.let { it.hue = hsv[0] }
        preview.background = GradientDrawable().apply { setColor(col); setStroke(2, Color.WHITE); cornerRadius = 6f }
        if (writeHex) hexBox.setText(hex(col))
        rgb.text = "R ${Color.red(col)}  G ${Color.green(col)}  B ${Color.blue(col)}"
        busy = false
    }

    fun setColor(col: Int) {
        Color.colorToHSV(col, hsv)
        syncViews(true)
    }
}

/** Square pad that picks an X and Y offset, like the Position pad in YGOcarder. */
class PadView(c: Context, private val range: Int, private val onPick: (Int, Int) -> Unit) : View(c) {
    var px = 0
    var py = 0
    private val bg = Paint().apply { color = Color.parseColor("#009cde") }
    private val line = Paint().apply { color = Color.WHITE; strokeWidth = 3f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8fd3ff") }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        cv.drawRect(0f, 0f, w, h, bg)
        cv.drawLine(w / 2f, 0f, w / 2f, h, line)
        cv.drawLine(0f, h / 2f, w, h / 2f, line)
        cv.drawCircle((px.toFloat() / range + 1f) / 2f * w, (py.toFloat() / range + 1f) / 2f * h, dp(14), dot)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        px = ((e.x / width * 2f - 1f) * range).roundToInt().coerceIn(-range, range)
        py = ((e.y / height * 2f - 1f) * range).roundToInt().coerceIn(-range, range)
        invalidate()
        onPick(px, py)
        return true
    }
}

/** Round angle dial with a - / + stepper under it. 0 is up and the angle grows clockwise. */
class DialView(c: Context, private val onPick: (Int) -> Unit) : View(c) {
    var angle = 0
        set(v) { field = ((v % 360) + 360) % 360; invalidate() }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ededed") }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4a7fc0"); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4a7fc0") }

    override fun onDraw(cv: Canvas) {
        val r = min(width, height) / 2f - 4f
        val cx = width / 2f
        val cy = height / 2f
        cv.drawCircle(cx, cy, r, disc)
        cv.drawCircle(cx, cy, r, edge)
        val a = Math.toRadians(angle.toDouble())
        cv.drawCircle(cx + (r - dp(10)) * sin(a).toFloat(), cy - (r - dp(10)) * cos(a).toFloat(), dp(7), dot)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val dx = e.x - width / 2f
        val dy = e.y - height / 2f
        angle = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).roundToInt()
        onPick(angle)
        return true
    }
}

class GStop(var pos: Float, var color: Int)

/** Gradient strip with draggable colour stops underneath. */
class GradientBarView(c: Context, val stops: MutableList<GStop>, private val onSelect: (GStop) -> Unit, private val onChange: () -> Unit) : View(c) {
    var selected: GStop = stops.first()
    private val bar = Paint()
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad get() = dp(14)

    override fun onDraw(cv: Canvas) {
        val w = width - 2 * pad
        val barH = height - dp(34)
        val sorted = stops.sortedBy { it.pos }
        if (sorted.size >= 2) {
            bar.shader = LinearGradient(pad, 0f, pad + w, 0f, IntArray(sorted.size) { sorted[it].color or (0xff shl 24) }, FloatArray(sorted.size) { sorted[it].pos }, Shader.TileMode.CLAMP)
        } else {
            bar.shader = null
            bar.color = sorted.firstOrNull()?.color ?: Color.BLACK
        }
        cv.drawRect(pad, 0f, pad + w, barH, bar)
        for (s in stops) {
            val x = pad + s.pos * w
            val on = s === selected
            box.style = Paint.Style.FILL
            box.color = Color.parseColor(if (on) "#cfd6e4" else "#888888")
            cv.drawRect(x - dp(13), barH + dp(6), x + dp(13), barH + dp(32), box)
            box.color = s.color or (0xff shl 24)
            cv.drawRect(x - dp(9), barH + dp(10), x + dp(9), barH + dp(28), box)
            if (on) {
                box.color = Color.parseColor("#4a9bff")
                cv.drawRect(x - dp(1), barH, x + dp(1), barH + dp(6), box)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val w = width - 2 * pad
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = stops.minByOrNull { Math.abs(pad + it.pos * w - e.x) }
                if (hit != null && Math.abs(pad + hit.pos * w - e.x) < dp(30)) {
                    selected = hit
                    onSelect(hit)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                selected.pos = ((e.x - pad) / w).coerceIn(0f, 1f)
                invalidate()
                onChange()
            }
        }
        return true
    }
}
