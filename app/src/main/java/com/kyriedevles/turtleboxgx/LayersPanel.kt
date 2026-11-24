package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

private val PINK = Theme.pink
private val HOT = Theme.hot
private val SOFT = Theme.label
private val DIM = Theme.muted

private fun Activity.dpx(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Activity.tv(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    text = t
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

private fun Activity.pillBtn(t: String, color: Int, fill: Boolean = false, click: () -> Unit) = TextView(this).apply {
    text = t
    gravity = Gravity.CENTER
    setTextColor(if (fill) Theme.onPink else color)
    typeface = Typeface.DEFAULT_BOLD
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    background = if (fill) Theme.on(cut = dpx(10).toFloat(), shadow = dpx(5).toFloat()) else Theme.field(dpx(10).toFloat(), dpx(1).toFloat() * 1.5f, dpx(5).toFloat())
    setOnClickListener { click() }
}

/** The "Image layers" panel: add, reorder (by index), show/hide, opacity, replace, duplicate, delete, and "Edit on card". */
class LayersPanel(
    private val activity: Activity,
    private val layout: Layout,
    private val st: CardState,
    private val onChanged: () -> Unit,
    private val pickImage: (String?) -> Unit,
    private val startEdit: (Layer, List<Layer>) -> Unit,
) {
    private var dlg: Dialog? = null
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
    private val open = HashSet<String>()

    private fun layers() = ImageLayers.read(st.values)

    private fun save(l: List<Layer>) {
        ImageLayers.write(st.values, l)
        onChanged()
    }

    private fun thumb(l: Layer): View {
        val box = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = CutDrawable(intArrayOf(Theme.plane3), cutTR = activity.dpx(8).toFloat())
            setPadding(activity.dpx(3), activity.dpx(3), activity.dpx(3), activity.dpx(3))
        }
        st.files[l.file]?.let { b ->
            try {
                val (bw, bh) = ImageLayers.size(b)
                var s = 1
                while (maxOf(bw, bh) / (s * 2) >= 128) s *= 2
                box.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s }))
            } catch (e: Throwable) {
            }
        }
        return box
    }

    private fun elementRows(): List<Pair<String, Float>> {
        val seen = LinkedHashSet<Pair<String, Float>>()
        val ctx = Ctx(layout, st.values)
        for (e in layout.elements) {
            val vis = e.visible
            if (vis != null && !try { Expr.eval(vis, ctx) } catch (x: Exception) { true }) continue
            val name = e.label ?: e.attrs["id"]?.takeIf { it.isNotBlank() } ?: e.kind
            seen.add(name to e.index)
        }
        return seen.toList()
    }

    fun refresh() {
        if (dlg == null) return
        list.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        list.removeAllViews()
        list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val ls = layers()
        // Rows: layers and the layout's own elements, front (highest index) first.
        class Row(val z: Float, val layer: Layer?, val name: String)
        val rows = ArrayList<Row>()
        ls.forEach { rows.add(Row(it.z, it, it.name)) }
        elementRows().forEach { rows.add(Row(it.second, null, it.first)) }
        rows.sortWith(compareByDescending<Row> { it.z }.thenBy { it.layer == null })
        list.addView(activity.tv("FRONT", 11f, Color.parseColor("#b98aa5"), true))
        for (r in rows) {
            val lay = r.layer
            if (lay == null) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(activity.dpx(12), activity.dpx(7), activity.dpx(12), activity.dpx(7))
                    background = CutDrawable(intArrayOf(Theme.plane2), cutTR = activity.dpx(12).toFloat())
                }
                row.addView(activity.tv(r.name, 13f, DIM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(activity.tv("index " + fmt(r.z), 12f, DIM, true))
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(6) })
            } else {
                list.addView(layerRow(lay, ls), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(6) })
            }
        }
        list.addView(activity.tv("BACK", 11f, Color.parseColor("#b98aa5"), true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(8) })
        list.addView(
            activity.tv("Dashed rows are the layout's own elements that are showing on this card, with their index from the XML. Give a layer a number to put it in front of or behind them. Higher is in front.", 11f, DIM),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(4) },
        )
    }

    private fun fmt(v: Float) = if (v == Math.rint(v.toDouble()).toFloat()) v.toInt().toString() else String.format(java.util.Locale.US, "%.2f", v)

    private fun layerRow(l: Layer, all: List<Layer>): View {
        val isOpen = open.contains(l.id)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dpx(10), activity.dpx(8), activity.dpx(10), activity.dpx(8))
            background = Theme.card(activity.dpx(14).toFloat(), activity.dpx(4).toFloat(), activity.dpx(3).toFloat(), isOpen)
        }
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(activity.tv(if (isOpen) "▾" else "▸", 18f, PINK, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { if (isOpen) open.remove(l.id) else open.add(l.id); refresh() }
        }, LinearLayout.LayoutParams(activity.dpx(28), activity.dpx(40)))
        head.addView(thumb(l), LinearLayout.LayoutParams(activity.dpx(44), activity.dpx(44)))
        val mid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(activity.dpx(10), 0, activity.dpx(6), 0) }
        mid.addView(activity.tv(l.name, 14f, Color.WHITE, true).apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END })
        mid.addView(activity.tv(if (l.vis) "Visible · ${(l.opacity * 100).toInt()}%" else "Hidden", 11f, DIM))
        head.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val zBox = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            setText(fmt(l.z))
            setTextColor(PINK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minimumHeight = 0
            minHeight = 0
            includeFontPadding = false
            setPadding(activity.dpx(4), 0, activity.dpx(4), 0)
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = Theme.field(activity.dpx(8).toFloat(), activity.dpx(1).toFloat() * 1.5f)
            fun apply() {
                val v = text.toString().toFloatOrNull() ?: return
                if (v != l.z) {
                    val cur = layers()
                    cur.firstOrNull { it.id == l.id }?.z = v
                    save(cur)
                    list.post { refresh() }
                }
            }
            setOnEditorActionListener { v, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { apply(); v.clearFocus(); true } else false }
            setOnFocusChangeListener { _, f -> if (!f) apply() }
        }
        head.addView(zBox, LinearLayout.LayoutParams(activity.dpx(58), activity.dpx(34)))
        head.addView(activity.tv(if (l.vis) "●" else "○", 20f, if (l.vis) PINK else Color.parseColor("#6f4a5f"), true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                val cur = layers()
                cur.firstOrNull { it.id == l.id }?.let { it.vis = !it.vis }
                save(cur)
                refresh()
            }
        }, LinearLayout.LayoutParams(activity.dpx(36), activity.dpx(40)))
        card.addView(head)
        if (isOpen) {
            fun mut(f: (Layer) -> Unit) {
                val cur = layers()
                cur.firstOrNull { it.id == l.id }?.let(f)
                save(cur)
            }
            val step = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            step.addView(activity.tv("Index (higher = in front)", 12f, SOFT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            step.addView(activity.pillBtn("−", PINK) { mut { it.z -= 1f }; refresh() }, LinearLayout.LayoutParams(activity.dpx(40), activity.dpx(34)))
            step.addView(activity.pillBtn("+", PINK) { mut { it.z += 1f }; refresh() }, LinearLayout.LayoutParams(activity.dpx(40), activity.dpx(34)).apply { leftMargin = activity.dpx(8) })
            card.addView(step, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(10) })
            val opHead = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            opHead.addView(activity.tv("Opacity", 12f, SOFT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val opVal = activity.tv("${(l.opacity * 100).toInt()}%", 12f, PINK, true)
            opHead.addView(opVal)
            card.addView(opHead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(8) })
            val sb = SeekBar(activity).apply {
                max = 100
                progress = (l.opacity * 100).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(HOT)
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) opVal.text = "$p%"
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        mut { it.opacity = (s?.progress ?: 100) / 100f }
                        refresh()
                    }
                })
            }
            card.addView(sb)
            val r1 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            r1.addView(activity.pillBtn("Edit on card", PINK, true) { dlg?.dismiss(); dlg = null; startEdit(l, layers()) }, LinearLayout.LayoutParams(0, activity.dpx(38), 1f).apply { rightMargin = activity.dpx(4) })
            r1.addView(activity.pillBtn("Replace", PINK) { pickImage(l.id) }, LinearLayout.LayoutParams(0, activity.dpx(38), 1f).apply { leftMargin = activity.dpx(4) })
            card.addView(r1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(10) })
            val r2 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            r2.addView(activity.pillBtn("Rename", PINK) { rename(l) }, LinearLayout.LayoutParams(0, activity.dpx(38), 1f).apply { rightMargin = activity.dpx(4) })
            r2.addView(activity.pillBtn("Duplicate", PINK) { duplicate(l) }, LinearLayout.LayoutParams(0, activity.dpx(38), 1f).apply { leftMargin = activity.dpx(4); rightMargin = activity.dpx(4) })
            r2.addView(activity.pillBtn("Delete", Color.parseColor("#ff4d6d")) { delete(l) }, LinearLayout.LayoutParams(0, activity.dpx(38), 1f).apply { leftMargin = activity.dpx(4) })
            card.addView(r2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(8) })
        }
        return card
    }

    private fun rename(l: Layer) {
        val input = EditText(activity).apply { setSingleLine(true); setTextColor(Color.WHITE); setText(l.name); setSelection(l.name.length) }
        ThemedBuilder(activity).setTitle("Rename layer").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    val cur = layers()
                    cur.firstOrNull { it.id == l.id }?.name = n
                    save(cur)
                    refresh()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun duplicate(l: Layer) {
        val cur = layers()
        val src = cur.firstOrNull { it.id == l.id } ?: return
        val c = src.copy()
        c.id = ImageLayers.newId()
        c.name = src.name + " copy"
        c.z = src.z + 1f
        st.files[c.file] = st.files[src.file] ?: return
        st.files[src.origFile]?.let { st.files[c.origFile] = it }
        cur.add(c)
        open.add(c.id)
        save(cur)
        refresh()
    }

    private fun delete(l: Layer) {
        ThemedBuilder(activity).setTitle("Delete “${l.name}”?")
            .setPositiveButton("Delete") { _, _ ->
                val cur = layers()
                cur.removeAll { it.id == l.id }
                st.files.remove(l.file)
                st.files.remove(l.origFile)
                save(cur)
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    fun show() {
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg = d
        val title = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(activity.tv("Image layers", 16f, HOT, true).apply { isAllCaps = true; letterSpacing = 0.12f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        title.addView(activity.tv("DONE", 13f, PINK, true).apply { setPadding(activity.dpx(10), activity.dpx(8), activity.dpx(10), activity.dpx(8)); setOnClickListener { d.dismiss() } })
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dpx(14), activity.dpx(12), activity.dpx(14), activity.dpx(12))
            background = Theme.dialog(activity.dpx(22).toFloat(), activity.dpx(4).toFloat())
        }
        root.addView(title)
        root.addView(activity.pillBtn("+  Add image layer", PINK, true) { pickImage(null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dpx(40)).apply { topMargin = activity.dpx(8); bottomMargin = activity.dpx(10) })
        root.addView(ScrollView(activity).apply { clipChildren = false; clipToPadding = false; setPadding((10 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(), (10 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt()); addView(list); clipChildren = false }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        d.setContentView(root)
        d.setOnDismissListener { if (dlg === d) dlg = null }
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val dm = activity.resources.displayMetrics
            setLayout((dm.widthPixels * 0.96f).toInt(), (dm.heightPixels * 0.72f).toInt())
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        }
        refresh()
        d.show()
    }
}

/** The small floating window shown while a layer is edited on the card. The card itself takes the touches. */
class LayerEditWindow(
    private val activity: Activity,
    private val editor: LayerEditor,
    private val title: String,
    private val save: () -> Unit,
    private val onTop: (Int) -> Unit,
    private val onDone: () -> Unit,
    /** The eraser's working picture and the original, same size; null if they can't be opened. */
    private val eraserSource: () -> Pair<android.graphics.Bitmap, android.graphics.Bitmap>? = { null },
    private val saveBitmap: (android.graphics.Bitmap) -> Unit = {},
) {
    private val undo = ArrayList<Layer>()
    private var dlg: Dialog? = null

    private fun snapshot() {
        undo.add(editor.layer.copy())
        if (undo.size > 40) undo.removeAt(0)
    }

    private fun apply(f: (Layer) -> Unit) {
        snapshot()
        f(editor.layer)
        editor.changed()
        save()
    }

    fun show() {
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg = d
        val l = editor.layer
        val readout = activity.tv("", 11f, SOFT)
        fun upd() { readout.text = "X ${l.cx.toInt()}   Y ${l.cy.toInt()}   W ${l.w.toInt()}   H ${l.h.toInt()}   ∠ ${l.rot.toInt()}°" }
        editor.onBegin = { snapshot() }
        editor.onCommit = { save() }
        editor.onChange = { upd() }
        fun sw(name: String, on: Boolean, ch: (Boolean) -> Unit) = Switch(activity).apply {
            text = name
            setTextColor(SOFT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isChecked = on
            val checked = intArrayOf(android.R.attr.state_checked)
            thumbTintList = android.content.res.ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(Color.WHITE, Color.parseColor("#c98aae")))
            trackTintList = android.content.res.ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(HOT, Color.parseColor("#4a1a35")))
            setOnCheckedChangeListener { _, v -> ch(v) }
        }
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(activity.tv(title, 13f, HOT, true).apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(sw("Grid", editor.grid) { editor.grid = it; editor.changed() })
        head.addView(sw("Lock", editor.lock) { editor.lock = it }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dpx(10) })
        fun small(t: String, click: () -> Unit) = activity.pillBtn(t, PINK, false, click).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        val b1 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val b2 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(row: LinearLayout, t: String, click: () -> Unit) = row.addView(small(t, click), LinearLayout.LayoutParams(0, activity.dpx(36), 1f).apply { setMargins(activity.dpx(2), 0, activity.dpx(2), 0) })
        add(b1, "−5°") { apply { it.rot -= 5f } }
        add(b1, "+5°") { apply { it.rot += 5f } }
        add(b1, "Mirror") { apply { it.mx = !it.mx } }
        add(b1, "Flip") { apply { it.fy = !it.fy } }
        add(b2, "Fit") { apply { ImageLayers.fit(it, editor.bmp.width, editor.bmp.height, editor.cardW, editor.cardH) } }
        add(b2, "Fill") { apply { it.w = editor.cardW; it.h = editor.cardH; it.cx = editor.cardW / 2f; it.cy = editor.cardH / 2f; it.rot = 0f } }
        add(b2, "Reset") { apply { ImageLayers.fit(it, editor.bmp.width, editor.bmp.height, editor.cardW, editor.cardH); it.mx = false; it.fy = false } }
        add(b2, "Undo") {
            if (undo.isNotEmpty()) {
                val u = undo.removeAt(undo.size - 1)
                l.cx = u.cx; l.cy = u.cy; l.w = u.w; l.h = u.h; l.rot = u.rot; l.mx = u.mx; l.fy = u.fy
                editor.changed()
                save()
            }
        }
        // Eraser tools (shown only while the eraser is on).
        fun paintTog(v: TextView, on: Boolean) {
            v.setTextColor(if (on) Theme.onPink else PINK)
            v.background = if (on) Theme.on(cut = activity.dpx(10).toFloat()) else Theme.field(activity.dpx(10).toFloat(), activity.dpx(1).toFloat() * 1.5f)
        }
        fun slider(name: String, max: Int, start: Int, fmt: (Int) -> String, change: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val lab = activity.tv("$name ${fmt(start)}", 11f, SOFT)
            val sb = android.widget.SeekBar(activity).apply {
                this.max = max
                progress = start
                progressTintList = android.content.res.ColorStateList.valueOf(HOT)
                thumbTintList = android.content.res.ColorStateList.valueOf(PINK)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                        lab.text = "$name ${fmt(p)}"
                        change(p)
                    }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
                })
            }
            row.addView(lab, LinearLayout.LayoutParams(activity.dpx(100), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }
        val er = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val erRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val bErase = small("Erase") { }
        val bRestore = small("Restore") { }
        val bUndo = small("Undo") { editor.undoErase() }
        val bRedo = small("Redo") { editor.redoErase() }
        fun modeUi() { paintTog(bErase, !editor.restore); paintTog(bRestore, editor.restore) }
        bErase.setOnClickListener { editor.restore = false; modeUi() }
        bRestore.setOnClickListener { editor.restore = true; modeUi() }
        modeUi()
        for (b in listOf(bErase, bRestore, bUndo, bRedo)) erRow.addView(b, LinearLayout.LayoutParams(0, activity.dpx(36), 1f).apply { setMargins(activity.dpx(2), 0, activity.dpx(2), 0) })
        er.addView(erRow)
        er.addView(slider("Size", 100, ((editor.brush - 4f) / 1.56f).toInt().coerceIn(0, 100), { "${(4f + it * 1.56f).toInt()}" }) { editor.brush = 4f + it * 1.56f; editor.changed() })
        er.addView(slider("Hardness", 100, editor.hardness.toInt(), { "$it" }) { editor.hardness = it.toFloat() })
        er.addView(sw("Brush above finger", editor.aboveFinger) { editor.aboveFinger = it })
        val eb = small("Eraser") { }
        lateinit var rootRef: View
        fun setEraser(on: Boolean) {
            if (on) {
                if (!editor.hasWork) {
                    val src = eraserSource() ?: return
                    editor.startErasing(src.first, src.second)
                }
                editor.eraser = true
            } else {
                editor.eraser = false
                if (editor.dirty) {
                    saveBitmap(editor.bmp)
                    editor.dirty = false
                }
            }
            b1.visibility = if (on) View.GONE else View.VISIBLE
            b2.visibility = if (on) View.GONE else View.VISIBLE
            er.visibility = if (on) View.VISIBLE else View.GONE
            paintTog(eb, on)
            editor.changed()
            rootRef.postDelayed({
                val loc = IntArray(2)
                rootRef.getLocationOnScreen(loc)
                onTop(loc[1])
            }, 150)
        }
        eb.setOnClickListener { setEraser(!editor.eraser) }
        val foot = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        foot.addView(readout, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        foot.addView(eb, LinearLayout.LayoutParams(activity.dpx(76), activity.dpx(32)).apply { rightMargin = activity.dpx(8) })
        foot.addView(
            activity.pillBtn("Done", PINK, true) {
                if (editor.dirty) { saveBitmap(editor.bmp); editor.dirty = false }
                d.dismiss()
                onDone()
            },
            LinearLayout.LayoutParams(activity.dpx(70), activity.dpx(32)),
        )
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dpx(10), activity.dpx(8), activity.dpx(10), activity.dpx(10))
            background = Theme.dialog(activity.dpx(16).toFloat(), activity.dpx(3).toFloat())
        }
        root.addView(head)
        root.addView(b1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(6) })
        root.addView(b2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(4) })
        root.addView(er, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(6) })
        root.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(6) })
        rootRef = root
        d.setContentView(root)
        upd()
        d.setCancelable(false)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            val dm = activity.resources.displayMetrics
            setLayout((dm.widthPixels * 0.96f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        }
        d.show()
        root.post {
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            onTop(loc[1])
        }
    }

    fun close() {
        dlg?.dismiss()
        dlg = null
    }
}
