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

private val PINK = Color.parseColor("#ff6ec7")
private val HOT = Color.parseColor("#ff1493")
private val SOFT = Color.parseColor("#e7c3d6")
private val DIM = Color.parseColor("#c9a6b9")

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
    setTextColor(if (fill) Color.BLACK else color)
    typeface = Typeface.DEFAULT_BOLD
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    background = if (fill) {
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(PINK, HOT)).apply { cornerRadius = dpx(10).toFloat() }
    } else {
        GradientDrawable().apply { setColor(Color.BLACK); setStroke(dpx(2), color); cornerRadius = dpx(10).toFloat() }
    }
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
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val open = HashSet<String>()

    private fun layers() = ImageLayers.read(st.values)

    private fun save(l: List<Layer>) {
        ImageLayers.write(st.values, l)
        onChanged()
    }

    private fun thumb(l: Layer): View {
        val box = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#2a2a2e")); cornerRadius = activity.dpx(8).toFloat() }
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
            val click = e.attrs["click"]
            val menu = click?.let { layout.datas[it]?.desc?.takeIf { d -> d.isNotBlank() } ?: it }
            val what = if (e.kind == "image") {
                e.srcs.firstOrNull()?.value?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            } else {
                "text" + (e.text.replace(Regex("\\s+"), " ").trim().take(14).takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            }
            val name = e.label ?: listOfNotNull(menu, what).distinct().joinToString(" · ").ifBlank { e.attrs["id"] ?: e.kind }
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
                    background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(activity.dpx(1), Color.parseColor("#3a1a2c")); cornerRadius = activity.dpx(12).toFloat() }
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
            background = GradientDrawable().apply { setColor(Color.parseColor("#0f0a14")); setStroke(activity.dpx(2), if (isOpen) HOT else Color.parseColor("#2c1226")); cornerRadius = activity.dpx(14).toFloat() }
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
            background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(activity.dpx(2), HOT); cornerRadius = activity.dpx(9).toFloat() }
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
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert).setTitle("Rename layer").setView(input)
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
        cur.add(c)
        open.add(c.id)
        save(cur)
        refresh()
    }

    private fun delete(l: Layer) {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert).setTitle("Delete “${l.name}”?")
            .setPositiveButton("Delete") { _, _ ->
                val cur = layers()
                cur.removeAll { it.id == l.id }
                st.files.remove(l.file)
                save(cur)
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    fun show() {
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg = d
        val title = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(activity.tv("Image layers", 16f, Color.parseColor("#ff4da6"), true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        title.addView(activity.tv("DONE", 13f, PINK, true).apply { setPadding(activity.dpx(10), activity.dpx(8), activity.dpx(10), activity.dpx(8)); setOnClickListener { d.dismiss() } })
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dpx(14), activity.dpx(12), activity.dpx(14), activity.dpx(12))
            background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(activity.dpx(2), HOT); cornerRadius = activity.dpx(18).toFloat() }
        }
        root.addView(title)
        root.addView(activity.pillBtn("+  Add image layer", PINK, true) { pickImage(null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dpx(40)).apply { topMargin = activity.dpx(8); bottomMargin = activity.dpx(10) })
        root.addView(ScrollView(activity).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
        head.addView(activity.tv(title, 13f, Color.parseColor("#ff4da6"), true).apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        val foot = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        foot.addView(readout, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        foot.addView(activity.pillBtn("Done", PINK, true) { d.dismiss(); onDone() }, LinearLayout.LayoutParams(activity.dpx(70), activity.dpx(32)))
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dpx(10), activity.dpx(8), activity.dpx(10), activity.dpx(10))
            background = GradientDrawable().apply { setColor(Color.parseColor("#f00a0510")); setStroke(activity.dpx(2), HOT); cornerRadius = activity.dpx(16).toFloat() }
        }
        root.addView(head)
        root.addView(b1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(6) })
        root.addView(b2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(4) })
        root.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpx(6) })
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
