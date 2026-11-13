package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import kotlin.math.min
import android.widget.TextView

/**
 * The Name Style panel (after YGOcarder): Auto / Predefined / Custom, with Color, Shadow, Outline, Gradient,
 * Pattern, Font and Emboss sections and "Save preset". The value lives in one data entry ([key]) as text
 * that [NameStyle] reads. Presets come from the layout's `namepresets` dropdown plus the ones saved here
 * (kept in the set under `namepresets_user`). Changes show on the card right away.
 */
class NameStyleEditor(
    private val activity: Activity,
    private val layout: Layout,
    private val state: CardState,
    private val key: String,
    private val onChanged: () -> Unit,
    private val previewer: ((List<Map<String, String>?>) -> List<Bitmap>)?,
    private val after: () -> Unit,
) {
    private val pink = Color.parseColor("#ff6ec7")
    private val hot = Color.parseColor("#ff1493")
    private val soft = Color.parseColor("#e7c3d6")
    private val userKey = "namepresets_user"

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var refreshPreview: () -> Unit = {}
    private fun live() {
        if (pending) return
        pending = true
        handler.postDelayed({ pending = false; onChanged(); refreshPreview() }, 90)
    }

    private fun current(): String = state.values[key] ?: layout.datas[key]?.default ?: "auto"

    private fun userPresets(): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        for (line in (state.values[userKey] ?: "").split('\n')) {
            val i = line.indexOf('\t')
            if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
        }
        return m
    }

    private fun saveUserPresets(m: Map<String, String>) {
        state.values[userKey] = m.entries.joinToString("\n") { it.key + "\t" + it.value }
    }

    private fun chip(text: String, on: Boolean, click: () -> Unit) = TextView(activity).apply {
        this.text = text
        setTextColor(if (on) pink else soft)
        typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            setStroke(dp(2), if (on) hot else Color.parseColor("#3a1a2c"))
            cornerRadius = dp(10).toFloat()
        }
        setOnClickListener { click() }
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun label(t: String) = TextView(activity).apply {
        text = t
        setTextColor(soft)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(6), 0, dp(4))
    }

    fun show() {
        var st = NameStyle.parse(current())
        var section = "Color"
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(4))
        }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val before = state.values[key]
        val beforeUser = state.values[userKey]

        fun commit() {
            state.values[key] = st.serialize()
            live()
        }

        fun setV(k: String, v: String) {
            st.v[k] = v
            commit()
        }

        fun fmtN(v: Float, int: Boolean) = if (int) Math.round(v).toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

        fun slider(name: String, k: String, lo: Float, hi: Float, def: Float, int: Boolean = false) {
            val start = st.v[k]?.toFloatOrNull() ?: def
            val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(label(name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val val_ = TextView(activity).apply { text = fmtN(start, int); setTextColor(pink); typeface = Typeface.DEFAULT_BOLD }
            head.addView(val_)
            body.addView(head)
            val steps = 200
            val bar = SeekBar(activity).apply {
                max = steps
                progress = ((start - lo) / (hi - lo) * steps).toInt().coerceIn(0, steps)
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#ff1493"))
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = lo + (hi - lo) * p / steps
                    val t = fmtN(v, int)
                    val_.text = t
                    setV(k, t)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            body.addView(bar)
        }

        fun header(name: String, k: String) {
            val cb = android.widget.Switch(activity).apply {
                text = name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                isChecked = st.v[k] == "1"
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#ff6ec7"))
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#5a1a40"))
                setBackgroundColor(Color.parseColor("#14101a"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnCheckedChangeListener { _, on -> setV(k, if (on) "1" else "0"); rebuildChips() }
            }
            body.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun colorPicker(title: String?, k: String, def: String, full: Boolean) {
            if (title != null) body.addView(label(title))
            val cp = ColorPickerView(activity, parseHex(st.v[k] ?: def), full) { col -> setV(k, hex(col)) }
            body.addView(cp)
        }

        fun pad(range: Int, kx: String, ky: String) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val px = PadView(activity, range) { x, y -> st.v[kx] = x.toString(); st.v[ky] = y.toString(); commit() }
            px.px = st.v[kx]?.toFloatOrNull()?.toInt() ?: 0
            px.py = st.v[ky]?.toFloatOrNull()?.toInt() ?: 0
            row.addView(px, LinearLayout.LayoutParams(dp(150), dp(150)))
            val side = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            fun field(name: String, k: String, other: () -> Unit) {
                side.addView(label(name))
                val et = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                    setSingleLine(true)
                    setText(st.v[k] ?: "0")
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setSelectAllOnFocus(true)
                    background = GradientDrawable().apply { setColor(Color.parseColor("#000000")); setStroke(2, Color.parseColor("#ff1493")); cornerRadius = 8f }
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            val v = s?.toString()?.toIntOrNull() ?: return
                            setV(k, v.toString())
                            px.px = st.v[kx]?.toFloatOrNull()?.toInt() ?: 0
                            px.py = st.v[ky]?.toFloatOrNull()?.toInt() ?: 0
                            px.invalidate()
                        }
                    })
                }
                side.addView(et, LinearLayout.LayoutParams(dp(110), dp(42)))
            }
            field("X Offset:", kx) { }
            field("Y Offset:", ky) { }
            row.addView(side)
            body.addView(label("Position"))
            body.addView(row)
        }

        fun dial(title: String, k: String, def: Int) {
            body.addView(label(title))
            val d = DialView(activity) { a -> setV(k, a.toString()) }
            d.angle = st.v[k]?.toFloatOrNull()?.toInt() ?: def
            body.addView(d, LinearLayout.LayoutParams(dp(150), dp(150)))
            val step = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val txt = TextView(activity).apply { setTextColor(pink); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
            fun upd() { txt.text = "${d.angle}°" }
            fun stepBtn(t: String, delta: Int) = Button(activity).apply {
                text = t
                setTextColor(pink)
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(2), hot); cornerRadius = dp(10).toFloat() }
                setOnClickListener { d.angle = d.angle + delta; setV(k, d.angle.toString()); upd() }
            }
            step.addView(stepBtn("−", -1), LinearLayout.LayoutParams(dp(60), dp(44)))
            step.addView(txt, LinearLayout.LayoutParams(0, dp(44), 1f))
            step.addView(stepBtn("+", 1), LinearLayout.LayoutParams(dp(60), dp(44)))
            upd()
            // keep the number in step while dragging the dial
            val orig = d
            body.addView(step, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            d.setOnTouchListener { v, ev -> val r = d.onTouchEvent(ev); upd(); r }
            if (orig !== d) return
        }

        fun tiles(items: List<Pair<String, Map<String, String>?>>, selected: (Int) -> Boolean, pick: (Int) -> Unit) {
            val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val views = ArrayList<ImageView>()
            val borders = ArrayList<GradientDrawable>()
            var i = 0
            while (i < items.size) {
                val r = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                for (j in i until min(i + 2, items.size)) {
                    val iv = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    val bd = GradientDrawable().apply { setColor(Color.parseColor("#8a8a90")); cornerRadius = dp(10).toFloat() }
                    bd.setStroke(dp(3), if (selected(j)) Color.parseColor("#ff1493") else Color.parseColor("#3a3a3a"))
                    iv.background = bd
                    iv.setPadding(dp(4), dp(4), dp(4), dp(4))
                    iv.contentDescription = items[j].first
                    iv.setOnClickListener {
                        pick(j)
                        for (n in borders.indices) borders[n].setStroke(dp(3), if (selected(n)) Color.parseColor("#ff1493") else Color.parseColor("#3a3a3a"))
                    }
                    views.add(iv)
                    borders.add(bd)
                    r.addView(iv, LinearLayout.LayoutParams(0, dp(66), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
                grid.addView(r)
                i += 2
            }
            body.addView(grid)
            if (previewer == null) {
                for ((n, v) in views.withIndex()) {
                    val t = TextView(activity)
                    v.contentDescription = items[n].first
                }
                body.addView(label("(preview not available)"))
            } else {
                val maps = items.map { it.second }
                Thread {
                    val bms = try { previewer.invoke(maps) } catch (e: Throwable) { emptyList() }
                    activity.runOnUiThread { for ((n, b) in bms.withIndex()) if (n < views.size) views[n].setImageBitmap(b) }
                }.start()
            }
        }

        fun withFill(extra: Map<String, String>): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            st.v["fill"]?.let { m["fill"] = it }
            m.putAll(extra)
            return m
        }

        fun gradientUi() {
            header("Has Gradient?", "grad")
            val stops = ArrayList<GStop>()
            for (part in (st.v["gstops"] ?: "").split(',')) {
                val p = part.split('|')
                if (p.size == 2) stops.add(GStop(p[0].trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: continue, parseHex(p[1].trim())))
            }
            if (stops.size < 2) {
                stops.clear()
                stops.add(GStop(0f, parseHex(st.v["fill"] ?: "#000000")))
                stops.add(GStop(1f, Color.WHITE))
            }
            fun save() {
                st.v["gstops"] = stops.sortedBy { it.pos }.joinToString(",") { String.format(java.util.Locale.US, "%.3f", it.pos) + "|" + hex(it.color) }
                commit()
            }
            if (st.v["gstops"] == null) st.v["gstops"] = stops.sortedBy { it.pos }.joinToString(",") { String.format(java.util.Locale.US, "%.3f", it.pos) + "|" + hex(it.color) }
            val stopAt = EditText(activity)
            lateinit var picker: ColorPickerView
            lateinit var bar: GradientBarView
            bar = GradientBarView(activity, stops, { s ->
                stopAt.setText(Math.round(s.pos * 100f).toString())
                picker.setColor(s.color)
            }) {
                stopAt.setText(Math.round(bar.selected.pos * 100f).toString())
                save()
            }
            body.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(86)))
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label("Stop at:"))
            stopAt.apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                setText(Math.round(bar.selected.pos * 100f).toString())
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(Color.parseColor("#000000")); setStroke(2, Color.parseColor("#ff1493")); cornerRadius = 8f }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val v = s?.toString()?.toFloatOrNull() ?: return
                        val np = (v / 100f).coerceIn(0f, 1f)
                        if (Math.abs(np - bar.selected.pos) > 0.0005f) {
                            bar.selected.pos = np
                            bar.invalidate()
                            save()
                        }
                    }
                })
            }
            row.addView(stopAt, LinearLayout.LayoutParams(dp(80), dp(42)).apply { leftMargin = dp(8) })
            fun smallBtn(t: String, bg: String, click: () -> Unit) = Button(activity).apply {
                text = t
                setAllCaps(false)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(bg))
                background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(2), Color.parseColor(bg)); cornerRadius = dp(10).toFloat() }
                setOnClickListener { click() }
            }
            row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            row.addView(smallBtn("Remove", "#ff4d6d") {
                if (stops.size > 2) {
                    stops.remove(bar.selected)
                    bar.selected = stops.first()
                    stopAt.setText(Math.round(bar.selected.pos * 100f).toString())
                    picker.setColor(bar.selected.color)
                    bar.invalidate()
                    save()
                }
            }, LinearLayout.LayoutParams(dp(100), dp(42)))
            body.addView(row)
            body.addView(smallBtn("Add Point (${stops.size}/20)", "#ff6ec7") {
                if (stops.size < 20) {
                    val sorted = stops.sortedBy { it.pos }
                    var bi = 0
                    var gap = -1f
                    for (i in 0 until sorted.size - 1) if (sorted[i + 1].pos - sorted[i].pos > gap) { gap = sorted[i + 1].pos - sorted[i].pos; bi = i }
                    val a = sorted[bi]
                    val b = sorted[bi + 1]
                    val ns = GStop((a.pos + b.pos) / 2f, android.graphics.Color.rgb(
                        (Color.red(a.color) + Color.red(b.color)) / 2, (Color.green(a.color) + Color.green(b.color)) / 2, (Color.blue(a.color) + Color.blue(b.color)) / 2,
                    ))
                    stops.add(ns)
                    bar.selected = ns
                    stopAt.setText(Math.round(ns.pos * 100f).toString())
                    picker.setColor(ns.color)
                    bar.invalidate()
                    save()
                    revise()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
            dial("Gradient Direction", "gangle", 0)
            picker = ColorPickerView(activity, bar.selected.color, true) { col -> bar.selected.color = col; bar.invalidate(); save() }
            body.addView(picker)
        }

        fun sectionBody() {
            when (section) {
                "Color" -> colorPicker("Text Color", "fill", "#000000", true)
                "Shadow" -> {
                    header("Has Shadow?", "shadow")
                    pad(15, "shx", "shy")
                    slider("Blur:", "shblur", 0f, 20f, 0f)
                    colorPicker("Color", "shcolor", "#000000", false)
                }
                "Outline" -> {
                    header("Has Outline?", "outline")
                    pad(10, "olx", "oly")
                    slider("Thickness:", "olw", 0f, 30f, 0f)
                    colorPicker("Color", "olcolor", "#000000", false)
                }
                "Gradient" -> gradientUi()
                "Pattern" -> {
                    val items = layout.datas["namepatterns"]?.items ?: emptyList<Pair<String, String>>()
                    if (items.isEmpty()) {
                        body.addView(label("This layout has no namepatterns list."))
                    } else {
                        val list = ArrayList<Pair<String, Map<String, String>?>>()
                        list.add("None" to withFill(emptyMap()))
                        for (it in items) list.add(it.first to withFill(mapOf("pattern" to it.first)))
                        tiles(list, { n -> (st.v["pattern"] ?: "") == (if (n == 0) "" else items[n - 1].first) }) { n ->
                            setV("pattern", if (n == 0) "" else items[n - 1].first)
                        }
                    }
                }
                "Font" -> {
                    val items = layout.datas["namefonts"]?.items ?: emptyList<Pair<String, String>>()
                    if (items.isEmpty()) {
                        body.addView(label("This layout has no namefonts list."))
                    } else {
                        val list = ArrayList<Pair<String, Map<String, String>?>>()
                        list.add("Auto" to withFill(emptyMap()))
                        for (it in items) list.add(it.first to withFill(mapOf("font" to it.first)))
                        tiles(list, { n -> (st.v["font"] ?: "") == (if (n == 0) "" else items[n - 1].first) }) { n ->
                            setV("font", if (n == 0) "" else items[n - 1].first)
                        }
                    }
                }
                "Emboss" -> {
                    header("Use Emboss?", "emboss")
                    body.addView(label("Emboss effect will override some text features. Change the color in the Color section."))
                    slider("Thickness", "ethick", 0f, 10f, 0f)
                    slider("Pitch", "epitch", 0f, 90f, 90f, true)
                    dial("Yaw", "eyaw", 90)
                }
            }
        }

        lateinit var rebuild: () -> Unit
        val strip = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#8a8a90")); setStroke(dp(2), Color.parseColor("#3a1a2c")); cornerRadius = dp(12).toFloat() }
        }
        val tabRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val tabScroll = android.widget.HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) }
        val modeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(2), Color.parseColor("#ff1493")); cornerRadius = dp(12).toFloat() }
        }
        val modeBtns = LinkedHashMap<String, TextView>()
        fun paintMode() {
            for ((m, tv) in modeBtns) {
                val on = st.mode == m
                tv.setTextColor(if (on) Color.BLACK else soft)
                tv.setBackgroundColor(if (on) Color.parseColor("#ff1493") else Color.BLACK)
            }
        }
        refreshPreview = {
            val m: Map<String, String>? = when (st.mode) {
                "custom" -> st.v
                "predefined" -> presetValues(st.preset)
                else -> null
            }
            val pv = previewer
            if (pv != null) Thread {
                val b = try { pv.invoke(listOf(m)).firstOrNull() } catch (e: Throwable) { null }
                if (b != null) activity.runOnUiThread { strip.setImageBitmap(b) }
            }.start()
        }
        for ((lab, mode) in listOf("Auto" to "auto", "Predefined" to "predefined", "Custom" to "custom")) {
            val tv = TextView(activity).apply {
                text = lab
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(10), 0, dp(10))
            }
            modeBtns[mode] = tv
            tv.setOnClickListener {
                if (st.mode != mode) {
                    val old = st
                    st = NameStyle(mode)
                    if (mode == "custom") st.v = LinkedHashMap(if (old.mode == "predefined") presetValues(old.preset) else old.v)
                    if (mode == "predefined") st.preset = layout.datas["namepresets"]?.items?.firstOrNull()?.first ?: ""
                    commit()
                    rebuild()
                }
            }
            modeRow.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        var chipFlow: FlowLayout? = null
        val chipButtons = LinkedHashMap<String, TextView>()
        fun used(s: String) = when (s) {
            "Shadow" -> st.v["shadow"] == "1"
            "Outline" -> st.v["outline"] == "1"
            "Gradient" -> st.v["grad"] == "1"
            "Pattern" -> !st.v["pattern"].isNullOrEmpty()
            "Font" -> !st.v["font"].isNullOrEmpty()
            "Emboss" -> st.v["emboss"] == "1"
            else -> false
        }
        rebuildChipsRef = {
            for ((s, tv) in chipButtons) tv.text = s + if (used(s)) " ●" else ""
        }
        reviseRef = { rebuild() }

        rebuild = {
            body.removeAllViews()
            chipButtons.clear()
            tabScroll.visibility = if (st.mode == "custom") View.VISIBLE else View.GONE
            paintMode()
            refreshPreview()
            when (st.mode) {
                "predefined" -> {
                    val names = ArrayList<String>()
                    layout.datas["namepresets"]?.items?.forEach { names.add(it.first) }
                    userPresets().keys.forEach { names.add(it) }
                    tiles(names.map { it to presetValues(it) }, { n -> st.preset == names[n] }) { n ->
                        st.preset = names[n]
                        commit()
                    }
                }
                "custom" -> {
                    tabRow.removeAllViews()
                    for (s in listOf("Color", "Shadow", "Outline", "Gradient", "Pattern", "Font", "Emboss")) {
                        val tv = chip(s + if (used(s)) " ●" else "", section == s) { section = s; rebuild() }
                        chipButtons[s] = tv
                        tabRow.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
                    }
                    sectionBody()
                    val save = Button(activity).apply {
                        text = "Save preset"
                        setAllCaps(false)
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#ff1493"))
                        setOnClickListener {
                            val input = EditText(activity).apply { setSingleLine(true); setTextColor(Color.WHITE); hint = "Preset name" }
                            AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                                .setTitle("Save preset")
                                .setView(input)
                                .setPositiveButton("Save") { _, _ ->
                                    val n = input.text.toString().trim().replace('\t', ' ').replace('\n', ' ')
                                    if (n.isNotEmpty()) {
                                        val m = userPresets()
                                        m[n] = st.serialize().substringAfter('|')
                                        saveUserPresets(m)
                                        onChanged()
                                        rebuild()
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                    body.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })
                    val mine = userPresets()
                    if (mine.isNotEmpty()) {
                        body.addView(label("Your presets (tap to load, long-press to delete)"))
                        val flow2 = FlowLayout(activity, dp(6))
                        for (n in mine.keys.toList()) {
                            val c = chip(n, false) {
                                st.v = LinkedHashMap(NameStyle.kv(userPresets()[n] ?: ""))
                                commit()
                                rebuild()
                            }
                            c.setOnLongClickListener {
                                val m = userPresets(); m.remove(n); saveUserPresets(m); rebuild(); true
                            }
                            flow2.addView(c)
                        }
                        body.addView(flow2)
                    }
                }
            }
        }
        box.addView(body)
        rebuild()
        val land = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val screenH = activity.resources.displayMetrics.heightPixels
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(8) })
            addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            addView(tabScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        }
        val grip = TextView(activity).apply {
            text = "☰   drag here to move"
            setTextColor(soft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        // In landscape there is little height, so the preview and tabs scroll with everything else.
        val scrollH = if (land) (screenH * 0.78f).toInt() - dp(130) else (screenH * 0.62f).toInt() - dp(250)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), 0)
            addView(grip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (!land) addView(head)
            addView(ScrollView(activity).apply { addView(box) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, scrollH.coerceAtLeast(dp(120))))
        }
        if (land) box.addView(head, 0)
        box.setPadding(0, dp(4), 0, dp(4))
        refreshPreview()

        var finished = false
        val dlg = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Name Style")
            .setView(root)
            .setPositiveButton("Done") { _, _ -> finished = true; onChanged() }
            .setNegativeButton("Cancel") { _, _ ->
                finished = true
                if (before == null) state.values.remove(key) else state.values[key] = before
                if (beforeUser == null) state.values.remove(userKey) else state.values[userKey] = beforeUser
                onChanged()
            }
            .create()
        dlg.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!finished) {
                if (before == null) state.values.remove(key) else state.values[key] = before
                if (beforeUser == null) state.values.remove(userKey) else state.values[userKey] = beforeUser
                onChanged()
            }
            after()
        }
        dlg.show()
        val w = dlg.window
        w?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        w?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        w?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        w?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        w?.setGravity(Gravity.BOTTOM)
        val outer = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#ff1493"), Color.parseColor("#ff00aa"), Color.parseColor("#ff6ec7")),
        ).apply { cornerRadius = dp(22).toFloat() }
        val inner = GradientDrawable().apply { setColor(Color.parseColor("#000000")); cornerRadius = dp(20).toFloat() }
        w?.setBackgroundDrawable(LayerDrawable(arrayOf(outer, InsetDrawable(inner, dp(2)))))
        dlg.findViewById<TextView>(activity.resources.getIdentifier("alertTitle", "id", "android"))?.setTextColor(Color.parseColor("#ff4da6"))
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(pink)
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(soft)
        var dx = 0f
        var dy = 0f
        val drag = View.OnTouchListener { _, ev ->
            val lp = w?.attributes ?: return@OnTouchListener false
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val loc = IntArray(2)
                    w.decorView.getLocationOnScreen(loc)
                    val r = android.graphics.Rect()
                    activity.window.decorView.getWindowVisibleDisplayFrame(r)
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.x = loc[0]
                    lp.y = loc[1] - r.top
                    w.attributes = lp
                    dx = ev.rawX - lp.x
                    dy = ev.rawY - lp.y
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    lp.x = (ev.rawX - dx).toInt()
                    lp.y = (ev.rawY - dy).toInt()
                    w.attributes = lp
                }
            }
            true
        }
        grip.setOnTouchListener(drag)
        dlg.findViewById<TextView>(activity.resources.getIdentifier("alertTitle", "id", "android"))?.setOnTouchListener(drag)
    }

    private var rebuildChipsRef: () -> Unit = {}
    private var reviseRef: () -> Unit = {}
    private fun rebuildChips() = rebuildChipsRef()
    private fun revise() = reviseRef()

    private fun presetValues(name: String): Map<String, String> {
        userPresets()[name]?.let { return NameStyle.kv(it) }
        return layout.datas["namepresets"]?.items?.firstOrNull { it.first == name }?.let { NameStyle.kv(it.second) } ?: emptyMap()
    }
}
