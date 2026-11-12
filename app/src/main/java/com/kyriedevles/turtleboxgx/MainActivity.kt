package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlin.concurrent.thread

/**
 * The app shell from the mockup: a title bar with icons, the card with a row of edit chips, and a bottom bar
 * with three pages (Card, Layouts, Sets). The Layouts page shows which files are in use and reloads them
 * on its own when you save the XML.
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("gx", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var cardView: CardView
    private lateinit var statusLine: TextView
    private lateinit var layoutsBox: LinearLayout
    private lateinit var setsBox: LinearLayout
    private lateinit var pages: List<View>
    private lateinit var tabIcons: List<HotIcon>
    private lateinit var tabLabels: List<TextView>
    private var tab = 0

    private var layout: Layout? = null
    private var zipFile: File? = null
    private var state: CardState? = null
    private var hits: List<Hit> = emptyList()
    private var pendingImageKey: String? = null
    private var lastBmp: Bitmap? = null
    private var fullStatus = ""

    // What the Layouts page shows.
    private var folderUri: Uri? = null
    private var folderName = ""
    private var xmlUri: Uri? = null
    private var xmlName = ""
    private var xmlModified = 0L
    private var xmlLoadedAt = 0L
    private var xmlNote = ""
    private var xmlError: ParseInfo? = null
    private var zipName = ""
    private var zipInfo = ""
    private var zipOk = false
    private var scanning = false

    private class ParseInfo(val line: Int, val message: String, val context: String)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pill(text: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        if (filled) {
            setTextColor(Color.BLACK)
            background = Hot.gradient(dp(20).toFloat())
        } else {
            setTextColor(Hot.pinkLight)
            background = Hot.outline(dp(20).toFloat(), dp(2), Color.parseColor("#5a2a43"))
        }
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(buildTopBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        root.addView(View(this).apply { background = Hot.gradient(0f, horizontal = true) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val content = FrameLayout(this)
        val cardPage = buildCardPage()
        layoutsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val layoutsPage = ScrollView(this).apply { addView(layoutsBox) }
        val setsPage = ScrollView(this).apply { addView(setsBox) }
        content.addView(cardPage)
        content.addView(layoutsPage)
        content.addView(setsPage)
        pages = listOf(cardPage, layoutsPage, setsPage)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#2a1220")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        root.addView(buildBottomBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        setContentView(root)

        showTab(0)
        refreshLayoutsPage()
        refreshSetsPage()

        val saved = prefs.getString("tree", null)
        if (saved != null) {
            val uri = Uri.parse(saved)
            val stillAllowed = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (stillAllowed) scan(uri)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(poll, 2500)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    /** Every few seconds: did the XML change? Reload it. Otherwise just refresh the "edited … ago" text. */
    private val poll = object : Runnable {
        override fun run() {
            val x = xmlUri
            val f = folderUri
            if (x != null && f != null && !scanning) {
                val modified = try {
                    DocumentFile.fromSingleUri(this@MainActivity, x)?.lastModified() ?: 0L
                } catch (e: Exception) {
                    0L
                }
                if (modified != 0L && modified != xmlModified) scan(f) else if (tab == 1) refreshLayoutsPage()
            }
            handler.postDelayed(this, 2500)
        }
    }

    // ---- shell ----

    private fun buildTopBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), 0, dp(8), 0)
        addView(label("TurtleBox", 20f, Hot.pink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun icon(kind: String, desc: String, action: (View) -> Unit) {
            val v = HotIcon(this@MainActivity, kind).apply {
                contentDescription = desc
                setOnClickListener { action(it) }
            }
            addView(v, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(2), 0, 0, 0) })
        }
        icon("save", "Save card set") { saveSet() }
        icon("eye", "Preview") { showPreview() }
        icon("image", "Save card image") { saveImage() }
        icon("more", "More") { showMore(it) }
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val icons = ArrayList<HotIcon>()
        val labels = ArrayList<TextView>()
        listOf("card" to "Card", "layouts" to "Layouts", "sets" to "Sets").forEachIndexed { i, (kind, name) ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { showTab(i) }
            }
            val ic = HotIcon(this, kind)
            val tx = label(name, 12f, Hot.inactive).apply { gravity = Gravity.CENTER; includeFontPadding = false }
            col.addView(ic, LinearLayout.LayoutParams(dp(22), dp(22)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(2) })
            col.addView(tx, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            icons.add(ic)
            labels.add(tx)
            bar.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        tabIcons = icons
        tabLabels = labels
        return bar
    }

    private fun showTab(i: Int) {
        tab = i
        pages.forEachIndexed { n, p -> p.visibility = if (n == i) View.VISIBLE else View.GONE }
        tabIcons.forEachIndexed { n, ic -> ic.gradient = n == i }
        tabLabels.forEachIndexed { n, t ->
            t.setTextColor(if (n == i) Hot.pinkLight else Hot.inactive)
            t.typeface = if (n == i) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        if (i == 1) refreshLayoutsPage()
        if (i == 2) refreshSetsPage()
    }

    private fun showMore(anchor: View) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Hot.outline(dp(14).toFloat(), dp(2), Hot.pinkDeep)
            setPadding(0, dp(6), 0, dp(6))
        }
        val pop = PopupWindow(box, dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        fun item(text: String, action: () -> Unit) {
            box.addView(
                label(text, 15f, Color.WHITE).apply {
                    setPadding(dp(18), 0, dp(18), 0)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener {
                        pop.dismiss()
                        action()
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)),
            )
        }
        item("Open card set") { pickSet() }
        item("Draw default card") { if (layout == null) toast("Choose a layout folder first.") else renderCard(null) }
        item("Choose layout folder") { pickFolder() }
        item("Version ${versionName()}") { }
        pop.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pop.elevation = dp(8).toFloat()
        pop.showAsDropDown(anchor, -dp(176), 0)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- Card page ----

    private fun buildCardPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        cardView = CardView(this).apply {
            hint = "Open the Layouts tab and choose\nthe folder with your layout files."
            onTapCard = { fx, fy -> onTap(fx, fy) }
        }
        page.addView(cardView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        statusLine = label("", 11f, Hot.dim).apply {
            setPadding(dp(16), dp(6), dp(16), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { showDetails() }
        }
        page.addView(statusLine)

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(6))
        }
        listOf("Effect text" to "cardtextmenu", "Name" to "cardname", "Stats" to "atkdef", "Frame" to "cardbg").forEach { (name, key) ->
            val chip = TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                setTextColor(Hot.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                background = Hot.outline(dp(12).toFloat(), dp(2), Hot.line)
                setOnClickListener { openEditor(key) }
            }
            chips.addView(chip, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        page.addView(chips)
        return page
    }

    private fun setStatus(s: String) {
        fullStatus = s
        statusLine.text = s.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
    }

    private fun showDetails() {
        val tv = label(fullStatus.ifBlank { "Nothing yet." }, 13f, Color.WHITE).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Details").setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null).show()
    }

    private fun showPreview() {
        val b = lastBmp ?: return toast("Nothing to preview yet.")
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.setContentView(
            ImageView(this).apply {
                setBackgroundColor(Color.BLACK)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(b)
                setOnClickListener { d.dismiss() }
            },
        )
        d.show()
    }

    private fun openEditor(click: String) {
        val l = layout
        val st = state
        if (l == null || st == null) {
            toast("Choose a layout folder first.")
            return
        }
        Editor(
            this, l, st,
            onChanged = { renderState(st) },
            pickImage = { key ->
                pendingImageKey = key
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
                    REQ_IMAGE,
                )
            },
        ).open(click)
    }

    /** A tap on the card, as fractions of its width and height: open the topmost tappable element. */
    private fun onTap(fx: Float, fy: Float) {
        val l = layout ?: return
        val hit = hits.lastOrNull { it.rect.contains(fx * l.width, fy * l.height) } ?: return
        openEditor(hit.click)
    }

    // ---- Layouts page ----

    private fun ago(ms: Long): String {
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 5 -> "just now"
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }

    private fun refreshLayoutsPage() {
        val box = layoutsBox
        box.removeAllViews()
        fun add(v: View, topDp: Int = 0) = box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(topDp) })

        val folder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Hot.outline(dp(16).toFloat(), dp(2), Hot.pinkDeep)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        folder.addView(label("Folder", 13f, Hot.muted))
        folder.addView(label(if (folderName.isBlank()) "None chosen yet" else folderName, 15f, Color.WHITE).apply { setPadding(0, dp(2), 0, 0) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(pill("Open folder", true) { pickFolder() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        buttons.addView(pill("Reload", false) { folderUri?.let { scan(it) } ?: toast("Choose a folder first.") }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { leftMargin = dp(8) })
        folder.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        add(folder)

        add(label("Files in use", 13f, Hot.muted), 18)
        if (xmlName.isNotBlank() || xmlError != null) {
            val sub = when {
                xmlError != null -> "Has a problem. The last good layout is still showing."
                else -> "Edited ${ago(xmlModified)} · loaded ${ago(xmlLoadedAt)}" + if (xmlNote.isNotBlank()) " · $xmlNote" else ""
            }
            add(fileRow("XML", xmlName, sub, xmlError == null), 8)
        }
        if (zipName.isNotBlank()) add(fileRow("ZIP", zipName, zipInfo, zipOk), 8)
        if (xmlName.isBlank() && zipName.isBlank() && xmlError == null) {
            add(label("No files yet. Open the folder that holds your layout .xml and .zip.", 14f, Hot.dim), 8)
        }

        xmlError?.let { e ->
            add(label("If something is wrong, the app tells you where", 13f, Hot.muted), 18)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Hot.outline(dp(14).toFloat(), dp(2), Color.parseColor("#ff4d6d"), Color.parseColor("#1a0509"))
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(label("Line ${e.line} · ${e.message}", 14f, Hot.bad, bold = true))
            card.addView(label("The last good layout is still showing. Fix the line and save; the card updates on its own.", 13f, Hot.muted).apply { setPadding(0, dp(4), 0, 0) })
            card.addView(
                label(e.context, 12f, Hot.muted).apply {
                    typeface = Typeface.MONOSPACE
                    setBackgroundColor(Color.BLACK)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) },
            )
            add(card, 8)
        }

        add(label("Edit the XML with any text editor on your phone. Add a <data> option, a dropdown or a text box and it appears in the menu, with no rebuild and no install. The app checks the file every few seconds and reloads it when it changes.", 13f, Hot.dim), 18)
    }

    private fun fileRow(badge: String, title: String, sub: String, ok: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Hot.outline(dp(14).toFloat(), dp(2), if (ok) Hot.line else Color.parseColor("#ff4d6d"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        row.addView(
            label(badge, 12f, Hot.pinkLight, bold = true).apply {
                gravity = Gravity.CENTER
                background = Hot.outline(dp(10).toFloat(), 0, Color.TRANSPARENT, Color.parseColor("#2a0a1c"))
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(label(title, 15f, Color.WHITE, bold = true))
        mid.addView(label(sub, 12f, Hot.dim))
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(12), 0, dp(8), 0) })
        row.addView(
            label(if (ok) "OK" else "Error", 12f, Color.BLACK, bold = true).apply {
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = Hot.outline(dp(10).toFloat(), 0, Color.TRANSPARENT, if (ok) Hot.ok else Hot.bad)
            },
        )
        return row
    }

    // ---- Sets page ----

    private fun recents(): List<Pair<String, String>> =
        (prefs.getString("recents", "") ?: "").lines().filter { it.contains('\t') }.map { it.substringBefore('\t') to it.substringAfter('\t') }

    private fun addRecent(uri: Uri, name: String) {
        val list = (listOf(uri.toString() to name) + recents().filter { it.first != uri.toString() }).take(8)
        prefs.edit().putString("recents", list.joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    } ?: "card set"

    private fun refreshSetsPage() {
        val box = setsBox
        box.removeAllViews()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Hot.outline(dp(16).toFloat(), dp(2), Hot.pinkDeep)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        head.addView(label("Current card", 13f, Hot.muted))
        head.addView(label(state?.values?.get("cardname") ?: "Nothing open", 17f, Color.WHITE, bold = true).apply { setPadding(0, dp(2), 0, 0) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill("Open set", true) { pickSet() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        row.addView(pill("Save set", false) { saveSet() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { leftMargin = dp(8) })
        row.addView(pill("New card", false) { if (layout == null) toast("Choose a layout folder first.") else renderCard(null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { leftMargin = dp(8) })
        head.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        box.addView(head)

        box.addView(label("Recent sets", 13f, Hot.muted), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        val list = recents()
        if (list.isEmpty()) {
            box.addView(label("Sets you open or save appear here.", 14f, Hot.dim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        list.forEach { (u, name) ->
            val r = label(name.removeSuffix(".msoe-set"), 15f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Hot.outline(dp(14).toFloat(), dp(2), Hot.line)
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { loadSet(Uri.parse(u)) }
            }
            box.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })
        }
    }

    // ---- files ----

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        startActivityForResult(intent, REQ_TREE)
    }

    private fun cardFileName(): String =
        (state?.values?.get("cardname") ?: "card").replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "card" }

    private fun saveSet() {
        if (state == null) return toast("No card to save yet.")
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "${cardFileName()}.msoe-set")
        startActivityForResult(intent, REQ_SAVE)
    }

    private fun saveImage() {
        if (lastBmp == null) return toast("No card to save yet.")
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/png").putExtra(Intent.EXTRA_TITLE, "${cardFileName()}.png")
        startActivityForResult(intent, REQ_PNG)
    }

    /** Artwork can be huge; keep it at most 2000 px on its long side and store it as PNG. */
    /** Width / height of the box this image option is drawn in right now, or null when the layout has none. */
    private fun artAspect(l: Layout, st: CardState, key: String): Float? {
        val ctx = Ctx(l, st.values)
        for (e in l.elements) {
            if (e.kind != "image" || e.attrs["click"] != key) continue
            for (c in e.sizes) {
                val w = c.whenExpr
                if (w == null || Expr.eval(w, ctx)) {
                    val r = c.value
                    if (r.width > 0f && r.height > 0f) return r.width / r.height
                }
            }
        }
        return null
    }

    private fun shrinkToPng(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("That file is not an image.")
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun pickSet() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, REQ_SET)
    }

    private fun loadSet(uri: Uri) {
        if (layout == null) return toast("Choose a layout folder first.")
        setStatus("Reading card set…")
        showTab(0)
        thread {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val set = CardSet.read(bytes)
                runOnUiThread { addRecent(uri, displayName(uri)) }
                renderCard(set)
            } catch (e: Exception) {
                runOnUiThread { setStatus("Could not read that card set: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    @Deprecated("Fine for this small activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_TREE -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // Some providers refuse persistence; the scan below still works this session.
                }
                prefs.edit().putString("tree", uri.toString()).apply()
                scan(uri)
            }
            REQ_IMAGE -> {
                val key = pendingImageKey ?: return
                val l = layout ?: return
                val st = state ?: return
                try {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val def = l.datas[key]?.default ?: ""
                    var fileName = Ctx(l, st.values).sub(def).ifBlank { "$key.png" }
                    if (!fileName.contains('.')) fileName += ".png"
                    fun store(png: ByteArray) {
                        st.files[fileName] = png
                        st.values[key] = fileName
                        renderState(st)
                    }
                    val aspect = artAspect(l, st, key)
                    if (aspect == null) {
                        store(shrinkToPng(bytes))
                    } else {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                            ?: throw IllegalArgumentException("That file is not an image.")
                        Cropper.show(this, bmp, aspect, onCrop = { cropped ->
                            val out = java.io.ByteArrayOutputStream()
                            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                            store(out.toByteArray())
                        }, onWhole = {
                            val out = java.io.ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            store(out.toByteArray())
                        })
                    }
                } catch (e: Exception) {
                    setStatus("Could not use that image: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            REQ_SAVE -> {
                val l = layout ?: return
                val st = state ?: return
                try {
                    contentResolver.openOutputStream(uri)!!.use { it.write(SetWriter.build(l, st)) }
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    } catch (e: Exception) {
                    }
                    addRecent(uri, displayName(uri))
                    toast("Saved ${st.values["cardname"] ?: "card"}.")
                } catch (e: Exception) {
                    setStatus("Could not save: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            REQ_PNG -> {
                val b = lastBmp ?: return
                try {
                    contentResolver.openOutputStream(uri)!!.use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    toast("Card image saved.")
                } catch (e: Exception) {
                    setStatus("Could not save the image: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            REQ_SET -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
                loadSet(uri)
            }
        }
    }

    /** Reads the folder: finds the layout XML and the assets ZIP, loads them, and keeps the last good layout on error. */
    private fun scan(tree: Uri) {
        if (scanning) return
        scanning = true
        folderUri = tree
        thread {
            var loaded = false
            try {
                val dir = DocumentFile.fromTreeUri(this, tree)
                if (dir == null || !dir.isDirectory) {
                    runOnUiThread {
                        xmlNote = "Cannot open that folder."
                        refreshLayoutsPage()
                    }
                } else {
                    folderName = dir.name ?: ""
                    val files = dir.listFiles()
                    val xml = files.firstOrNull { it.isFile && it.name?.endsWith(".xml", true) == true }
                    val zip = files.firstOrNull { it.isFile && it.name?.endsWith(".zip", true) == true }
                    if (xml == null) {
                        xmlName = ""
                        xmlUri = null
                        xmlError = null
                        xmlNote = "No .xml layout found in this folder."
                    } else {
                        xmlUri = xml.uri
                        xmlName = xml.name ?: "layout.xml"
                        xmlModified = xml.lastModified()
                        val text = contentResolver.openInputStream(xml.uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        try {
                            val l = LayoutParser.parse(text)
                            layout = l
                            xmlError = null
                            xmlLoadedAt = System.currentTimeMillis()
                            val images = l.elements.count { it.kind == "image" }
                            xmlNote = "${l.width.toInt()}×${l.height.toInt()} · ${l.datas.size} options · $images images"
                            loaded = true
                        } catch (e: LayoutParser.ParseError) {
                            val lines = text.lines()
                            val from = maxOf(1, e.line - 2)
                            val ctx = (from..minOf(lines.size, e.line)).joinToString("\n") { n ->
                                "${n.toString().padEnd(5)}${lines[n - 1].trim().take(60)}"
                            }
                            xmlError = ParseInfo(e.line, e.message ?: "problem", ctx)
                        }
                    }
                    if (zip == null) {
                        zipName = ""
                        zipOk = false
                    } else {
                        zipName = zip.name ?: "assets.zip"
                        val target = File(filesDir, "layout.zip")
                        val stamp = "${zip.length()}:${zip.lastModified()}"
                        if (!target.exists() || prefs.getString("zipStamp", "") != stamp) {
                            runOnUiThread { setStatus("Copying $zipName (${zip.length() / 1024 / 1024} MB)…") }
                            contentResolver.openInputStream(zip.uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                            prefs.edit().putString("zipStamp", stamp).apply()
                        }
                        zipFile = target
                        zipOk = true
                        zipInfo = "Frames, fonts, icons · ${target.length() / 1024 / 1024} MB"
                    }
                }
            } catch (e: Exception) {
                xmlNote = "Error: ${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread {
                scanning = false
                refreshLayoutsPage()
                if (loaded) {
                    val st = state
                    if (st != null) renderState(st) else if (zipOk) renderCard(null)
                }
            }
        }
    }

    /** Starts editing a card (a saved set, or null for the layout's defaults) and draws it. */
    private fun renderCard(set: CardSet?) {
        val st = CardState.from(set)
        state = st
        runOnUiThread { refreshSetsPage() }
        renderState(st)
    }

    /** Draws the card being edited on a background thread and shows it. */
    private fun renderState(st: CardState) {
        val l = layout ?: return
        val z = zipFile
        thread {
            val t0 = SystemClock.elapsedRealtime()
            var bmp: Bitmap? = null
            val msg = StringBuilder()
            var assets: Assets? = null
            var newHits: List<Hit> = emptyList()
            try {
                val ctx = Ctx(l, st.values)
                assets = Assets(z, st.files, cacheDir)
                val r = Renderer(l, ctx, assets)
                bmp = r.render(2f)
                val ms = SystemClock.elapsedRealtime() - t0
                val blank = assets.missing.filter { isBlankOnPurpose(it) }
                val real = assets.missing.filter { !isBlankOnPurpose(it) }
                msg.append(st.values["cardname"] ?: "Card").append(" · ${r.drawn} elements in $ms ms")
                if (real.isNotEmpty()) msg.append(" · ${real.size} not found (tap for details)")
                msg.append("\n")
                newHits = r.hits
                r.notes.forEach { msg.append(it).append('\n') }
                if (blank.isNotEmpty()) msg.append("Blank on purpose (none/off images): ${blank.size}\n")
                if (real.isNotEmpty()) {
                    msg.append("\nNot found / failed (${real.size}):\n")
                    real.take(25).forEach { msg.append("  • $it\n") }
                    if (real.size > 25) msg.append("  … and ${real.size - 25} more\n")
                }
            } catch (e: Throwable) {
                msg.append("Drawing failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                assets?.close()
            }
            runOnUiThread {
                setStatus(msg.toString())
                if (bmp != null) {
                    lastBmp = bmp
                    cardView.bitmap = bmp
                    hits = newHits
                }
            }
        }
    }

    /** Layouts ask for names like none.png, off.png or ".png" when an option is switched off; the old app skips them quietly. */
    private fun isBlankOnPurpose(name: String): Boolean {
        if (name.equals("none", ignoreCase = true)) return true
        val base = name.substringAfterLast('/').removeSuffix(".png").lowercase()
        return base in setOf("", "none", "off", "dd_none")
    }

    companion object {
        private const val REQ_TREE = 1001
        private const val REQ_SET = 1002
        private const val REQ_IMAGE = 1003
        private const val REQ_SAVE = 1004
        private const val REQ_PNG = 1005
    }
}
