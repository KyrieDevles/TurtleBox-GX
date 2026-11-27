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
            if (d.type == "multi_select" || d.type == "button" || d.type == "fitstatus") continue
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
    private val namePreview: ((List<Map<String, String>?>) -> List<android.graphics.Bitmap>)? = null,
    /** Called with the screen y where a docked menu starts (so the card can fit above it), or -1 when it closes. */
    private val onTop: ((Int) -> Unit)? = null,
    /** Starts moving and resizing a text box on the card (data name, what to run when finished). */
    private val onBox: ((String, () -> Unit) -> Unit)? = null,
) {
    companion object {
        /** The height the user last dragged a docked menu to, kept for the session (0 = half the screen). */
        var dockH = 0

        /** Collapsible sections (nested menus) that are open, kept for the session. */
        val openSections = HashSet<String>()
    }

    private val pink = Theme.pink
    private val hot = Theme.hot

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun find(name: String): DataDef? =
        layout.datas[name] ?: layout.datas.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun open(click: String) {
        val d = find(click) ?: return
        if (d.type == "image") {
            pickImage(d.name)
            return
        }
        if (d.type == "togglegrid") {
            openToggleGrid(d)
            return
        }
        val fields = if (d.type == "multi_select") d.items.mapNotNull { find(it.second) } else listOf(d)
        if (fields.isEmpty()) return
        // An item whose value starts with # is a section heading (<item name="Spacing" value="#"/>): it is shown above the next field.
        // Headings work inside the collapsible sections too (Word spacing, Leading... in Paragraph).
        val headings = HashMap<String, String>()
        val seenGroups = HashSet<String>()
        fun collectHeadings(g: DataDef) {
            if (!seenGroups.add(g.name)) return
            var pending: String? = null
            for ((nm, v) in g.items) {
                if (v.startsWith("#")) { pending = nm; continue }
                val f = find(v) ?: continue
                if (pending != null) { headings[f.name] = pending; pending = null }
                if (f.type == "multi_select") collectHeadings(f)
            }
        }
        if (d.type == "multi_select") collectHeadings(d)

        val ctx = Ctx(layout, state.values)
        val commits = ArrayList<() -> Unit>()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(22))
            clipChildren = false
            clipToPadding = false
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
        // Changes show on the card at once; Cancel puts the old values back.
        val tracked = HashSet<String>()
        fun track(name: String) {
            if (!tracked.add(name)) return
            val old = state.values[name]
            restores.add { if (old == null) state.values.remove(name) else state.values[name] = old }
        }
        var into: LinearLayout = box
        var depth = 0

        // Auto / Manual (see AutoFit): while fit steps size the text, Size px, Leading px, Tracking and Horizontal
        // Scale show the values the card uses, dimmed. The first edit of any of them writes all four (manual).
        val autoRows = HashMap<String, SliderRow>()
        val fitRefreshers = ArrayList<() -> Unit>()
        fun sizeSet(g: AutoFit.Group): Boolean =
            (g.size?.let { (state.values[it] ?: ctx.value(it)).trim().toFloatOrNull() } ?: 0f) > 0f
        fun autoOf(name: String): AutoFit.Group? = AutoFit.groupFor(name)?.takeIf { it.auto && !sizeSet(it) }
        fun refreshFit() {
            for ((n, r) in autoRows) {
                val g = autoOf(n)
                if (g != null) r.show(g.values[n] ?: 0f, true)
                else if (r.auto()) r.show((state.values[n] ?: ctx.value(n)).trim().toFloatOrNull() ?: 0f, false)
            }
            fitRefreshers.forEach { it() }
        }
        fun freeze(g: AutoFit.Group, name: String, v: Float) {
            for (n in g.names()) {
                track(n)
                state.values[n] = if (n == name) fmt(v) else g.values[n]?.let { fmt(it) } ?: (state.values[n] ?: ctx.value(n))
            }
            refreshFit()
        }
        fun backToAuto(sizeName: String) {
            val g = AutoFit.groupFor(sizeName) ?: return
            for (n in g.names()) {
                track(n)
                state.values[n] = find(n)?.default ?: ""
            }
            refreshFit()
            liveApply()
        }
        fun changedIn(g: DataDef): Boolean = g.items.mapNotNull { find(it.second) }.any { m ->
            if (m.type == "multi_select") changedIn(m)
            else if (m.type == "box") (state.values[m.name] ?: "").isNotBlank()
            else {
                val v = state.values[m.name] ?: ctx.value(m.name)
                val def = m.default ?: ""
                v.toFloatOrNull()?.let { a -> def.toFloatOrNull()?.let { b -> a != b } ?: false } ?: (v != def && def.isNotEmpty() && m.items.isNotEmpty())
            }
        }
        fun build(f: DataDef) {
            if (f.type == "multi_select") {
                // A menu inside a menu is a collapsible section (like the tabs in Name Style).
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Theme.card(dp(14).toFloat(), dp(4).toFloat(), dp(3).toFloat(), false)
                    setPadding(dp(14), dp(2), dp(14), dp(2))
                }
                val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)) }
                val title = f.desc?.takeIf { it.isNotBlank() } ?: f.name
                val head = TextView(activity).apply {
                    setTextColor(Theme.pink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    letterSpacing = 0.12f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(14), 0, dp(14))
                }
                fun paintHead() {
                    val open = f.name in openSections
                    head.text = (if (open) "▾   " else "▸   ") + title.uppercase() + if (changedIn(f)) "   ●" else ""
                    body.visibility = if (open) View.VISIBLE else View.GONE
                }
                head.setOnClickListener {
                    if (!openSections.remove(f.name)) openSections.add(f.name)
                    paintHead()
                }
                card.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(card, row())
                Theme.unclip(card)
                Theme.unclip(body)
                val saved = into
                into = body
                depth++
                for (m in f.items.mapNotNull { find(it.second) }) build(m)
                depth--
                into = saved
                paintHead()
                return
            }
            headings[f.name]?.takeIf { depth > 0 }?.let { h ->
                // Inside a section: a pink caps line, no new card.
                into.addView(TextView(activity).apply {
                    text = h.uppercase()
                    setTextColor(Theme.hot)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    letterSpacing = 0.14f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(16), 0, dp(2))
                })
            }
            headings[f.name]?.takeIf { depth == 0 }?.let { h ->
                box.addView(TextView(activity).apply {
                    text = h.uppercase()
                    setTextColor(Theme.pink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    letterSpacing = 0.14f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(2), dp(14), 0, dp(6))
                })
                val sec = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Theme.card(dp(14).toFloat(), dp(4).toFloat(), dp(3).toFloat(), false)
                    setPadding(dp(12), dp(4), dp(12), dp(12))
                }
                box.addView(sec, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                Theme.unclip(sec)
                into = sec
            }
            if (f.type == "namestyle") {
                val cur = state.values[f.name] ?: ctx.value(f.name)
                val mode = cur.substringBefore('|').ifBlank { "auto" }
                val b = hotButton((f.desc?.takeIf { it.isNotBlank() } ?: "Name Style") + "   ●  " + mode)
                b.setOnClickListener {
                    commitAll(commits)
                    dlgRef?.dismiss()
                    onChanged()
                    NameStyleEditor(activity, layout, state, f.name, onChanged, namePreview) { open(click) }.show()
                }
                into.addView(b, row())
                return
            }
            if (f.type == "box") {
                val b = hotButton((f.desc?.takeIf { it.isNotBlank() } ?: "Move / resize text box…") + if ((state.values[f.name] ?: "").isNotBlank()) "   ●  moved" else "")
                b.setOnClickListener {
                    commitAll(commits)
                    dlgRef?.dismiss()
                    onChanged()
                    onBox?.invoke(f.name) { open(click) }
                }
                into.addView(b, row())
                return
            }
            if (f.type == "button") {
                // A button that opens a settings panel (gear = the multi_select group it shows).
                val grp = f.gear?.let { find(it) }
                if (grp != null) {
                    val members = grp.items.mapNotNull { find(it.second) }.filter { it.type != "multi_select" }
                    val changed = members.any { m ->
                        val v = (state.values[m.name] ?: ctx.value(m.name)).toFloatOrNull()
                        val def = m.default?.toFloatOrNull()
                        v != null && def != null && v != def
                    }
                    val b = hotButton((f.desc?.takeIf { it.isNotBlank() } ?: f.name) + if (changed) "   ●  changed" else "")
                    b.setOnClickListener {
                        commitAll(commits)
                        dlgRef?.dismiss()
                        onChanged()
                        openPanel(grp.name) { open(click) }
                    }
                    into.addView(b, row())
                }
                return
            }
            if (f.type == "fitstatus") {
                // Fit steps status: AUTO (which step), MANUAL (your values, with Back to auto fit) or OFF.
                val sizeName = f.default?.takeIf { it.isNotBlank() } ?: return
                val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
                line.addView(label(f.desc?.takeIf { it.isNotBlank() } ?: "Status"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val chip = TextView(activity).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    letterSpacing = 0.12f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                }
                line.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                into.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val sub = TextView(activity).apply {
                    setTextColor(Theme.muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(6), 0, 0)
                }
                into.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val back = hotButton("Back to auto fit")
                back.setOnClickListener { backToAuto(sizeName) }
                into.addView(back, row())
                fun paintStatus() {
                    val g = AutoFit.groupFor(sizeName)
                    val auto = autoOf(sizeName) != null
                    val word = when { g == null -> "—"; !g.on -> "OFF"; auto -> "AUTO"; else -> "MANUAL" }
                    chip.text = word
                    chip.setTextColor(if (auto) Theme.onPink else Theme.text)
                    chip.background = if (auto) Theme.segment(true, dp(6).toFloat())
                        else GradientDrawable().apply { setColor(Color.parseColor("#2d2d31")); cornerRadius = dp(4).toFloat() }
                    sub.text = when {
                        g == null -> "Shows once the card has been drawn."
                        !g.on -> "Smooth fit: the text shrinks and squeezes freely, within the limits under When Fit steps is off."
                        auto -> (if (g.step >= 0) "Step ${g.step + 1} of ${g.steps}" else "Below the last step: shrunk to fit") +
                            (if (g.squeeze < 0.995f) " · squeezed to ${Math.round(g.squeeze * 100f)}%" else "") +
                            ". Edit any value in Characters to take over."
                        else -> "Your Characters values are used. Back to auto fit lets the steps size the text again."
                    }
                    back.visibility = if (g != null && g.on && !auto) View.VISIBLE else View.GONE
                }
                fitRefreshers.add { paintStatus() }
                paintStatus()
                return
            }
            val inlineSlider = f.items.isEmpty() && f.type != "image" && sliderRange(f.name) != null
            if (!inlineSlider) into.addView(label(f.desc?.takeIf { it.isNotBlank() } ?: f.name))
            val current = state.values[f.name] ?: ctx.value(f.name)
            when {
                f.type == "image" -> {
                    val b = hotButton("Choose image…")
                    b.setOnClickListener { commitAll(commits); pickImage(f.name) }
                    into.addView(b, row())
                }
                f.items.isNotEmpty() && f.items.size <= 8 && f.items.all { it.first.length <= 34 } -> {
                    var chosen = current
                    // Two or three short choices share one joined bar; longer sets are a list of full-width octagons.
                    // Alignment (4 or more of the 7 Photoshop alignments): one joined bar of icons.
                    val icons = f.items.size >= 4 && f.items.all { it.second in AlignIcon.KINDS }
                    val joined = icons || (f.items.size <= 3 && f.items.all { it.first.length <= 12 })
                    val iconName = if (icons) TextView(activity).apply {
                        setTextColor(Theme.muted)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setPadding(dp(2), dp(6), 0, 0)
                    } else null
                    val flow: LinearLayout = LinearLayout(activity).apply {
                        orientation = if (joined) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                        if (joined) {
                            background = Theme.bar(dp(11).toFloat(), dp(1).toFloat() * 1.5f, dp(4).toFloat())
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                        }
                    }
                    val chips = ArrayList<Pair<TextView, String>>()
                    fun paint() {
                        for ((tv, v) in chips) {
                            val on = v == chosen
                            tv.setTextColor(if (on) Theme.onPink else Theme.muted)
                            tv.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            tv.background = if (joined) Theme.segment(on, dp(8).toFloat()) else Theme.choice(on, dp(10).toFloat(), dp(1).toFloat() * 1.5f, dp(5).toFloat())
                            tv.invalidate()
                        }
                        iconName?.text = f.items.firstOrNull { it.second == chosen }?.first ?: ""
                    }
                    for ((name, v) in f.items) {
                        val tv = (if (icons) AlignIcon(activity, v) else TextView(activity)).apply {
                            text = if (icons) "" else name
                            if (icons) contentDescription = name
                            isAllCaps = true
                            letterSpacing = 0.08f
                            gravity = android.view.Gravity.CENTER
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setPadding(if (icons) 0 else dp(14), 0, if (icons) 0 else dp(14), 0)
                            minimumHeight = dp(if (icons) 40 else if (joined) 38 else 40)
                            setOnClickListener {
                                chosen = v
                                paint()
                                state.values[f.name] = chosen
                                liveApply()
                            }
                        }
                        chips.add(tv to v)
                        if (joined) flow.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        else flow.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                    }
                    paint()
                    track(f.name)
                    commits.add { if (chosen != (state.values[f.name] ?: ctx.value(f.name))) state.values[f.name] = chosen }
                    into.addView(flow, row())
                    iconName?.let { into.addView(it) }
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
                                state.values[f.name] = chosen
                                liveApply()
                                dd.dismiss()
                            }.create()
                        dlg.show()
                        style(dlg)
                    }
                    track(f.name)
                    commits.add { if (chosen != (state.values[f.name] ?: ctx.value(f.name))) state.values[f.name] = chosen }
                    into.addView(b, row())
                }
                sliderRange(f.name) != null -> {
                    val (lo, hi) = sliderRange(f.name)!!
                    val start = (current.toFloatOrNull() ?: f.default?.toFloatOrNull() ?: lo.toFloat()).coerceIn(lo.toFloat(), hi.toFloat())
                    track(f.name)
                    val r = sliderRowFull(into, f, start, { v ->
                        val g = autoOf(f.name)
                        if (g != null) freeze(g, f.name, v) else state.values[f.name] = fmt(v)
                        liveApply()
                    }, { }, dp(8))
                    if (AutoFit.groupFor(f.name) != null) {
                        autoRows[f.name] = r
                        autoOf(f.name)?.let { g -> r.show(g.values[f.name] ?: start, true) }
                    }
                }
                else -> {
                    val multi = f.name.endsWith("text", ignoreCase = true) || current.contains('\n')
                    val et = EditText(activity).apply {
                        setText(current)
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setHintTextColor(Color.GRAY)
                        background = Theme.field(dp(12).toFloat(), dp(1).toFloat().coerceAtLeast(1f) * 1.5f, dp(7).toFloat())
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        if (multi) {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            minLines = 4
                            gravity = android.view.Gravity.TOP
                            setSingleLine(false)
                        } else {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            setSingleLine(true)
                        }
                    }
                    if (firstEdit == null) firstEdit = et
                    track(f.name)
                    et.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(e: Editable?) {
                            state.values[f.name] = e?.toString() ?: ""
                            liveApply()
                        }
                    })
                    commits.add {
                        val v = et.text.toString()
                        if (v != (state.values[f.name] ?: ctx.value(f.name))) state.values[f.name] = v
                    }
                    val gearName = f.gear
                    if (gearName != null && find(gearName) != null) {
                        val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
                        line.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        val g = gearButton()
                        g.setOnClickListener {
                            commitAll(commits)
                            dlgRef?.dismiss()
                            onChanged()
                            openPanel(gearName) { open(click) }
                        }
                        line.addView(g, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(10) })
                        into.addView(line, row())
                    } else {
                        into.addView(et, row())
                    }
                }
            }
        }
        for (f in fields) build(f)

        val scroll = object : ScrollView(activity) {
                // Content may spill sideways (glow) but not into the top and bottom padding, where an off-screen row's glow would show without its row.
                override fun dispatchDraw(canvas: android.graphics.Canvas) {
                    canvas.save()
                    // Once scrolled, start the clip a little lower so the glow of a row that just left the view is hidden too.
                    canvas.clipRect(0, scrollY + paddingTop + minOf(scrollY, dp(10)), width, scrollY + height - paddingBottom)
                    super.dispatchDraw(canvas)
                    canvas.restore()
                }
            }.apply { clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(6), dp(8), dp(6)); addView(box); isVerticalFadingEdgeEnabled = false }
        // A docked menu has a pill handle on top: drag it up or down to resize, all the way up to cover the card.
        val gripBox = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            addView(View(activity).apply { background = android.graphics.drawable.GradientDrawable().apply { setColor(pink); cornerRadius = dp(3).toFloat() } }, LinearLayout.LayoutParams(dp(44), dp(5)))
        }
        val content: View = if (restores.isNotEmpty()) LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(gripBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else scroll
        val dlg = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(d.desc?.takeIf { it.isNotBlank() } ?: d.name)
            .setView(content)
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
        val fitListener: () -> Unit = { refreshFit() }
        AutoFit.listener = fitListener
        dlg.setOnDismissListener {
            if (AutoFit.listener === fitListener) AutoFit.listener = null
            if (restores.isNotEmpty()) onTop?.invoke(-1)
        }
        dlg.show()
        // Docked at the bottom, half height, no dimming: the card stays visible above while sliders apply live.
        if (restores.isNotEmpty()) {
            val screenH = activity.resources.displayMetrics.heightPixels
            val h0 = if (dockH > 0) dockH else (screenH * 0.5f).toInt()
            onTop?.invoke(screenH - h0)
            dlg.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            dlg.window?.setGravity(android.view.Gravity.BOTTOM)
            dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h0)
            var startY = 0f
            var startH = 0
            val dragWin = dlg.window
            val drag = View.OnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { startY = ev.rawY; startH = dragWin?.decorView?.height ?: h0 }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val h = (startH + (startY - ev.rawY)).toInt().coerceIn(dp(190), (screenH * 0.94f).toInt())
                        dockH = h
                        dragWin?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> onTop?.invoke(screenH - dockH)
                }
                true
            }
            gripBox.setOnTouchListener(drag)
            dlg.findViewById<TextView>(activity.resources.getIdentifier("alertTitle", "id", "android"))?.setOnTouchListener(drag)
        }
        dlg.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        style(dlg)
    }


    /**
     * type="togglegrid": a grid of on/off buttons, one per item. Each item's value is the name of a two-state field;
     * its name is the text on the button. An item with an empty value is an empty slot. Changes show on the card at
     * once; Cancel puts the old values back.
     */
    private fun openToggleGrid(d: DataDef) {
        val ctx = Ctx(layout, state.values)
        val cells = d.items.map { (label, v) -> Triple(label, v, find(v)?.offOn()) }
        val before = cells.mapNotNull { (_, v, oo) -> if (oo != null) v to (state.values[v] ?: ctx.value(v)) else null }.toMap()
        val handler = Handler(Looper.getMainLooper())
        var pending = false
        fun live() {
            if (pending) return
            pending = true
            handler.postDelayed({ pending = false; onChanged() }, 90)
        }
        val cols = if (d.columns > 0) d.columns else 3
        val grid = android.widget.GridLayout(activity).apply {
            columnCount = cols
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        val views = ArrayList<Pair<TextView, String>>()
        fun isOn(v: String): Boolean {
            val oo = find(v)?.offOn() ?: return false
            return (state.values[v] ?: ctx.value(v)) == oo.second
        }
        fun paint() {
            for ((tv, v) in views) {
                val on = isOn(v)
                tv.setTextColor(if (on) Theme.onPink else Theme.muted)
                tv.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val shape = if (on) Theme.on(cut = dp(14).toFloat(), shadow = dp(7).toFloat()) else Theme.off(cut = dp(14).toFloat(), shadow = dp(7).toFloat())
                tv.background = shape
            }
        }
        for ((label, v, oo) in cells) {
            val tv = TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length <= 2) 28f else 12f)
                maxLines = 2
            }
            if (oo != null) {
                views.add(tv to v)
                tv.contentDescription = label
                tv.setOnClickListener {
                    val turnOn = !isOn(v)
                    state.values[v] = if (turnOn) oo.second else oo.first
                    val m = d.master
                    if (turnOn && m != null && m != v) {
                        find(m)?.offOn()?.let { state.values[m] = it.second }
                    }
                    paint()
                    live()
                }
            }
            val lp = android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
            ).apply { width = 0; height = dp(72); setMargins(dp(4), dp(4), dp(4), dp(4)) }
            grid.addView(tv, lp)
        }
        paint()
        val dlg = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(d.desc?.takeIf { it.isNotBlank() } ?: d.name)
            .setView(ScrollView(activity).apply { addView(grid) })
            .setPositiveButton("Done") { _, _ -> onChanged() }
            .setNegativeButton("Cancel") { _, _ ->
                for ((k, v) in before) state.values[k] = v
                onChanged()
            }
            .create()
        dlg.show()
        // Docked at the bottom without dimming, so the card stays visible while arrows change.
        dlg.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        dlg.window?.setGravity(Gravity.BOTTOM)
        style(dlg)
    }


    /** Round gradient hot-pink button with a white gear. */
    private fun gearButton(): View = View(activity).apply {
        background = LayerDrawable(arrayOf(Theme.on(cut = dp(12).toFloat(), shadow = dp(6).toFloat()), GearDrawable()))
        isClickable = true
        contentDescription = "Settings"
    }

    private class GearDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.onPink }
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

    private fun statusBarPad(): Int {
        val r = android.graphics.Rect()
        activity.window.decorView.getWindowVisibleDisplayFrame(r)
        return r.top
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
        openPanelFields(g.desc?.takeIf { it.isNotBlank() } ?: g.name, fields, after)
    }

    /** One number option as a name, a typed value, a reset button and a slider. Returns what resets it. */
    private fun sliderRow(parent: LinearLayout, f: DataDef, startV: Float, onValue: (Float) -> Unit, onDone: () -> Unit, gap: Int = 0): () -> Unit =
        sliderRowFull(parent, f, startV, onValue, onDone, gap).reset

    /** A slider row: [reset] puts the default back; [show] sets the value without reporting it, dimmed when auto. */
    private class SliderRow(val reset: () -> Unit, val show: (Float, Boolean) -> Unit, val auto: () -> Boolean)

    private fun sliderRowFull(parent: LinearLayout, f: DataDef, startV: Float, onValue: (Float) -> Unit, onDone: () -> Unit, gap: Int = 0): SliderRow {
        val (lo, hi) = sliderRange(f.name) ?: (0 to 200)
        var autoNow = false
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, gap, 0, 0) }
        val name = label(f.desc?.takeIf { it.isNotBlank() }?.substringBefore(" (") ?: f.name)
        head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val num = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
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
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        head.addView(num, LinearLayout.LayoutParams(dp(90), dp(40)))
        val one = Button(activity).apply {
            text = "↺"
            setAllCaps(false)
            setTextColor(pink)
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minimumWidth = 0
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(2), hot)
                cornerRadius = dp(10).toFloat()
            }
            contentDescription = "Reset this option"
            // While auto, the value belongs to fit steps: Back to auto fit / editing is the way to change it.
            setOnClickListener { if (!autoNow) num.setText(fmt(f.default?.toFloatOrNull() ?: 100f)) }
        }
        head.addView(one, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(6) })
        parent.addView(head)
        val bar = SeekBar(activity).apply {
            max = (hi - lo) * 10
            progress = Math.round((startV - lo) * 10f).coerceIn(0, max)
            progressTintList = android.content.res.ColorStateList.valueOf(pink)
            thumbTintList = android.content.res.ColorStateList.valueOf(pink)
        }
        var busy = false
        fun apply(v: Float) = onValue(v)
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
                val v = s?.toString()?.toFloatOrNull() ?: return
                busy = true
                bar.progress = Math.round((v - lo) * 10f).coerceIn(0, bar.max)
                busy = false
                apply(v)
            }
        })
        num.setOnEditorActionListener { v, act, _ ->
            if (act == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                (v.text?.toString()?.toFloatOrNull())?.let { apply(it); onDone() }
                v.clearFocus()
                true
            } else false
        }
        parent.addView(bar, row())
        val nameText = name.text.toString()
        val numBg = num.background as GradientDrawable
        val resetBg = one.background as GradientDrawable
        val show: (Float, Boolean) -> Unit = { v, auto ->
            autoNow = auto
            // Do not rewrite the number the user is typing in.
            if (auto || !num.hasFocus()) {
                busy = true
                if (num.text.toString() != fmt(v)) num.setText(fmt(v))
                bar.progress = Math.round((v - lo) * 10f).coerceIn(0, bar.max)
                busy = false
            }
            val ink = if (auto) Theme.muted else pink
            val edge = if (auto) Color.parseColor("#4a4550") else hot
            num.setTextColor(ink)
            numBg.setStroke(dp(2), edge)
            one.setTextColor(if (auto) Color.parseColor("#4a4550") else pink)
            resetBg.setStroke(dp(2), edge)
            name.text = if (auto) "$nameText  · auto" else nameText
            name.setTextColor(if (auto) Theme.muted else Theme.label)
            val tint = android.content.res.ColorStateList.valueOf(ink)
            bar.progressTintList = tint
            bar.thumbTintList = tint
        }
        val reset = {
            val d = (f.default?.toFloatOrNull() ?: 100f)
            num.setText(fmt(d))
        }
        return SliderRow(reset, show, { autoNow })
    }

    private fun openPanelFields(title: String, fields: List<DataDef>, after: () -> Unit) {
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
            val (lo, _) = sliderRange(f.name) ?: (0 to 200)
            val defText = f.default ?: before[f.name] ?: lo.toString()
            val startV = (before[f.name]?.toFloatOrNull() ?: defText.toFloatOrNull() ?: lo.toFloat())
            resetters.add(sliderRow(box, f, startV, { v -> state.values[f.name] = fmt(v); live() }, { handler.removeCallbacksAndMessages(null); pending = false; onChanged() }))
        }

        var finished = false
        val dlg = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(box); isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(24)) })
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
            // Closing any way other than Cancel keeps what was typed or dragged.
            if (!finished) onChanged()
            onTop?.invoke(-1)
            after()
        }
        dlg.show()
        val w = dlg.window
        if (fields.size > 3) w?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.45f).toInt())
        w?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        w?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        w?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        w?.setGravity(Gravity.BOTTOM)
        style(dlg)
        // Fit the card above the panel where it opens (dragging the panel afterwards leaves the card as it is).
        w?.decorView?.post {
            val loc = IntArray(2)
            w.decorView.getLocationOnScreen(loc)
            onTop?.invoke(loc[1])
        }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            setTextColor(Color.parseColor("#e7c3d6"))
            setOnClickListener { resetters.forEach { it() } }
        }
        // Drag the grip bar (or the title) to move the panel anywhere, so the card stays in view.
        var dx = 0f
        var dy = 0f
        val drag = View.OnTouchListener { _, ev ->
            val lp = w?.attributes ?: return@OnTouchListener false
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val loc = IntArray(2)
                    w.decorView.getLocationOnScreen(loc)
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.x = loc[0]
                    lp.y = loc[1] - statusBarPad()
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
        val grip = TextView(activity).apply {
            text = "☰   drag here to move"
            setTextColor(Color.parseColor("#e7c3d6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            setOnTouchListener(drag)
        }
        box.addView(grip, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg.findViewById<TextView>(activity.resources.getIdentifier("alertTitle", "id", "android"))?.setOnTouchListener(drag)
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
            n.startsWith("fontscale") -> 0 to 200
            n.startsWith("tracking") -> -1000 to 1000
            n.startsWith("leadingpx") -> 0 to 100
            n.startsWith("leading") -> 50 to 200
            n.startsWith("hscale") || n.startsWith("vscale") -> 30 to 200
            n.startsWith("baseline") -> -50 to 50
            n.startsWith("wordspace") -> 0 to 300
            n.startsWith("weight") -> 0 to 100
            n.startsWith("justifylast") || n.startsWith("justifyflow") -> 0 to 100
            // Size and paragraph settings in card px (0 on Size = automatic fit).
            n.startsWith("sizepx") -> 0 to 40
            n.startsWith("indentfirst") -> -50 to 100
            n.startsWith("indentleft") || n.startsWith("indentright") -> 0 to 100
            n.startsWith("spacebefore") || n.startsWith("spaceafter") -> 0 to 50
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
        setTextColor(Theme.label)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(8), 0, dp(6))
    }

    /** A field-style button: dark box, gradient edge, cut corner. Text after "·" or "●" is dimmed or accented. */
    private fun hotButton(text: String) = Button(activity).apply {
        val sep = text.indexOf("   ·   ")
        val dot = text.indexOf("   ●  ")
        val spanned = android.text.SpannableString(text)
        if (sep >= 0) {
            spanned.setSpan(android.text.style.ForegroundColorSpan(Theme.muted), sep, text.length, 0)
            spanned.setSpan(android.text.style.TypefaceSpan("sans-serif"), sep, text.length, 0)
        } else if (dot >= 0) {
            spanned.setSpan(android.text.style.ForegroundColorSpan(Theme.pink), dot, text.length, 0)
        }
        this.text = spanned
        setAllCaps(false)
        setTextColor(pink)
        typeface = Typeface.DEFAULT_BOLD
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        stateListAnimator = null
        minHeight = dp(44)
        minimumHeight = dp(44)
        background = Theme.field(dp(12).toFloat(), dp(1).toFloat().coerceAtLeast(1f) * 1.5f, dp(7).toFloat())
        setPadding(dp(14), 0, dp(14), 0)
    }

    /** The dialog frame: see Theme.styleDialog. */
    private fun style(dlg: AlertDialog) = Theme.styleDialog(activity, dlg)
}


/** Lays its children out left to right and wraps to the next line, for rows of option chips. */
class FlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {
    init {
        clipChildren = false
    }

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

/** One of Photoshop's 7 paragraph alignments drawn as four lines (the icon of the alignment bar). */
private class AlignIcon(ctx: Context, private val kind: String) : TextView(ctx) {
    companion object {
        val KINDS = setOf("ragleft", "hcenter", "right", "left", "justcenter", "justright", "justall")
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val d = resources.displayMetrics.density
        val w = 20f * d
        val bh = 2.4f * d
        val gap = 4.6f * d
        val left = (width - w) / 2f
        val top = (height - (3 * gap + bh)) / 2f
        val widths = when (kind) {
            "ragleft", "right" -> floatArrayOf(1f, 0.73f, 0.91f, 0.55f)
            "hcenter" -> floatArrayOf(1f, 0.64f, 0.82f, 0.45f)
            "justall" -> floatArrayOf(1f, 1f, 1f, 1f)
            else -> floatArrayOf(1f, 1f, 1f, 0.55f)
        }
        val anchor = when (kind) { "right", "justright" -> 2; "hcenter", "justcenter" -> 1; else -> 0 }
        p.color = if (currentTextColor == Theme.onPink) Theme.onPink else Theme.text
        for (i in 0..3) {
            val bw = w * widths[i]
            val x = when (anchor) { 2 -> left + w - bw; 1 -> left + (w - bw) / 2f; else -> left }
            val y = top + i * gap
            c.drawRoundRect(x, y, x + bw, y + bh, d, d, p)
        }
    }
}
