package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
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
    private val after: () -> Unit,
) {
    private val pink = Color.parseColor("#ff6ec7")
    private val hot = Color.parseColor("#ff1493")
    private val soft = Color.parseColor("#e7c3d6")
    private val userKey = "namepresets_user"

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private fun live() {
        if (pending) return
        pending = true
        handler.postDelayed({ pending = false; onChanged() }, 90)
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
        // Start Custom from the preset that was chosen, so it can be tweaked.
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

        fun num(name: String, k: String, lo: Float, hi: Float, def: Float, int: Boolean = false) {
            val start = st.v[k]?.toFloatOrNull() ?: def
            fun f(v: Float) = if (int) Math.round(v).toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
            val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(label(name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val et = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                setSingleLine(true)
                setText(f(start))
                setTextColor(pink)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setSelectAllOnFocus(true)
                background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(2), hot); cornerRadius = dp(10).toFloat() }
            }
            head.addView(et, LinearLayout.LayoutParams(dp(90), dp(40)))
            body.addView(head)
            val steps = 100
            val bar = SeekBar(activity).apply {
                max = steps
                progress = ((start - lo) / (hi - lo) * steps).toInt().coerceIn(0, steps)
                progressTintList = android.content.res.ColorStateList.valueOf(pink)
                thumbTintList = android.content.res.ColorStateList.valueOf(pink)
            }
            var busy = false
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser || busy) return
                    val v = lo + (hi - lo) * p / steps
                    busy = true; et.setText(f(v)); busy = false
                    setV(k, f(v))
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (busy) return
                    val v = s?.toString()?.toFloatOrNull() ?: return
                    busy = true; bar.progress = ((v - lo) / (hi - lo) * steps).toInt().coerceIn(0, steps); busy = false
                    setV(k, f(v))
                }
            })
            body.addView(bar)
        }

        fun colorRow(name: String, k: String, def: String) {
            val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(label(name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = View(activity)
            fun paintSw(s: String) {
                val c = try { Color.parseColor(s) } catch (e: Exception) { Color.TRANSPARENT }
                sw.background = GradientDrawable().apply { setColor(c); setStroke(dp(2), pink); cornerRadius = dp(8).toFloat() }
            }
            val start = st.v[k] ?: def
            paintSw(start)
            head.addView(sw, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) })
            val et = EditText(activity).apply {
                setSingleLine(true)
                setText(start)
                setTextColor(pink)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setSelectAllOnFocus(true)
                background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(2), hot); cornerRadius = dp(10).toFloat() }
            }
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val t = s?.toString()?.trim() ?: return
                    try {
                        Color.parseColor(t)
                    } catch (e: Exception) {
                        return
                    }
                    paintSw(t)
                    setV(k, t)
                }
            })
            head.addView(et, LinearLayout.LayoutParams(dp(110), dp(40)))
            body.addView(head)
        }

        fun toggle(name: String, k: String, then: () -> Unit) {
            val cb = CheckBox(activity).apply {
                text = name
                setTextColor(pink)
                isChecked = st.v[k] == "1"
                buttonTintList = android.content.res.ColorStateList.valueOf(pink)
                setOnCheckedChangeListener { _, on -> setV(k, if (on) "1" else "0"); then() }
            }
            body.addView(cb)
        }

        fun chips(items: List<Pair<String, String>>, k: String, none: String?) {
            val flow = FlowLayout(activity, dp(6))
            val all = ArrayList<Pair<String, String>>()
            if (none != null) all.add(none to "")
            all.addAll(items)
            val views = ArrayList<Pair<TextView, String>>()
            for ((lab, v) in all) {
                val tv = chip(lab, (st.v[k] ?: "") == v) {
                    setV(k, v)
                    for ((t, vv) in views) {
                        val on = vv == v
                        t.setTextColor(if (on) pink else soft)
                        t.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        (t.background as GradientDrawable).setStroke(dp(2), if (on) hot else Color.parseColor("#3a1a2c"))
                    }
                }
                views.add(tv to v)
                flow.addView(tv)
            }
            body.addView(flow)
        }

        fun gradient() {
            val on = st.v["grad"] == "1"
            body.addView(label("Turn on, then set the angle and stops. A stop is  position|#color  (0 to 1), separated by commas."))
            toggle("Use gradient", "grad") { }
            num("Angle", "gangle", 0f, 360f, 0f, true)
            body.addView(label("Stops"))
            val et = EditText(activity).apply {
                setText(st.v["gstops"] ?: "0.000|#ffffff,1.000|#888888")
                setTextColor(pink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(2), hot); cornerRadius = dp(10).toFloat() }
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    setV("gstops", s?.toString()?.trim() ?: return)
                }
            })
            body.addView(et)
            if (!on && st.v["gstops"] == null) st.v["gstops"] = "0.000|#ffffff,1.000|#888888"
        }

        lateinit var rebuild: () -> Unit

        fun sectionBody() {
            when (section) {
                "Color" -> colorRow("Text color", "fill", "#ffffff")
                "Shadow" -> {
                    toggle("Use shadow", "shadow") { }
                    colorRow("Shadow color", "shcolor", "#000000")
                    num("Offset X", "shx", -30f, 30f, 2f)
                    num("Offset Y", "shy", -30f, 30f, 2f)
                    num("Blur", "shblur", 0f, 30f, 2f)
                }
                "Outline" -> {
                    toggle("Use outline", "outline") { }
                    colorRow("Outline color", "olcolor", "#000000")
                    num("Width", "olw", 0f, 30f, 4f)
                    num("Offset X", "olx", -20f, 20f, 0f)
                    num("Offset Y", "oly", -20f, 20f, 0f)
                }
                "Gradient" -> gradient()
                "Pattern" -> {
                    val items = layout.datas["namepatterns"]?.items ?: emptyList<Pair<String, String>>()
                    if (items.isEmpty()) body.addView(label("This layout has no namepatterns list."))
                    else chips(items.map { it.first to it.first }, "pattern", "None")
                }
                "Font" -> {
                    val items = layout.datas["namefonts"]?.items ?: emptyList<Pair<String, String>>()
                    if (items.isEmpty()) body.addView(label("This layout has no namefonts list."))
                    else chips(items.map { it.first to it.first }, "font", "Layout default")
                }
                "Emboss" -> {
                    toggle("Use emboss", "emboss") { }
                    num("Light angle (pitch)", "epitch", 0f, 360f, 90f, true)
                    num("Light height (yaw)", "eyaw", 0f, 180f, 90f, true)
                    num("Thickness", "ethick", 0f, 10f, 0f)
                }
            }
        }

        rebuild = {
            body.removeAllViews()
            val rg = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
            for ((lab, mode) in listOf("Auto" to "auto", "Predefined" to "predefined", "Custom" to "custom")) {
                val rb = RadioButton(activity).apply {
                    text = lab
                    id = View.generateViewId()
                    setTextColor(pink)
                    buttonTintList = android.content.res.ColorStateList.valueOf(pink)
                    isChecked = st.mode == mode
                }
                rg.addView(rb)
                rb.setOnClickListener {
                    if (st.mode != mode) {
                        val old = st
                        st = NameStyle(mode)
                        if (mode == "custom") {
                            st.v = LinkedHashMap(
                                if (old.mode == "predefined") presetValues(old.preset) else old.v,
                            )
                        }
                        if (mode == "predefined") st.preset = layout.datas["namepresets"]?.items?.firstOrNull()?.first ?: ""
                        commit()
                        rebuild()
                    }
                }
            }
            body.addView(rg)
            when (st.mode) {
                "predefined" -> {
                    val all = ArrayList<Pair<String, String>>()
                    layout.datas["namepresets"]?.items?.forEach { all.add(it.first to it.first) }
                    userPresets().keys.forEach { all.add(it to it) }
                    val flow = FlowLayout(activity, dp(6))
                    for ((lab, v) in all) {
                        flow.addView(chip(lab, st.preset == v) {
                            st.preset = v
                            commit()
                            rebuild()
                        })
                    }
                    body.addView(flow)
                }
                "custom" -> {
                    val flow = FlowLayout(activity, dp(6))
                    for (s in listOf("Color", "Shadow", "Outline", "Gradient", "Pattern", "Font", "Emboss")) {
                        val used = when (s) {
                            "Shadow" -> st.v["shadow"] == "1"
                            "Outline" -> st.v["outline"] == "1"
                            "Gradient" -> st.v["grad"] == "1"
                            "Pattern" -> !st.v["pattern"].isNullOrEmpty()
                            "Font" -> !st.v["font"].isNullOrEmpty()
                            "Emboss" -> st.v["emboss"] == "1"
                            else -> true
                        }
                        flow.addView(chip(s + if (used && s != "Color") " ●" else "", section == s) { section = s; rebuild() })
                    }
                    body.addView(flow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
                    sectionBody()
                    val save = Button(activity).apply {
                        text = "Save preset"
                        setAllCaps(false)
                        setTextColor(pink)
                        background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(2), hot); cornerRadius = dp(10).toFloat() }
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
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                    body.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(10) })
                    val del = userPresets()
                    if (del.isNotEmpty()) {
                        body.addView(label("Your presets (tap to load, long-press to delete)"))
                        val flow2 = FlowLayout(activity, dp(6))
                        for (n in del.keys.toList()) {
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

        var finished = false
        val dlg = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Name Style")
            .setView(ScrollView(activity).apply { addView(box) })
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
        w?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.5f).toInt())
        w?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        w?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        w?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        w?.setGravity(Gravity.BOTTOM)
        val outer = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#ff1493"), Color.parseColor("#ff00aa"), Color.parseColor("#ff6ec7")),
        ).apply { cornerRadius = dp(22).toFloat() }
        val inner = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(20).toFloat() }
        w?.setBackgroundDrawable(LayerDrawable(arrayOf(outer, InsetDrawable(inner, dp(2)))))
        dlg.findViewById<TextView>(activity.resources.getIdentifier("alertTitle", "id", "android"))?.setTextColor(Color.parseColor("#ff4da6"))
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(pink)
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(soft)
    }

    private fun presetValues(name: String): Map<String, String> {
        userPresets()[name]?.let { return NameStyle.kv(it) }
        return layout.datas["namepresets"]?.items?.firstOrNull { it.first == name }?.let { NameStyle.kv(it.second) } ?: emptyMap()
    }
}
