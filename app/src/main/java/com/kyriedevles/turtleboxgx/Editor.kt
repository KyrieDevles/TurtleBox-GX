package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.content.Context
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The card being edited: its values plus the files stored with it (artwork). */
class CardState(val values: MutableMap<String, String> = LinkedHashMap(), val files: MutableMap<String, ByteArray> = LinkedHashMap()) {
    companion object {
        fun from(set: CardSet?): CardState =
            if (set == null) CardState() else CardState(LinkedHashMap(set.values), LinkedHashMap(set.files))
    }
}

/** Writes a .msoe-set file: a zip with a `set` XML (key/value pairs) and the stored files. */
object SetWriter {
    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun build(layout: Layout, state: CardState): ByteArray {
        val ctx = Ctx(layout, state.values)
        val sb = StringBuilder("<?xml version='1.0' encoding='UTF-8' ?><save><style>")
        for (k in listOf("app-version", "author", "desc", "icon", "md5", "name", "url", "version")) {
            sb.append("<$k>").append(esc(layout.styleInfo[k] ?: "")).append("</$k>")
        }
        sb.append("</style>")
        val written = HashSet<String>()
        for (d in layout.datas.values) {
            if (d.type == "multi_select") continue
            written.add(d.name)
            sb.append("<data><key>").append(esc(d.name)).append("</key><value>")
                .append(esc(state.values[d.name] ?: ctx.value(d.name))).append("</value></data>")
        }
        for ((k, v) in state.values) {
            if (k in written) continue
            sb.append("<data><key>").append(esc(k)).append("</key><value>").append(esc(v)).append("</value></data>")
        }
        sb.append("</save>")

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("set"))
            z.write(sb.toString().toByteArray(Charsets.UTF_8))
            z.closeEntry()
            for ((name, bytes) in state.files) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

/**
 * Builds the edit dialog for a tapped element from the layout's own `<data>` entries:
 * a `multi_select` entry lists several options, a dropdown has `<item>` choices, a text entry is typed,
 * and an image entry opens the picker. Nothing here is hard-coded to a card type, so new options added
 * to the XML show up without rebuilding the app.
 */
class Editor(
    private val activity: Activity,
    private val layout: Layout,
    private val state: CardState,
    private val onChanged: () -> Unit,
    private val pickImage: (String) -> Unit,
) {
    private val pink = Color.parseColor("#ff6ec7")
    private val hot = Color.parseColor("#ff1493")

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun find(name: String): DataDef? =
        layout.datas[name] ?: layout.datas.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun open(click: String) {
        val d = find(click) ?: return
        if (d.type == "image") {
            pickImage(d.name)
            return
        }
        val fields = if (d.type == "multi_select") d.items.mapNotNull { find(it.second) } else listOf(d)
        if (fields.isEmpty()) return

        val ctx = Ctx(layout, state.values)
        val commits = ArrayList<() -> Unit>()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        var firstEdit: EditText? = null
        var dlgRef: AlertDialog? = null
        val restores = ArrayList<() -> Unit>()
        val liveHandler = Handler(Looper.getMainLooper())
        var livePending = false
        fun liveApply() {
            if (livePending) return
            livePending = true
            liveHandler.postDelayed({ livePending = false; onChanged() }, 90)
        }
        for (f in fields) {
            if (f.type == "multi_select") continue
            box.addView(label(f.desc?.takeIf { it.isNotBlank() } ?: f.name))
            val current = state.values[f.name] ?: ctx.value(f.name)
            when {
                f.type == "image" -> {
                    val b = hotButton("Choose image…")
                    b.setOnClickListener { commitAll(commits); pickImage(f.name) }
                    box.addView(b, row())
                }
                f.items.isNotEmpty() && f.items.size <= 8 && f.items.all { it.first.length <= 34 } -> {
                    var chosen = current
                    val flow = FlowLayout(activity, dp(6))
                    val chips = ArrayList<Pair<TextView, String>>()
                    fun paint() {
                        for ((tv, v) in chips) {
                            val on = v == chosen
                            tv.setTextColor(if (on) pink else Color.parseColor("#e7c3d6"))
                            tv.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                            tv.background = GradientDrawable().apply {
                                setColor(Color.BLACK)
                                setStroke(dp(2), if (on) hot else Color.parseColor("#3a1a2c"))
                                cornerRadius = dp(18).toFloat()
                            }
                        }
                    }
                    for ((name, v) in f.items) {
                        val tv = TextView(activity).apply {
                            text = name
                            gravity = android.view.Gravity.CENTER
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            setPadding(dp(14), 0, dp(14), 0)
                            minimumHeight = dp(36)
                            setOnClickListener {
                                chosen = v
                                paint()
                            }
                        }
                        chips.add(tv to v)
                        flow.addView(tv)
                    }
                    paint()
                    commits.add { if (chosen != (state.values[f.name] ?: ctx.value(f.name))) state.values[f.name] = chosen }
                    box.addView(flow, row())
                }
                f.items.isNotEmpty() -> {
                    var chosen = current
                    val b = hotButton(f.items.firstOrNull { it.second == chosen }?.first ?: chosen.ifEmpty { "(none)" })
                    b.setOnClickListener {
                        val names = f.items.map { it.first }.toTypedArray()
                        val at = f.items.indexOfFirst { it.second == chosen }
                        val dlg = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                            .setSingleChoiceItems(names, at) { dd, which ->
                                chosen = f.items[which].second
                                b.text = f.items[which].first
                                dd.dismiss()
                            }.create()
                        dlg.show()
                        style(dlg)
                    }
                    commits.add { if (chosen != (state.values[f.name] ?: ctx.value(f.name))) state.values[f.name] = chosen }
                    box.addView(b, row())
                }
                sliderRange(f.name) != null -> {
                    val (lo, hi) = sliderRange(f.name)!!
                    val start = (current.toFloatOrNull() ?: lo.toFloat()).coerceIn(lo.toFloat(), hi.toFloat())
                    val readout = TextView(activity).apply {
                        text = fmt(start) + "%"
                        setTextColor(pink)
                        typeface = Typeface.DEFAULT_BOLD
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        gravity = android.view.Gravity.END
                    }
                    box.addView(readout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = -dp(22) })
                    val original = state.values[f.name]
                    restores.add { if (original == null) state.values.remove(f.name) else state.values[f.name] = original }
                    val bar = SeekBar(activity).apply {
                        max = (hi - lo) * 10
                        progress = Math.round((start - lo) * 10f).coerceIn(0, max)
                        progressTintList = android.content.res.ColorStateList.valueOf(pink)
                        thumbTintList = android.content.res.ColorStateList.valueOf(pink)
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                                val v = lo + p / 10f
                                readout.text = fmt(v) + "%"
                                if (fromUser) {
                                    state.values[f.name] = fmt(v)
                                    liveApply()
                                }
                            }

                            override fun onStartTrackingTouch(sb: SeekBar?) {}

                            override fun onStopTrackingTouch(sb: SeekBar?) {}
                        })
                    }
                    box.addView(bar, row())
                }
                else -> {
                    val multi = f.name.endsWith("text", ignoreCase = true) || current.contains('\n')
                    val et = EditText(activity).apply {
                        setText(current)
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setHintTextColor(Color.GRAY)
                        background = GradientDrawable().apply {
                            setColor(Color.BLACK)
                            setStroke(dp(1), Color.parseColor("#5a2a43"))
                            cornerRadius = dp(10).toFloat()
                        }
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        if (multi) {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            minLines = 6
                            gravity = android.view.Gravity.TOP
                            setSingleLine(false)
                        } else {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            setSingleLine(true)
                        }
                    }
                    if (firstEdit == null) firstEdit = et
                    commits.add {
                        val v = et.text.toString()
                        if (v != (state.values[f.name] ?: ctx.value(f.name))) state.values[f.name] = v
                    }
                    val gearName = f.gear
                    if (gearName != null && find(gearName) != null) {
                        val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        line.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        val g = gearButton()
                        g.setOnClickListener {
                            commitAll(commits)
                            dlgRef?.dismiss()
                            onChanged()
                            openPanel(gearName) { open(click) }
                        }
                        line.addView(g, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(10) })
                        box.addView(line, row())
                    } else {
                        box.addView(et, row())
                    }
                }
            }
        }

        val dlg = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(d.desc?.takeIf { it.isNotBlank() } ?: d.name)
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Done") { _, _ ->
                commitAll(commits)
                onChanged()
            }
            .setNegativeButton("Cancel") { _, _ ->
                if (restores.isNotEmpty()) {
                    restores.forEach { it() }
                    onChanged()
                }
            }
            .create()
        dlgRef = dlg
        dlg.show()
        dlg.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        style(dlg)
    }


    /** Round gradient hot-pink button with a white gear. */
    private fun gearButton(): View = View(activity).apply {
        background = LayerDrawable(
            arrayOf(
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(hot, Color.parseColor("#ff00aa"), pink)).apply { shape = GradientDrawable.OVAL },
                GearDrawable(),
            ),
        )
        isClickable = true
        contentDescription = "Settings"
    }

    private class GearDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val path = Path()
        override fun onBoundsChange(b: android.graphics.Rect) {
            path.reset()
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val r = minOf(b.width(), b.height()) * 0.30f
            val teeth = 8
            val pts = teeth * 4
            for (i in 0 until pts) {
                val a = Math.PI * 2 * i / pts - Math.PI / pts
                val rr = if ((i % 4) == 1 || (i % 4) == 2) r * 1.32f else r
                val x = cx + (rr * Math.cos(a)).toFloat()
                val y = cy + (rr * Math.sin(a)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            path.addCircle(cx, cy, r * 0.45f, Path.Direction.CCW)
            path.fillType = Path.FillType.EVEN_ODD
        }

        override fun draw(c: Canvas) {
            c.drawPath(path, paint)
        }

        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun fmt(v: Float): String {
        val r = Math.round(v * 100f) / 100f
        return if (r == Math.round(r).toFloat()) Math.round(r).toString() else r.toString()
    }

    /**
     * Settings panel for a group of number options (the gear beside Materials). It docks at the bottom without
     * dimming the screen, so the card stays visible, and every change is applied to the card right away.
     * Cancel puts the old values back. [after] runs when the panel closes (the effect menu reopens).
     */
    private fun openPanel(groupName: String, after: () -> Unit) {
        val g = find(groupName) ?: run { after(); return }
        val fields = g.items.mapNotNull { find(it.second) }.filter { it.type != "multi_select" }
        val ctx = Ctx(layout, state.values)
        val before = fields.associate { it.name to (state.values[it.name] ?: ctx.value(it.name)) }
        val handler = Handler(Looper.getMainLooper())
        var pending = false
        fun live() {
            if (pending) return
            pending = true
            handler.postDelayed({ pending = false; onChanged() }, 90)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(4))
        }
        val resetters = ArrayList<() -> Unit>()
        for (f in fields) {
            val (lo, hi) = sliderRange(f.name) ?: (0 to 200)
            val defText = f.default ?: before[f.name] ?: lo.toString()
            val startV = (before[f.name]?.toFloatOrNull() ?: defText.toFloatOrNull() ?: lo.toFloat())
            val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val name = label(f.desc?.takeIf { it.isNotBlank() }?.substringBefore(" (") ?: f.name)
            head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val num = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSingleLine(true)
                setText(fmt(startV))
                setTextColor(pink)
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.BLACK)
                    setStroke(dp(2), hot)
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setSelectAllOnFocus(true)
            }
            head.addView(num, LinearLayout.LayoutParams(dp(90), dp(40)))
            box.addView(head)
            val bar = SeekBar(activity).apply {
                max = (hi - lo) * 10
                progress = Math.round((startV - lo) * 10f).coerceIn(0, max)
                progressTintList = android.content.res.ColorStateList.valueOf(pink)
                thumbTintList = android.content.res.ColorStateList.valueOf(pink)
            }
            var busy = false
            fun apply(v: Float) {
                state.values[f.name] = fmt(v)
                live()
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser || busy) return
                    val v = lo + p / 10f
                    busy = true
                    num.setText(fmt(v))
                    busy = false
                    apply(v)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            num.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (busy) return
                    val v = s?.toString()?.toFloatOrNull()?.takeIf { it > 0f } ?: return
                    busy = true
                    bar.progress = Math.round((v - lo) * 10f).coerceIn(0, bar.max)
                    busy = false
                    apply(v)
                }
            })
            resetters.add {
                val d = (f.default?.toFloatOrNull() ?: 100f)
                num.setText(fmt(d))
            }
            box.addView(bar, row())
        }

        var finished = false
        val dlg = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(g.desc?.takeIf { it.isNotBlank() } ?: g.name)
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Done") { _, _ -> finished = true; onChanged() }
            .setNegativeButton("Cancel") { _, _ ->
                finished = true
                for ((k, v) in before) state.values[k] = v
                onChanged()
            }
            .setNeutralButton("Reset", null)
            .create()
        dlg.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!finished) {
                for ((k, v) in before) state.values[k] = v
                onChanged()
            }
            after()
        }
        dlg.show()
        val w = dlg.window
        w?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        w?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        w?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        var atTop = false
        fun place() {
            w?.setGravity(if (atTop) Gravity.TOP else Gravity.BOTTOM)
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL)
        }
        place()
        style(dlg)
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            setTextColor(Color.parseColor("#e7c3d6"))
            setOnClickListener { resetters.forEach { it() } }
        }
        // Move: the panel can sit at the bottom or the top, whichever leaves the card visible.
        val move = Button(activity).apply {
            text = "Move ↑"
            setAllCaps(false)
            setTextColor(Color.parseColor("#e7c3d6"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                atTop = !atTop
                text = if (atTop) "Move ↓" else "Move ↑"
                place()
            }
        }
        box.addView(move, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
    }

    /**
     * Options that read better as a slider: Condense and the font size limits (percent).
     * Matching is by prefix so a box can have its own copy in the layout (condense_pendulum, maxsize_flavor, ...).
     */
    private fun sliderRange(name: String): Pair<Int, Int>? {
        val n = name.lowercase()
        return when {
            n.startsWith("condense") -> 30 to 100
            n.startsWith("maxsize") -> 50 to 120
            n.startsWith("minsize") -> 0 to 100
            n.startsWith("justifylast") || n.startsWith("justifyflow") -> 0 to 100
            // materialssize, materialswidth, materialsline (but not the "materials" text itself)
            n.startsWith("materials") && n != "materials" -> 50 to 150
            else -> null
        }
    }

    private fun commitAll(commits: List<() -> Unit>) {
        commits.forEach { it() }
    }

    private fun row(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        return lp
    }

    private fun label(text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(Color.parseColor("#e7c3d6"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(6), 0, dp(4))
    }

    private fun hotButton(text: String) = Button(activity).apply {
        this.text = text
        setAllCaps(false)
        setTextColor(pink)
        typeface = Typeface.DEFAULT_BOLD
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            setStroke(dp(2), hot)
            cornerRadius = dp(10).toFloat()
        }
        setPadding(dp(14), 0, dp(14), 0)
    }

    /** Black dialog with a hot-pink gradient outline, like the TurtleBox menus. */
    private fun style(dlg: AlertDialog) {
        val outer = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#ff1493"), Color.parseColor("#ff00aa"), Color.parseColor("#ff6ec7")),
        ).apply { cornerRadius = dp(22).toFloat() }
        val inner = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(20).toFloat()
        }
        val layers = LayerDrawable(arrayOf(outer, InsetDrawable(inner, dp(2))))
        dlg.window?.setBackgroundDrawable(layers)
        dlg.findViewById<TextView>(activity.resources.getIdentifier("alertTitle", "id", "android"))?.setTextColor(Color.parseColor("#ff4da6"))
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(pink)
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#e7c3d6"))
    }
}


/** Lays its children out left to right and wraps to the next line, for rows of option chips. */
class FlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec)
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(maxW, y + rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}
