package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
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
    /** Picks a file from the phone into the card ("font" or "pattern"); calls back with its path, or null. */
    private val importFile: ((String, (String?) -> Unit) -> Unit)? = null,
    private val after: () -> Unit,
) {
    private val pink = Theme.pink
    private val hot = Theme.hot
    private val soft = Theme.label
    private val userKey = "namepresets_user"

    companion object {
        /** Folder (inside the card's files) that imported pattern pictures are kept in. */
        const val PATTERN_DIR = "patterns/"

        /** The panel height the user last dragged to, kept for the session. */
        var lastH = 0
    }

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
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        background = Theme.choice(on, dp(8).toFloat(), dp(1).toFloat() * 1.5f, dp(4).toFloat())
        setTextColor(if (on) Theme.onPink else soft)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        setOnClickListener { click() }
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** A number / text box that keeps its text fully visible at a small fixed height. */
    private fun tidyField(et: EditText, sp: Float = 14f) {
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        et.setPadding(dp(6), 0, dp(6), 0)
        et.minimumHeight = 0
        et.minHeight = 0
        et.includeFontPadding = false
        et.gravity = Gravity.CENTER
    }

    private fun tidyBtn(b: Button, sp: Float) {
        b.minimumHeight = 0
        b.minHeight = 0
        b.minimumWidth = 0
        b.minWidth = 0
        b.setPadding(dp(6), 0, dp(6), 0)
        b.includeFontPadding = false
        b.stateListAnimator = null
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun label(t: String) = TextView(activity).apply {
        text = t
        setTextColor(soft)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(4), 0, dp(2))
    }

    fun show() {
        var st = NameStyle.parse(current())
        var section = "Color"
        // Which copy of a repeatable effect (shadows, outlines, inner shadows) is being edited, by its count key.
        val copySel = HashMap<String, Int>()
        var rebuildFwd: () -> Unit = {}
        var presetMine = false
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var cur: LinearLayout = body
        var host: LinearLayout = body
        val before = state.values[key]
        val beforeUser = state.values[userKey]

        fun commit() {
            state.values[key] = st.serialize()
            live()
        }

        fun setV(k: String, v: String) {
            st.v[k] = v
            commit()
            rebuildChips()
        }

        fun fmtN(v: Float, int: Boolean) = if (int) Math.round(v).toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

        fun slider(name: String, k: String, lo: Float, hi: Float, def: Float, int: Boolean = false) {
            val start = st.v[k]?.toFloatOrNull() ?: def
            val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(label(name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val val_ = TextView(activity).apply { text = fmtN(start, int); setTextColor(pink); typeface = Typeface.DEFAULT_BOLD }
            head.addView(val_)
            cur.addView(head)
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
            cur.addView(bar)
        }

        fun colorPicker(title: String?, k: String, def: String, full: Boolean) {
            if (title != null) cur.addView(label(title))
            val cp = ColorPickerView(activity, parseHex(st.v[k] ?: def), full) { col -> setV(k, hex(col)) }
            cur.addView(cp)
        }

        fun pad(range: Int, kx: String, ky: String) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val px = PadView(activity, range) { x, y -> st.v[kx] = x.toString(); st.v[ky] = y.toString(); commit() }
            px.px = st.v[kx]?.toFloatOrNull()?.toInt() ?: 0
            px.py = st.v[ky]?.toFloatOrNull()?.toInt() ?: 0
            row.addView(px, LinearLayout.LayoutParams(dp(196), dp(196)))
            val side = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, 0, 0) }
            fun field(name: String, k: String, other: () -> Unit) {
                side.addView(label(name))
                val et = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                    setSingleLine(true)
                    setText(st.v[k] ?: "0")
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setSelectAllOnFocus(true)
                    background = Theme.inset(dp(8).toFloat(), dp(1).toFloat() * 1.5f)
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
                tidyField(et)
                side.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
            }
            field("X", kx) { }
            field("Y", ky) { }
            row.addView(side, LinearLayout.LayoutParams(0, dp(196), 1f))
            cur.addView(row)
        }

        /** One button that sits inside a joined bar: no box of its own. */
        fun segBtn(t: String, color: Int, click: () -> Unit) = Button(activity).apply {
            text = t
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            tidyBtn(this, 13f)
            background = null
            setOnClickListener { click() }
        }

        /** Joins controls into one flat bar split by hairlines, with a thin shadow. */
        fun joined(vararg parts: Pair<View, Float>): LinearLayout {
            val bar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Theme.bar(dp(9).toFloat(), dp(1).toFloat(), dp(3).toFloat())
                setPadding(dp(1), dp(1), dp(1), dp(1))
            }
            for ((i, part) in parts.withIndex()) {
                if (i > 0) bar.addView(View(activity).apply { setBackgroundColor(Color.parseColor("#3a2a34")) }, LinearLayout.LayoutParams(dp(1), dp(18)))
                bar.addView(part.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, part.second))
            }
            return bar
        }

        fun dial(title: String, k: String, def: Int, parent: LinearLayout = cur, size: Int = 150) {
            parent.addView(label(title))
            val d = DialView(activity) { a -> setV(k, a.toString()) }
            d.angle = st.v[k]?.toFloatOrNull()?.toInt() ?: def
            val txt = TextView(activity).apply { setTextColor(pink); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setSingleLine(true) }
            fun upd() { txt.text = "${d.angle}°" }
            val bar = joined(
                segBtn("−", pink) { d.angle = d.angle - 1; setV(k, d.angle.toString()); upd() } to 1f,
                txt to 2f,
                segBtn("+", pink) { d.angle = d.angle + 1; setV(k, d.angle.toString()); upd() } to 1f,
            )
            val side = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, 0, 0) }
            side.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
            val rowD = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            rowD.addView(d, LinearLayout.LayoutParams(dp(size), dp(size)))
            rowD.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            parent.addView(rowD)
            upd()
            d.setOnTouchListener { _, ev -> val r = d.onTouchEvent(ev); upd(); r }
        }

        /** A button that imports a file from the phone into the card and then uses it. */
        fun importRow(text: String, kind: String, use: (String) -> Unit) {
            val imp = importFile ?: return
            val b = Button(activity).apply {
                this.text = text
                setAllCaps(false)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(pink)
                tidyBtn(this, 13f)
                background = Theme.choice(false, dp(8).toFloat(), dp(1).toFloat() * 1.5f, dp(4).toFloat())
                setOnClickListener { imp(kind) { path -> if (path != null) use(path) } }
            }
            host.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(8) })
        }

        fun tiles(items: List<Pair<String, Map<String, String>?>>, selected: (Int) -> Boolean, pick: (Int) -> Unit) {
            val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val views = ArrayList<ImageView>()
            val cells = ArrayList<LinearLayout>()
            val names = ArrayList<TextView>()
            fun paintNames() { for (n in names.indices) { val on = selected(n); names[n].setTextColor(if (on) Theme.onPink else soft); names[n].typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; cells[n].background = if (on) Theme.on(cut = dp(10).toFloat(), shadow = dp(5).toFloat()) else Theme.card(dp(10).toFloat(), dp(4).toFloat(), 0f) } }
            var i = 0
            while (i < items.size) {
                val r = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                for (j in i until min(i + 3, items.size)) {
                    val iv = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    iv.setBackgroundColor(Color.parseColor("#8a8a90"))
                    iv.setPadding(dp(3), dp(2), dp(3), dp(2))
                    iv.contentDescription = items[j].first
                    iv.setOnClickListener {
                        pick(j)
                        paintNames()
                    }
                    val nameTv = TextView(activity).apply {
                        text = items[j].first
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        gravity = Gravity.CENTER
                        setSingleLine(true)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        includeFontPadding = false
                    }
                    views.add(iv)
                    names.add(nameTv)
                    val cell = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
                    cells.add(cell)
                    cell.setOnClickListener { iv.performClick() }
                    cell.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
                    cell.addView(nameTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
                    r.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                }
                for (f in 0 until i + 3 - min(i + 3, items.size)) r.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
                grid.addView(r)
                i += 3
            }
            paintNames()
            host.addView(grid)
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

        fun card(title: String?): LinearLayout {
            val c = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(12))
                if (host === body) background = Theme.card(dp(14).toFloat(), dp(4).toFloat(), dp(3).toFloat()) else setPadding(0, dp(6), 0, dp(4))
            }
            if (title != null) c.addView(TextView(activity).apply {
                text = title.uppercase()
                setTextColor(Color.parseColor("#b98aa5"))
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                letterSpacing = 0.05f
                setPadding(0, 0, 0, dp(6))
            })
            host.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(if (host === body) 10 else 4) })
            return c
        }

        fun header(name: String, k: String, note: String? = null) {
            if (note != null) card(null).addView(TextView(activity).apply { text = note; setTextColor(Theme.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) })
        }

        fun gradientUi() {
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
            fun ser() = stops.sortedBy { it.pos }.joinToString(",") { String.format(java.util.Locale.US, "%.3f", it.pos) + "|" + hex(it.color) }
            fun save() { st.v["gstops"] = ser(); commit() }
            if (st.v["gstops"] == null) st.v["gstops"] = ser()
            val stopAt = EditText(activity)
            val info = TextView(activity).apply { setTextColor(Color.parseColor("#b98aa5")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setSingleLine(true) }
            lateinit var picker: ColorPickerView
            lateinit var bar: GradientBarView
            lateinit var addBtn: Button
            fun refreshInfo() {
                val sorted = stops.sortedBy { it.pos }
                info.text = "· ${sorted.indexOf(bar.selected) + 1} of ${stops.size}"
                addBtn.text = "Add Point (${stops.size}/20)"
            }
            fun select(g: GStop) {
                bar.selected = g
                stopAt.setText(Math.round(g.pos * 100f).toString())
                picker.setColor(g.color)
                bar.invalidate()
                refreshInfo()
            }
            fun smallBtn(t: String, bg: String, click: () -> Unit) = Button(activity).apply {
                text = t
                setAllCaps(false)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(bg))
                tidyBtn(this, 13f)
                background = Theme.choice(false, dp(8).toFloat(), dp(1).toFloat() * 1.5f, dp(4).toFloat())
                setOnClickListener { click() }
            }
            fun sq(t: String, click: () -> Unit) = smallBtn(t, "#ff1493", click).apply { setTextColor(pink) }

            // Card 1: switch, bar, stop stepper, add / remove
            val c1 = card(null)
            val sw = android.widget.Switch(activity).apply {
                text = "Has Gradient?"
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isChecked = st.v["grad"] == "1"
                val checked = intArrayOf(android.R.attr.state_checked)
                thumbTintList = android.content.res.ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(Color.WHITE, Color.parseColor("#c98aae")))
                trackTintList = android.content.res.ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(Color.parseColor("#ff1493"), Color.parseColor("#4a1a35")))
                setOnCheckedChangeListener { _, on -> setV("grad", if (on) "1" else "0"); rebuildChips() }
            }
            bar = GradientBarView(activity, stops, { g -> select(g) }) {
                stopAt.setText(Math.round(bar.selected.pos * 100f).toString())
                save()
                refreshInfo()
            }
            c1.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
            val prevBtn = segBtn("◀", pink) {
                val sorted = stops.sortedBy { it.pos }
                val i = sorted.indexOf(bar.selected)
                select(sorted[(i - 1 + sorted.size) % sorted.size])
            }
            val mid = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            mid.addView(label("Stop at").apply { setPadding(0, 0, dp(6), 0) })
            stopAt.apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                setText(Math.round(bar.selected.pos * 100f).toString())
                setTextColor(pink)
                typeface = Typeface.DEFAULT_BOLD
                tidyField(this)
                background = null
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
                            refreshInfo()
                        }
                    }
                })
            }
            mid.addView(stopAt, LinearLayout.LayoutParams(dp(44), dp(30)))
            mid.addView(TextView(activity).apply { text = "%"; setTextColor(pink); typeface = Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) })
            mid.addView(info.apply { setPadding(dp(6), 0, 0, 0) })
            val nextBtn = segBtn("▶", pink) {
                val sorted = stops.sortedBy { it.pos }
                val i = sorted.indexOf(bar.selected)
                select(sorted[(i + 1) % sorted.size])
            }
            val row = joined(prevBtn to 1f, mid to 5f, nextBtn to 1f)
            c1.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(6) })
            addBtn = segBtn("Add Point", pink) {
                if (stops.size < 20) {
                    val sorted = stops.sortedBy { it.pos }
                    var bi = 0
                    var gap = -1f
                    for (i in 0 until sorted.size - 1) if (sorted[i + 1].pos - sorted[i].pos > gap) { gap = sorted[i + 1].pos - sorted[i].pos; bi = i }
                    val a = sorted[bi]
                    val b = sorted[bi + 1]
                    val ns = GStop((a.pos + b.pos) / 2f, Color.rgb(
                        (Color.red(a.color) + Color.red(b.color)) / 2, (Color.green(a.color) + Color.green(b.color)) / 2, (Color.blue(a.color) + Color.blue(b.color)) / 2,
                    ))
                    stops.add(ns)
                    select(ns)
                    save()
                }
            }
            val removeBtn = segBtn("Remove", Color.parseColor("#ff4d6d")) {
                if (stops.size > 2) {
                    val sorted = stops.sortedBy { it.pos }
                    val i = sorted.indexOf(bar.selected)
                    stops.remove(bar.selected)
                    val rest = stops.sortedBy { it.pos }
                    select(rest[(i - 1).coerceIn(0, rest.size - 1)])
                    save()
                }
            }
            val btnRow = joined(addBtn to 1f, removeBtn to 1f)
            c1.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(10) })

            // Card 2: direction
            val c2 = card("Direction")
            val dRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val d = DialView(activity) { a -> setV("gangle", a.toString()) }
            d.angle = st.v["gangle"]?.toFloatOrNull()?.toInt() ?: 0
            dRow.addView(d, LinearLayout.LayoutParams(dp(76), dp(76)))
            val dCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
            val deg = TextView(activity).apply { setTextColor(pink); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setSingleLine(true) }
            fun upd() { deg.text = "${d.angle}°" }
            fun setAng(a: Int) { d.angle = a; setV("gangle", d.angle.toString()); upd() }
            val chips = joined(*listOf(0, 90, 180, 270).map { a -> segBtn("$a°", pink) { setAng(a) } to 1f }.toTypedArray())
            dCol.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
            val stepRow = joined(segBtn("−", pink) { setAng(d.angle - 1) } to 1f, deg to 2f, segBtn("+", pink) { setAng(d.angle + 1) } to 1f)
            dCol.addView(stepRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(10) })
            dRow.addView(dCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            d.setOnTouchListener { _, ev -> val r = d.onTouchEvent(ev); upd(); r }
            upd()
            c2.addView(dRow)

            // Card 3: colour of the selected stop
            val c3 = card("Stop colour")
            picker = ColorPickerView(activity, bar.selected.color, true, true) { col -> bar.selected.color = col; bar.invalidate(); save() }
            c3.addView(picker)
            refreshInfo()
        }

        /**
         * The copies of an effect that can be added several times, like Photoshop's "+" on Stroke and Drop Shadow:
         * pick one to edit, add a copy (of the one shown), move it up or down (the first is drawn on top), remove it.
         * Returns the key suffix of the copy being edited.
         */
        fun copyBar(countKey: String, keys: List<String>): String {
            val n = (st.v[countKey]?.toIntOrNull() ?: 1).coerceIn(1, NameFx.MAX_COPIES)
            val sel = (copySel[countKey] ?: 0).coerceIn(0, n - 1)
            copySel[countKey] = sel
            fun sfx(i: Int) = if (i == 0) "" else (i + 1).toString()
            fun move(a: Int, b: Int) {
                for (k in keys) {
                    val va = st.v[k + sfx(a)]
                    val vb = st.v[k + sfx(b)]
                    if (vb == null) st.v.remove(k + sfx(a)) else st.v[k + sfx(a)] = vb
                    if (va == null) st.v.remove(k + sfx(b)) else st.v[k + sfx(b)] = va
                }
            }
            val c = card("Copies · the first is drawn on top")
            val parts = ArrayList<Pair<View, Float>>()
            for (i in 0 until n) parts.add(segBtn(if (i == sel) "● ${i + 1}" else "${i + 1}", if (i == sel) hot else pink) {
                copySel[countKey] = i
                rebuildFwd()
            } to 1f)
            if (n < NameFx.MAX_COPIES) parts.add(segBtn("+ Add", pink) {
                for (k in keys) st.v[k + sfx(sel)]?.let { st.v[k + sfx(n)] = it }
                st.v[countKey] = (n + 1).toString()
                copySel[countKey] = n
                commit()
                rebuildFwd()
            } to 1.4f)
            c.addView(joined(*parts.toTypedArray()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
            if (n > 1) {
                val tools = joined(
                    segBtn("Move up", pink) {
                        if (sel > 0) { move(sel, sel - 1); copySel[countKey] = sel - 1; commit(); rebuildFwd() }
                    } to 1f,
                    segBtn("Move down", pink) {
                        if (sel < n - 1) { move(sel, sel + 1); copySel[countKey] = sel + 1; commit(); rebuildFwd() }
                    } to 1f,
                    segBtn("Remove", pink) {
                        for (i in sel until n - 1) move(i, i + 1)
                        for (k in keys) st.v.remove(k + sfx(n - 1))
                        st.v[countKey] = (n - 1).toString()
                        copySel[countKey] = minOf(sel, n - 2)
                        commit()
                        rebuildFwd()
                    } to 1f,
                )
                c.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(8) })
            }
            return sfx(sel)
        }

        /** A row of choices (one value of [k]) in a card. */
        fun choice(title: String?, k: String, opts: List<Pair<String, String>>, def: String) {
            cur = card(title)
            val now = st.v[k] ?: def
            val parts = opts.map { (label, v) ->
                segBtn(if (v == now) "● $label" else label, if (v == now) hot else pink) { setV(k, v); rebuildFwd() } to 1f
            }
            cur.addView(joined(*parts.toTypedArray()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        }

        fun sectionBody() {
            when (section) {
                "Color" -> { cur = card(null); colorPicker(null, "fill", "#000000", true) }
                "Shadow" -> {
                    header("Has Shadow?", "shadow")
                    val sf = copyBar("shcount", listOf("shx", "shy", "shblur", "shcolor", "shspread", "shopacity"))
                    cur = card("Position"); pad(15, "shx$sf", "shy$sf")
                    cur = card(null); slider("Blur", "shblur$sf", 0f, 20f, 0f)
                    cur = card(null); slider("Spread", "shspread$sf", 0f, 10f, 0f)
                    cur = card(null); slider("Opacity %", "shopacity$sf", 0f, 100f, 100f, true)
                    cur = card("Shadow colour"); colorPicker(null, "shcolor$sf", "#000000", false)
                }
                "Inner Shadow" -> {
                    val sf = copyBar("iscount", listOf("isx", "isy", "isblur", "iscolor", "isopacity"))
                    cur = card("Position"); pad(10, "isx$sf", "isy$sf")
                    cur = card(null); slider("Blur", "isblur$sf", 0f, 20f, 4f)
                    cur = card(null); slider("Opacity %", "isopacity$sf", 0f, 100f, 75f, true)
                    cur = card("Shadow colour"); colorPicker(null, "iscolor$sf", "#000000", false)
                }
                "Inner Glow" -> {
                    cur = card(null); slider("Size", "igblur", 0f, 30f, 6f)
                    cur = card(null); slider("Opacity %", "igopacity", 0f, 100f, 75f, true)
                    cur = card("Glow colour"); colorPicker(null, "igcolor", "#ffffff", false)
                }
                "Outline" -> {
                    header("Has Outline?", "outline")
                    val sf = copyBar("olcount", listOf("olw", "olx", "oly", "olcolor", "olpos", "olopacity"))
                    choice("Stroke position", "olpos$sf", listOf("Outside" to "out", "Inside" to "in", "Centre" to "center"), "out")
                    cur = card("Offset"); pad(10, "olx$sf", "oly$sf")
                    cur = card(null); slider("Thickness", "olw$sf", 0f, 30f, 0f)
                    cur = card(null); slider("Opacity %", "olopacity$sf", 0f, 100f, 100f, true)
                    cur = card("Outline colour"); colorPicker(null, "olcolor$sf", "#000000", false)
                }
                "Gradient" -> {
                    gradientUi()
                    choice("Style", "gstyle", listOf("Linear" to "linear", "Radial" to "radial"), "linear")
                    cur = card(null); slider("Opacity %", "gopacity", 0f, 100f, 100f, true)
                }
                "Colour Overlay" -> {
                    cur = card(null); slider("Opacity %", "coopacity", 0f, 100f, 100f, true)
                    cur = card("Overlay colour"); colorPicker(null, "cocolor", "#ff0000", false)
                }
                "Satin" -> {
                    cur = card(null); dial("Angle", "satangle", 19, cur, 130)
                    cur = card(null); slider("Distance", "satdist", 0f, 50f, 11f)
                    cur = card(null); slider("Size", "satsize", 0f, 50f, 14f)
                    cur = card(null); slider("Opacity %", "satopacity", 0f, 100f, 50f, true)
                    choice("Invert", "satinvert", listOf("Off" to "0", "On" to "1"), "0")
                    cur = card("Satin colour"); colorPicker(null, "satcolor", "#000000", false)
                }
                "Pattern" -> {
                    // The layout's namepatterns (stored by label), then pictures imported into the card (by path).
                    val named = layout.datas["namepatterns"]?.items ?: emptyList<Pair<String, String>>()
                    val values = ArrayList<String>()
                    val list = ArrayList<Pair<String, Map<String, String>?>>()
                    list.add("None" to withFill(emptyMap())); values.add("")
                    for (it in named) { list.add(it.first to withFill(mapOf("pattern" to it.first))); values.add(it.first) }
                    for (f in state.files.keys.filter { it.startsWith(PATTERN_DIR, true) }.sorted()) {
                        list.add(f.substringAfterLast('/').substringBeforeLast('.') + " (imported)" to withFill(mapOf("pattern" to f)))
                        values.add(f)
                    }
                    importRow("Import pattern…", "pattern") { setV("pattern", it); rebuildFwd() }
                    tiles(list, { n -> (st.v["pattern"] ?: "") == values[n] }) { n -> setV("pattern", values[n]) }
                }
                "Font" -> {
                    // namefonts (stored by label, as before), then every other font file in the layout and the card.
                    val named = layout.datas["namefonts"]?.items ?: emptyList<Pair<String, String>>()
                    val all = TextLayers.fontItems(layout, state.files.keys.filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) } + FontCatalog.layoutFonts)
                    val values = ArrayList<String>()
                    val list = ArrayList<Pair<String, Map<String, String>?>>()
                    list.add("Auto" to withFill(emptyMap())); values.add("")
                    for ((i, it) in all.withIndex()) {
                        val v = if (i < named.size) it.first else it.second
                        list.add(it.first to withFill(mapOf("font" to v))); values.add(v)
                    }
                    importRow("Import font…", "font") { setV("font", it); rebuildFwd() }
                    tiles(list, { n -> (st.v["font"] ?: "") == values[n] }) { n -> setV("font", values[n]) }
                }
                "Glow" -> {
                    header("Has Glow?", "glow")
                    cur = card(null); slider("Size", "glblur", 0f, 30f, 8f)
                    cur = card(null); slider("Spread", "glspread", 0f, 10f, 0f)
                    cur = card(null); slider("Opacity %", "glopacity", 0f, 100f, 100f, true)
                    cur = card("Glow colour"); colorPicker(null, "glcolor", "#ffffff", false)
                }
                "Emboss" -> {
                    header("Use Emboss?", "emboss", "Emboss overrides some text features. Change the colour in the Color row.")
                    cur = card(null); slider("Thickness", "ethick", 0f, 10f, 0f)
                    cur = card(null); slider("Pitch", "epitch", 0f, 90f, 90f, true)
                    cur = card("Light direction"); dial("Yaw", "eyaw", 90, cur, 130)
                }
            }
            cur = body
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
            background = Theme.bar(dp(11).toFloat(), dp(1).toFloat() * 1.5f, dp(4).toFloat())
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val modeBtns = LinkedHashMap<String, TextView>()
        fun paintMode() {
            val r = dp(10).toFloat()
            val ms = modeBtns.keys.toList()
            for ((m, tv) in modeBtns) {
                val on = st.mode == m
                tv.setTextColor(if (on) Theme.onPink else soft)
                tv.text = tv.text.toString().uppercase()
                tv.letterSpacing = 0.08f
                val first = m == ms.first()
                val last = m == ms.last()
                tv.background = Theme.segment(on, dp(8).toFloat())
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
        for ((lab, mode) in listOf("Auto" to "auto", "Preset" to "predefined", "Custom" to "custom")) {
            val tv = TextView(activity).apply {
                text = lab
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(8), 0, dp(8))
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
        val saveBtn = Button(activity).apply {
                        text = "Save preset"
                        tidyBtn(this, 14f)
                        setAllCaps(false)
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(pink)
                        text = "SAVE PRESET"
                        letterSpacing = 0.08f
                        background = Theme.field(dp(10).toFloat(), dp(1).toFloat() * 1.5f, dp(4).toFloat())
                        setOnClickListener {
                            val input = EditText(activity).apply { setSingleLine(true); setTextColor(Color.WHITE); hint = "Preset name" }
                            ThemedBuilder(activity)
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
        var chipFlow: FlowLayout? = null
        val rowUpd = LinkedHashMap<String, () -> Unit>()
        fun copiesNote(countKey: String): String {
            val n = st.v[countKey]?.toIntOrNull() ?: 1
            return if (n > 1) " · ×$n" else ""
        }
        fun used(s: String) = when (s) {
            "Shadow" -> st.v["shadow"] == "1"
            "Inner Shadow" -> st.v["ishadow"] == "1"
            "Inner Glow" -> st.v["iglow"] == "1"
            "Glow" -> st.v["glow"] == "1"
            "Outline" -> st.v["outline"] == "1"
            "Gradient" -> st.v["grad"] == "1"
            "Satin" -> st.v["satin"] == "1"
            "Colour Overlay" -> st.v["coverlay"] == "1"
            "Pattern" -> !st.v["pattern"].isNullOrEmpty()
            "Font" -> !st.v["font"].isNullOrEmpty()
            "Emboss" -> st.v["emboss"] == "1"
            else -> false
        }
        rebuildChipsRef = {
            for (u in rowUpd.values) u()
        }
        reviseRef = { rebuild() }

        fun manage() {
            val dlg = Dialog(activity)
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
            var showMine = true
            val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            fun pillBtn(t: String, c: Int, click: () -> Unit) = TextView(activity).apply {
                text = t
                gravity = Gravity.CENTER
                setTextColor(c)
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                background = null
                setOnClickListener { click() }
            }
            fun changed() { onChanged(); rebuild() }
            lateinit var fill: () -> Unit
            fun move(names: List<String>, i: Int, d: Int) {
                val j = i + d
                if (j < 0 || j >= names.size) return
                val order = names.toMutableList()
                val t = order[i]; order[i] = order[j]; order[j] = t
                val m = userPresets()
                saveUserPresets(LinkedHashMap<String, String>().also { out -> order.forEach { n -> m[n]?.let { out[n] = it } } })
                changed(); fill()
            }
            fun rename(old: String) {
                val input = EditText(activity).apply { setSingleLine(true); setTextColor(Color.WHITE); setText(old); setSelection(old.length) }
                ThemedBuilder(activity).setTitle("Rename preset").setView(input)
                    .setPositiveButton("Rename") { _, _ ->
                        val n = input.text.toString().trim().replace('\t', ' ').replace('\n', ' ')
                        val m = userPresets()
                        if (n.isEmpty() || n == old) return@setPositiveButton
                        if (m.containsKey(n) || layout.datas["namepresets"]?.items?.any { it.first == n } == true) {
                            Toast.makeText(activity, "That name is already used.", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        val out = LinkedHashMap<String, String>()
                        for ((k, v) in m) out[if (k == old) n else k] = v
                        saveUserPresets(out)
                        if (st.preset == old) st.preset = n
                        commit(); changed(); fill()
                    }.setNegativeButton("Cancel", null).show()
            }
            fun delete(n: String) {
                ThemedBuilder(activity).setTitle("Delete “$n”?")
                    .setPositiveButton("Delete") { _, _ ->
                        val m = userPresets(); m.remove(n); saveUserPresets(m)
                        changed(); fill()
                    }.setNegativeButton("Cancel", null).show()
            }
            fill = {
                list.removeAllViews()
                val mine = userPresets().keys.toList()
                val names = if (showMine) mine else (layout.datas["namepresets"]?.items?.map { it.first } ?: emptyList())
                if (names.isEmpty()) list.addView(label(if (showMine) "No saved presets yet. In Custom, tap Save preset." else "This layout has no built-in presets."))
                val views = ArrayList<ImageView>()
                for ((i, n) in names.withIndex()) {
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        background = Theme.card(dp(14).toFloat(), dp(4).toFloat(), dp(3).toFloat(), opaque = true)
                    }
                    if (showMine) {
                        val ud = joined(pillBtn("▲", pink) { move(mine, i, -1) } to 1f, pillBtn("▼", pink) { move(mine, i, 1) } to 1f)
                        row.addView(ud, LinearLayout.LayoutParams(dp(72), dp(34)).apply { rightMargin = dp(8) })
                    }
                    val iv = ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        background = GradientDrawable().apply { setColor(Color.parseColor("#8a8a90")); cornerRadius = dp(8).toFloat() }
                    }
                    views.add(iv)
                    row.addView(iv, LinearLayout.LayoutParams(dp(86), dp(42)))
                    row.addView(TextView(activity).apply {
                        text = n
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setSingleLine(true)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(10), 0, dp(6), 0)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (showMine) {
                        row.addView(joined(pillBtn("Rename", pink) { rename(n) } to 1f, pillBtn("Delete", Color.parseColor("#ff4d6d")) { delete(n) } to 1f), LinearLayout.LayoutParams(dp(130), dp(34)))
                    }
                    list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                }
                val pv = previewer
                if (pv != null && names.isNotEmpty()) {
                    val maps = names.map { withFill(presetValues(it)) }
                    Thread {
                        val bms = try { pv.invoke(maps) } catch (e: Throwable) { emptyList() }
                        activity.runOnUiThread { for ((n, b) in bms.withIndex()) if (n < views.size) views[n].setImageBitmap(b) }
                    }.start()
                }
            }
            val seg = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            val segBtns = ArrayList<TextView>()
            fun paintSeg() {
                for ((i, t) in segBtns.withIndex()) {
                    val on = (i == 1) == showMine
                    t.setTextColor(if (on) Theme.onPink else soft)
                    t.background = Theme.choice(on, dp(6).toFloat(), dp(1).toFloat() * 1.5f, dp(3).toFloat())
                }
            }
            for ((i, nm) in listOf("Built-in (view only)", "Mine").withIndex()) {
                val t = TextView(activity).apply {
                    text = nm
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setOnClickListener { showMine = i == 1; paintSeg(); fill() }
                }
                segBtns.add(t)
                seg.addView(t, LinearLayout.LayoutParams(0, dp(30), 1f))
            }
            paintSeg()
            val title = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            title.addView(TextView(activity).apply { text = "Manage presets"; setTextColor(Color.parseColor("#ff4da6")); typeface = Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            title.addView(TextView(activity).apply { text = "DONE"; setTextColor(pink); typeface = Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(dp(10), dp(8), dp(10), dp(8)); setOnClickListener { dlg.dismiss() } })
            val importBtn = pillBtn("Import (paste)", pink) {
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val txt = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString() ?: ""
                val m = userPresets()
                var n = 0
                for (line in txt.split('\n')) {
                    val i = line.indexOf('\t')
                    if (i > 0 && line.substring(i + 1).contains('=')) { m[line.substring(0, i)] = line.substring(i + 1); n++ }
                }
                if (n > 0) { saveUserPresets(m); changed(); showMine = true; paintSeg(); fill() }
                Toast.makeText(activity, if (n > 0) "Imported $n presets." else "Copy exported presets first, then tap Import.", Toast.LENGTH_SHORT).show()
            }
            val exportBtn = pillBtn("Export all (copy)", pink) {
                val m = userPresets()
                if (m.isEmpty()) {
                    Toast.makeText(activity, "No saved presets to export.", Toast.LENGTH_SHORT).show()
                } else {
                    val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("presets", m.entries.joinToString("\n") { it.key + "\t" + it.value }))
                    Toast.makeText(activity, "Copied ${m.size} presets. Paste them anywhere to keep or share.", Toast.LENGTH_LONG).show()
                }
            }
            val io = joined(importBtn to 1f, exportBtn to 1f)
            val sv = ScrollView(activity).apply { clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(8), dp(12), dp(8)); addView(list); Theme.unclip(this) }
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(14))
                background = Theme.dialog(dp(22).toFloat(), dp(4).toFloat())
            }
            root.addView(title)
            root.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(10) })
            root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(io, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(10) })
            dlg.setContentView(root)
            dlg.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                val dm = activity.resources.displayMetrics
                setLayout((dm.widthPixels * 0.94f).toInt(), (dm.heightPixels * 0.8f).toInt())
            }
            fill()
            dlg.show()
        }

        rebuildFwd = { rebuild() }
        rebuild = {
            body.removeAllViews()
            rowUpd.clear()
            tabScroll.visibility = View.GONE
            saveBtn.visibility = if (st.mode == "custom") View.VISIBLE else View.GONE
            paintMode()
            refreshPreview()
            when (st.mode) {
                "predefined" -> {
                    val sub = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    for ((lab, mine) in listOf("BUILT-IN" to false, "MINE" to true)) {
                        sub.addView(TextView(activity).apply {
                            text = lab
                            typeface = Typeface.DEFAULT_BOLD
                            letterSpacing = 0.08f
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                            gravity = Gravity.CENTER
                            setTextColor(if (presetMine == mine) Theme.onPink else soft)
                            background = Theme.choice(presetMine == mine, dp(8).toFloat(), dp(1).toFloat() * 1.5f, dp(4).toFloat())
                            setOnClickListener { presetMine = mine; rebuild() }
                        }, LinearLayout.LayoutParams(dp(82), dp(32)).apply { rightMargin = dp(6) })
                    }
                    sub.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                    sub.addView(TextView(activity).apply {
                        text = "IMPORT · EXPORT"
                        typeface = Typeface.DEFAULT_BOLD
                        letterSpacing = 0.06f
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTextColor(Theme.pink)
                        setPadding(dp(6), dp(8), dp(2), dp(8))
                        setOnClickListener { manage() }
                    })
                    body.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                    val names = ArrayList<String>()
                    if (presetMine) userPresets().keys.forEach { names.add(it) } else layout.datas["namepresets"]?.items?.forEach { names.add(it.first) }
                    if (names.isEmpty()) body.addView(label("No saved presets yet. In Custom, tap Save preset."))
                    tiles(names.map { it to presetValues(it) }, { n -> st.preset == names[n] }) { n ->
                        st.preset = names[n]
                        commit()
                    }
                }
                "custom" -> {
                    tabRow.removeAllViews()
                    // Photoshop's Layer Style list order (after the text's own colour and font).
                    for (s in listOf("Color", "Font", "Emboss", "Outline", "Inner Shadow", "Inner Glow", "Satin", "Colour Overlay", "Gradient", "Pattern", "Glow", "Shadow")) {
                        val open = section == s
                        val toggleKey = when (s) { "Shadow" -> "shadow"; "Satin" -> "satin"; "Colour Overlay" -> "coverlay"; "Inner Shadow" -> "ishadow"; "Inner Glow" -> "iglow"; "Glow" -> "glow"; "Outline" -> "outline"; "Gradient" -> "grad"; "Emboss" -> "emboss"; else -> null }
                        val tg = SkewToggle(activity)
                        val sum = TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); gravity = Gravity.END }
                        fun val_(k: String) = st.v[k] ?: ""
                        fun summary(): CharSequence {
                            val (v, rest) = when (s) {
                                "Color" -> val_("fill").ifEmpty { "#000000" } to ""
                                "Shadow" -> if (used(s)) "${val_("shx").ifEmpty { "0" }}, ${val_("shy").ifEmpty { "0" }}" to " · blur ${val_("shblur").ifEmpty { "0" }}" + copiesNote("shcount") else "off" to ""
                                "Inner Shadow" -> if (used(s)) "${val_("isx").ifEmpty { "0" }}, ${val_("isy").ifEmpty { "0" }}" to copiesNote("iscount") else "off" to ""
                                "Inner Glow" -> if (used(s)) "${val_("igblur").ifEmpty { "6" }} px" to " · ${val_("igcolor").ifEmpty { "#ffffff" }}" else "off" to ""
                                "Glow" -> if (used(s)) "${val_("glblur").ifEmpty { "8" }} px" to " · ${val_("glcolor").ifEmpty { "#ffffff" }}" else "off" to ""
                                "Outline" -> if (used(s)) "${val_("olw").ifEmpty { "0" }} px" to " · ${val_("olcolor").ifEmpty { "#000000" }}" + copiesNote("olcount") else "off" to ""
                                "Satin" -> if (used(s)) "${val_("satangle").ifEmpty { "19" }}°" to " · ${val_("satcolor").ifEmpty { "#000000" }}" else "off" to ""
                                "Colour Overlay" -> if (used(s)) val_("cocolor").ifEmpty { "#ff0000" } to " · ${val_("coopacity").ifEmpty { "100" }}%" else "off" to ""
                                "Gradient" -> if (used(s)) "${val_("gstops").split(',').count { it.contains('|') }} stops" to " · ${val_("gangle").ifEmpty { "0" }}°" else "off" to ""
                                "Pattern" -> val_("pattern").ifEmpty { "none" } to ""
                                "Font" -> val_("font").ifEmpty { "auto" } to ""
                                else -> if (used(s)) "on" to "" else "off" to ""
                            }
                            val sb = android.text.SpannableStringBuilder(v + rest)
                            if (used(s) || s == "Color") sb.setSpan(android.text.style.ForegroundColorSpan(Theme.pink), 0, v.length, 0)
                            if (used(s) || s == "Color") sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, v.length, 0)
                            return sb
                        }
                        fun upd() {
                            tg.on = s == "Color" || used(s)
                            tg.invalidate()
                            sum.setTextColor(Theme.muted)
                            sum.text = summary()
                        }
                        rowUpd[s] = { upd() }
                        upd()
                        tg.setOnClickListener {
                            when {
                                toggleKey != null -> setV(toggleKey, if (st.v[toggleKey] == "1") "0" else "1")
                                s == "Pattern" || s == "Font" -> if (used(s)) setV(s.lowercase(), "") else { section = s; rebuild() }
                            }
                        }
                        val head = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(14), 0, dp(14), 0)
                            addView(tg, LinearLayout.LayoutParams(dp(30), dp(16)).apply { rightMargin = dp(10) })
                            addView(TextView(activity).apply { text = s; typeface = Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Theme.pink) })
                            addView(sum, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
                            addView(TextView(activity).apply { text = if (open) "▾" else "▸"; setTextColor(Theme.label); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setPadding(dp(10), 0, 0, 0) })
                            setOnClickListener { section = if (open) "" else s; rebuild() }
                        }
                        val rowBox = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            background = Theme.card(dp(12).toFloat(), dp(4).toFloat(), dp(3).toFloat(), open)
                        }
                        rowBox.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
                        body.addView(rowBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                        if (open) {
                            val inner = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(10)) }
                            rowBox.addView(inner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                            host = inner
                            sectionBody()
                            host = body
                        }
                    }
                    val mine = userPresets()
                    if (mine.isNotEmpty()) {
                        body.addView(label("Your presets (tap to load)"))
                        val flow2 = FlowLayout(activity, dp(6))
                        for (n in mine.keys.toList()) {
                            val c = chip(n, false) {
                                st.v = LinkedHashMap(NameStyle.kv(userPresets()[n] ?: ""))
                                commit()
                                rebuild()
                            }
                            flow2.addView(c)
                        }
                        body.addView(flow2)
                        body.addView(chip("Manage presets", false) { manage() }.apply { layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } })
                    }
                }
            }
            Theme.noClip(body)
        }
        box.addView(body)
        Theme.unclip(box)
        rebuild()
        val land = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val screenH = activity.resources.displayMetrics.heightPixels
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(6) })
            addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
            addView(tabScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        }
        // A short pill handle: drag it (or the title) up or down to resize the panel.
        val grip = View(activity).apply {
            background = GradientDrawable().apply { setColor(pink); cornerRadius = dp(3).toFloat() }
        }
        val gripBox = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
            addView(grip, LinearLayout.LayoutParams(dp(44), dp(5)))
        }
        val titleTv = TextView(activity).apply {
            text = "NAME STYLE"
            letterSpacing = 0.12f
            setTextColor(Theme.hot)
            paint.shader = android.graphics.LinearGradient(0f, 0f, dp(170).toFloat(), 0f, Theme.metal, null, android.graphics.Shader.TileMode.CLAMP)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(6), 0, dp(6))
        }
        fun textBtn(t: String, c: Int) = TextView(activity).apply {
            text = t
            setTextColor(c)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val cancelTv = textBtn("CANCEL", soft)
        val doneTv = textBtn("DONE", pink)
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancelTv)
        }
        // In landscape there is little height, so the preview and tabs scroll with everything else.
        panelH = if (lastH > 0) lastH else if (land) (screenH * 0.86f).toInt() else (screenH * 0.6f).toInt()
        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            background = Theme.dock(0f, dp(3).toFloat(), dp(8).toFloat())
            (saveBtn.parent as? ViewGroup)?.removeView(saveBtn)
            addView(saveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { rightMargin = dp(10) })
            addView(doneTv.apply {
                text = "DONE"
                letterSpacing = 0.12f
                gravity = Gravity.CENTER
                setTextColor(Theme.onPink)
                background = Theme.on(cut = dp(10).toFloat(), shadow = dp(6).toFloat())
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            clipToPadding = false
            addView(gripBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (!land) addView(head)
            addView(ScrollView(activity).apply { clipChildren = false; clipToPadding = false; setPadding(dp(14), dp(10), dp(14), dp(16)); addView(box); Theme.unclip(this) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { leftMargin = -dp(14); rightMargin = -dp(14) })
            addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); leftMargin = -dp(14); rightMargin = -dp(14) })
        }
        if (land) box.addView(head, 0)
        box.setPadding(0, dp(2), 0, dp(4))
        refreshPreview()

        var finished = false
        val dlg = android.app.Dialog(activity)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(root)
        dlg.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!finished) {
                if (before == null) state.values.remove(key) else state.values[key] = before
                if (beforeUser == null) state.values.remove(userKey) else state.values[userKey] = beforeUser
                onChanged()
            }
            after()
        }
        doneTv.setOnClickListener { finished = true; onChanged(); dlg.dismiss() }
        cancelTv.setOnClickListener { dlg.dismiss() }
        dlg.show()
        val w = dlg.window
        w?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, panelH)
        w?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        w?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        w?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        w?.setGravity(Gravity.BOTTOM)
        w?.setBackgroundDrawable(Theme.dialog(dp(22).toFloat(), dp(4).toFloat()))
        var startY = 0f
        var startH = 0
        val drag = View.OnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = ev.rawY
                    startH = w?.decorView?.height ?: panelH
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val h = (startH + (startY - ev.rawY)).toInt().coerceIn(dp(190), (screenH * 0.94f).toInt())
                    lastH = h
                    w?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
                }
            }
            true
        }
        gripBox.setOnTouchListener(drag)
        titleTv.setOnTouchListener(drag)
    }

    private var panelH = 0
    private var rebuildChipsRef: () -> Unit = {}
    private var reviseRef: () -> Unit = {}
    private fun rebuildChips() = rebuildChipsRef()
    private fun revise() = reviseRef()

    private fun presetValues(name: String): Map<String, String> {
        userPresets()[name]?.let { return NameStyle.kv(it) }
        return layout.datas["namepresets"]?.items?.firstOrNull { it.first == name }?.let { NameStyle.kv(it.second) } ?: emptyMap()
    }
}

/** The little octagon on/off switch used on the effect rows. */
class SkewToggle(ctx: android.content.Context) : View(ctx) {
    var on = false
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    init {
        // Thin black glow around the octagon; the glow is drawn outside the view's bounds.
        val d = ctx.resources.displayMetrics.density
        background = CutDrawable(intArrayOf(Theme.plane3), cutTL = 16f * d * 0.3f, shadow = 3f * d)
    }
    private fun oct(l: Float, t: Float, r: Float, b: Float, q: Float) = android.graphics.Path().apply {
        moveTo(l, t + q); lineTo(l + q, t); lineTo(r - q, t); lineTo(r, t + q)
        lineTo(r, b - q); lineTo(r - q, b); lineTo(l + q, b); lineTo(l, b - q); close()
    }
    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val k = h * 0.3f
        val path = oct(0f, 0f, w, h, k)
        paint.shader = if (on) android.graphics.LinearGradient(0f, 0f, w, h, Theme.metal, null, android.graphics.Shader.TileMode.CLAMP) else null
        if (on) c.drawPath(path, paint)
        paint.shader = null
        paint.color = if (on) Theme.onPink else Color.parseColor("#6a5c65")
        val t = h * 0.7f; val pad = h * 0.15f
        val x = if (on) w - t - pad - k * 0.5f else pad + k * 0.5f
        val knob = oct(x, pad, x + t, pad + t, t * 0.3f)
        c.drawPath(knob, paint)
    }
}
