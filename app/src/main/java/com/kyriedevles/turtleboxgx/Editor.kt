package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
                    box.addView(et, row())
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
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
        dlg.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        style(dlg)
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
