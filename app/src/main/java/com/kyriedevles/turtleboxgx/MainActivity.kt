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
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
    private lateinit var dockOuter: LinearLayout
    private lateinit var dockInner: LinearLayout
    private var dockPage: LinearLayout? = null
    private var dockSig = ""
    private var dockStructSig = ""
    private lateinit var dockBody: LinearLayout
    private lateinit var dockHandle: FrameLayout
    private lateinit var dockPill: TextView
    private lateinit var dockChev: FrameLayout
    private var dragHeight = -1
    private class TileRef(val menu: String, val status: TextView, val dot: View, val box: View, val label: String)
    private var tileRefs: List<TileRef> = emptyList()
    private var dockScroll = 0
    /** 0 = only the handle row, 1 = the row of tiles, 2 = the grid (any height). */
    private val dockMode get() = prefs.getInt("dock_mode", if (prefs.getBoolean("dock_open", false)) 2 else 1)
    private var rowH = 0
    private lateinit var layoutsBox: LinearLayout
    private lateinit var setsBox: LinearLayout
    private lateinit var pages: List<View>
    private lateinit var tabIcons: List<HotIcon>
    private lateinit var tabLabels: List<TextView>
    private var tab = 0

    private var layout: Layout? = null
    private var baseXml = ""
    private var templates: Map<String, Templates.Info> = emptyMap()
    private var selectedTemplate = ""
    private var templateNotes: List<String> = emptyList()
    private var undefinedNames: List<String> = emptyList()
    private var mergedXml: String? = null
    private var templatesDoc: DocumentFile? = null

    private fun templateAssetDir(): File? = selectedTemplate.takeIf { it.isNotBlank() }?.let { File(filesDir, "tpl/$it") }

    /** Copies templates/<name>/ (the template's own pictures) into app storage, only what changed. */
    private fun syncTemplateAssets(name: String) {
        if (name.isBlank()) return
        try {
            val kids = templatesDoc?.listFiles() ?: return
            // One source only: templates/<name>/ (loose files) if it exists, else templates/<name>.zip.
            val dir = File(filesDir, "tpl/$name")
            val zf = File(filesDir, "tpl/$name.zip")
            val sf = File(filesDir, "tpl/$name.zip.stamp")
            val folder = kids.firstOrNull { it.isDirectory && it.name.equals(name, true) }
            val z = kids.firstOrNull { it.isFile && it.name.equals("$name.zip", true) }
            if (folder != null) {
                syncDevFolder(folder, dir, "tpl-$name.manifest")
                zf.delete(); sf.delete()
            } else {
                dir.deleteRecursively()
                File(filesDir, "tpl-$name.manifest").delete()
                if (z == null) {
                    zf.delete(); sf.delete()
                } else {
                    val stamp = "${z.length()}:${z.lastModified()}"
                    if (!zf.exists() || !sf.exists() || sf.readText() != stamp) {
                        zf.parentFile?.mkdirs()
                        contentResolver.openInputStream(z.uri)?.use { i -> zf.outputStream().use { it2 -> i.copyTo(it2) } }
                        sf.writeText(stamp)
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    /** The base layout with the selected template patched in (or the base alone). */
    private fun buildLayout(base: String): Layout {
        val t = templates[selectedTemplate]
        if (t == null) {
            templateNotes = emptyList()
            mergedXml = null
            return LayoutParser.parse(base).also { undefinedNames = NameCheck.undefined(base, it) }
        }
        val r = try {
            Templates.apply(base, t)
        } catch (e: Exception) {
            Templates.Result(base, listOf("template ${t.name}: ${e.javaClass.simpleName}: ${e.message}"))
        }
        templateNotes = r.notes
        mergedXml = r.xml
        return LayoutParser.parse(r.xml).also { undefinedNames = NameCheck.undefined(r.xml, it) }
    }

    private fun selectTemplate(name: String) {
        selectedTemplate = name
        prefs.edit().putString("template", name).apply()
        state?.let { if (name.isBlank()) it.values.remove("template") else it.values["template"] = name }
        thread {
            syncTemplateAssets(name)
            try {
                layout = buildLayout(baseXml)
            } catch (e: LayoutParser.ParseError) {
                runOnUiThread { toast("Template problem: ${e.message}") }
            }
            runOnUiThread {
                refreshLayoutsPage()
                state?.let { renderState(it) }
            }
        }
    }
    private var zipFile: File? = null
    private var state: CardState? = null
    private var hits: List<Hit> = emptyList()
    private var pendingImageKey: String? = null
    private var layersPanel: LayersPanel? = null
    private var editWindow: LayerEditWindow? = null
    private var editingLayerId: String? = null
    @Volatile private var editAbove: Bitmap? = null
    private var pendingLayerReplace: String? = null
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
    private var setsFolderName = ""
    private var setFiles: List<SetFile> = emptyList()
    private var setRoot: SetNode? = null
    private val openSetFolders = HashSet<String>()

    /** A folder inside the sets folder: its own sets and the folders below it. */
    private class SetFile(val uri: Uri, val name: String)

    private class SetNode(val name: String, val path: String, val files: List<SetFile>, val children: List<SetNode>) {
        val total: Int get() = files.size + children.sumOf { it.total }
    }

    /** One query per folder (id, name, type together) instead of one per file, which is far faster on Android's file provider. */
    private fun scanFast(tree: Uri, docId: String, name: String, path: String, depth: Int): SetNode {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val files = ArrayList<SetFile>()
        val dirs = ArrayList<Pair<String, String>>()
        val cur = contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        ) ?: throw IllegalStateException("no cursor")
        cur.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val n = it.getString(1) ?: continue
                if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) dirs.add(id to n)
                else if (n.endsWith(".msoe-set", true)) files.add(SetFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), n))
            }
        }
        files.sortBy { it.name.lowercase() }
        val kids = if (depth >= 5) emptyList() else dirs.sortedBy { it.second.lowercase() }
            .map { scanFast(tree, it.first, it.second, path + "/" + it.second, depth + 1) }.filter { it.total > 0 }
        return SetNode(name, path, files, kids)
    }

    /** Slow but universal fallback through DocumentFile. */
    private fun scanNode(dir: DocumentFile, path: String, depth: Int): SetNode {
        val all = dir.listFiles()
        val files = all.filter { it.isFile && it.name?.endsWith(".msoe-set", true) == true }.map { SetFile(it.uri, it.name ?: "set") }.sortedBy { it.name.lowercase() }
        val kids = if (depth >= 5) emptyList() else all.filter { it.isDirectory }.sortedBy { (it.name ?: "").lowercase() }
            .map { scanNode(it, path + "/" + (it.name ?: ""), depth + 1) }.filter { it.total > 0 }
        return SetNode(dir.name ?: "", path, files, kids)
    }
    private var setsScanning = false

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
        val crashFile = File(filesDir, "crash.txt")
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                crashFile.writeText("Thread ${t.name}\n" + android.util.Log.getStackTraceString(e).take(6000))
            } catch (x: Throwable) {
            }
            prevHandler?.uncaughtException(t, e)
        }
        curSet = prefs.getString("curset", null)?.let { Uri.parse(it) }
        selectedTemplate = prefs.getString("template", "") ?: ""
        curSetName = prefs.getString("cursetname", "") ?: ""
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

        showTab(savedInstanceState?.getInt("tab") ?: 0)
        refreshLayoutsPage()
        refreshSetsPage()

        if (crashFile.exists()) {
            val report = try { crashFile.readText() } catch (e: Exception) { "" }
            crashFile.delete()
            if (report.isNotBlank()) {
                val tv = label(report, 11f, Color.WHITE).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(16), dp(12), dp(16), dp(12)) }
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("The app crashed last time")
                    .setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("Close", null).show()
            }
        }
        val saved = prefs.getString("tree", null)
        if (saved != null) {
            val uri = Uri.parse(saved)
            val stillAllowed = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (stillAllowed) scan(uri)
        } else if (!prefs.getBoolean("setupAsked", false) && bundledFiles().isNotEmpty()) {
            prefs.edit().putBoolean("setupAsked", true).apply()
            handler.post {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Set up TurtleBox GX")
                    .setMessage("Choose or create a folder (for example “Turtlebox GX”). The app puts its layout files and an empty templates folder there; you can add your own templates later.")
                    .setPositiveButton("Set up") { _, _ -> setupFolder() }
                    .setNegativeButton("Later", null).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(poll, 300)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
        autosave()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        autosave()
    }

    private val autoFile by lazy { File(filesDir, "autosave.msoe-set") }
    private val autoLock = Any()

    /** Keeps the card being edited on disk so rotating or closing the app never loses it. */
    private fun autosave() {
        val l = layout ?: return
        val st = state ?: return
        try {
            synchronized(autoLock) {
                val bytes = SetWriter.build(l, st)
                val tmp = File(filesDir, "autosave.tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(autoFile)
            }
        } catch (e: Throwable) {
        }
    }

    /** Restores the autosaved card. If the app died while doing this last time, the autosave is dropped instead. */
    private fun readAutosave(): CardSet? {
        if (prefs.getBoolean("restoring", false)) {
            prefs.edit().putBoolean("restoring", false).commit()
            autoFile.delete()
            return null
        }
        if (!autoFile.exists()) return null
        prefs.edit().putBoolean("restoring", true).commit()
        return try {
            CardSet.read(autoFile.readBytes())
        } catch (e: Throwable) {
            autoFile.delete()
            prefs.edit().putBoolean("restoring", false).commit()
            null
        }
    }

    private var watchedStamp: String? = null

    /** Size and edit time of the layout XML and of every template file, so any edit is noticed. */
    private fun watchStamp(x: Uri): String? = try {
        val d = DocumentFile.fromSingleUri(this, x)
        val sb = StringBuilder("${d?.lastModified()}:${d?.length()}")
        templatesDoc?.listFiles()?.forEach { t -> if (t.isFile) sb.append('|').append(t.name).append(':').append(t.lastModified()).append(':').append(t.length()) }
        if ((d?.lastModified() ?: 0L) == 0L) null else sb.toString()
    } catch (e: Exception) {
        null
    }

    /** Every few seconds: did the XML change? Reload it. Otherwise just refresh the "edited … ago" text. */
    private val poll = object : Runnable {
        override fun run() {
            val x = xmlUri
            val f = folderUri
            if (x != null && f != null && !scanning) {
                val stamp = watchStamp(x)
                if (stamp != null && stamp != watchedStamp) scan(f) else if (tab == 1) refreshLayoutsPage()
            }
            handler.postDelayed(this, 2500)
        }
    }

    // ---- shell ----

    private fun buildTopBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), 0, dp(4), 0)
        val title = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(label("TurtleBox", 20f, Hot.pink, bold = true).apply { includeFontPadding = false })
        title.addView(label("v${versionName()}", 11f, Hot.dim).apply { includeFontPadding = false; setPadding(0, dp(2), 0, 0) })
        addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun action(kind: String, name: String, act: () -> Unit) {
            val col = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { act() }
            }
            col.addView(HotIcon(this@MainActivity, kind).apply { contentDescription = name }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(2) })
            col.addView(label(name, 12f, Hot.inactive).apply { gravity = Gravity.CENTER; includeFontPadding = false }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(col, LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        action("clear", "Clear") { clearCard() }
        action("save", "Save") { saveSet() }
        action("eye", "Preview") { showPreview() }
        action("image", "Image") { saveImage() }
    }

    private fun clearCard() {
        if (layout == null) return toast("Choose a layout folder first.")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Clear card?")
            .setMessage("This resets the card to the layout's defaults. Anything not saved to a set is lost.")
            .setPositiveButton("Clear") { _, _ -> renderCard(null, true) }
            .setNegativeButton("Cancel", null).show()
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
        if (i == 2) { refreshSetsPage(); scanSetsFolder() }
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
        // The edit dock: a row of tiles that opens into a grid.
        dockOuter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Hot.gradient(dp(20).toFloat())
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        dockInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Hot.outline(dp(18).toFloat(), 0, Color.BLACK)
        }
        // The handle stays put while the body is rebuilt, so a drag is never interrupted.
        val handle = FrameLayout(this)
        handle.addView(
            View(this).apply { background = Hot.gradient(dp(3).toFloat(), true) },
            FrameLayout.LayoutParams(dp(44), dp(5), Gravity.CENTER),
        )
        dockPill = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Hot.muted)
            maxLines = 1
            background = Hot.outline(dp(14).toFloat(), dp(2), Color.parseColor("#4a1f38"), Color.parseColor("#12081a"))
            setOnClickListener { showTemplateDialog() }
        }
        handle.addView(dockPill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(12) })
        dockChev = FrameLayout(this).apply {
            background = Hot.outline(dp(14).toFloat(), dp(2), Color.parseColor("#4a1f38"), Color.parseColor("#12081a"))
            isClickable = true
            setOnClickListener { flipDock() }
        }
        handle.addView(dockChev, FrameLayout.LayoutParams(dp(36), dp(28), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(12) })
        var downY = 0f
        var startH = 0
        var moving = false
        var lastH = 0
        var lastM = 1
        handle.setOnTouchListener { v, e ->
            val pageH = (dockPage?.height ?: 0).let { if (it > 0) it else (resources.displayMetrics.heightPixels * 0.8f).toInt() }
            val maxH = (pageH * 0.62f).toInt()
            val miniH = dp(44)
            val row = if (rowH > 0) rowH else dp(160)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY; moving = false
                    startH = dockOuter.height
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (Math.abs(dy) > dp(6)) moving = true
                    if (moving) {
                        val h = (startH - dy).toInt().coerceIn(miniH, maxOf(miniH, maxH))
                        val m = when {
                            h < (miniH + row) / 2 -> 0
                            h < row + dp(60) -> 1
                            else -> 2
                        }
                        lastH = h
                        lastM = m
                        dragHeight = h
                        applyDockSize(m, h)
                        refreshDock(false, m)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moving) {
                        if (lastM == 2) prefs.edit().putFloat("dock_frac", (lastH.toFloat() / pageH).coerceIn(0.2f, 0.62f)).apply()
                        setDockMode(lastM)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        flipDock()
                    }
                    moving = false
                }
            }
            true
        }
        dockHandle = handle
        dockBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dockInner.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        dockInner.addView(dockBody, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dockOuter.addView(dockInner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        page.addView(dockOuter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), 0, dp(6), dp(6)) })
        dockPage = page
        refreshDock(true)
        return page
    }

    // ---- edit dock ----

    private fun dockTiles(): List<Tile> {
        val t = layout?.tiles ?: emptyList()
        val base = if (t.isEmpty()) {
            listOf(
                Tile("cardtextmenu", "Effect text", "text", null, false), Tile("namemenu", "Name", "name", null, false),
                Tile("atkdef", "Stats", "stats", null, false), Tile("cardbg", "Frame", "frame", null, false),
            )
        } else {
            t
        }
        return if (base.none { it.menu == "@layers" }) base + Tile("@layers", "Layers", "layers", null, false) else base
    }

    /** True when something in the menu differs from its default (shown as a dot on the tile). */
    private fun menuChanged(menu: String, seen: MutableSet<String> = HashSet()): Boolean {
        val l = layout ?: return false
        val st = state ?: return false
        if (menu == "@layers") return ImageLayers.read(st.values).isNotEmpty()
        if (!seen.add(menu)) return false
        val d = l.datas[menu] ?: return false
        if (d.type == "multi_select" || d.type == "togglegrid") return d.items.any { (_, v) -> v.isNotEmpty() && menuChanged(v, seen) }
        val cur = st.values[menu] ?: return false
        return cur != (d.default ?: "")
    }

    private fun tileStatus(t: Tile): String? {
        val l = layout
        val st = state
        if (t.menu == "@layers") {
            if (st == null) return null
            val n = ImageLayers.read(st.values).size
            return if (n == 0) "none" else if (n == 1) "1 image" else "$n images"
        }
        if (l == null || st == null) return null
        val g = l.datas[t.menu]
        if (t.status == null && g != null && g.type == "togglegrid") {
            val cells = g.items.map { it.second }.filter { it.isNotEmpty() && it != g.master }.mapNotNull { l.datas[it] }
            val on = cells.count { c ->
                val oo = c.offOn()
                oo != null && (st.values[c.name] ?: c.default ?: "") == oo.second
            }
            return "$on of ${cells.size} on"
        }
        val raw = t.status ?: return null
        // A status that is just one dropdown field shows the choice's name rather than its stored value.
        Regex("^\\{\\{([^{}]+)\\}\\}$").find(raw.trim())?.let { m ->
            val d = l.datas[m.groupValues[1]]
            if (d != null && d.items.isNotEmpty() && d.type != "multi_select" && d.type != "togglegrid") {
                val v = st.values[d.name] ?: Ctx(l, st.values).value(d.name)
                val shown = d.items.firstOrNull { it.second == v }?.first ?: v
                return shown.trim().ifBlank { null }
            }
        }
        val out = Ctx(l, st.values).sub(raw).trim()
        return if (out.isBlank() || out.contains("{{")) null else out
    }

    private fun setDockMode(m: Int) {
        val e = prefs.edit().putInt("dock_mode", m)
        if (m > 0) e.putInt("dock_last", m)
        e.apply()
        dragHeight = -1
        applyDockSize(m)
        refreshDock(true)
    }

    /** Chevron and handle tap: the handle row alone, or back to the row / grid you had open. */
    private fun flipDock() {
        setDockMode(if (dockMode == 0) prefs.getInt("dock_last", 1) else 0)
    }

    private fun showTemplateDialog() {
        val names = listOf("") + templates.keys
        val labels = (listOf(layout?.styleInfo?.get("label")?.ifBlank { null } ?: "Default (base layout)") + templates.values.map { it.label + if (it.description.isNotBlank()) "\n" + it.description else "" }).toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Template")
            .setSingleChoiceItems(labels, names.indexOf(selectedTemplate).coerceAtLeast(0)) { d, i -> d.dismiss(); selectTemplate(names[i]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun tileView(t: Tile, status: String?, changed: Boolean, icons: Assets?, refs: MutableList<TileRef>): View {
        val box = FrameLayout(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#12081a"))
                cornerRadius = dp(14).toFloat()
                val c = Color.parseColor("#4a1f38")
                if (t.fromTemplate) setStroke(dp(2), c, dp(6).toFloat(), dp(4).toFloat()) else setStroke(dp(2), c)
            }
        }
        val icon = HotIcon(this, t.icon)
        // An icon with a dot in its name is a picture file (png, webp, jpg) from the card, template or layout assets.
        if (t.icon.contains('.')) icon.bitmap = try { icons?.bitmap(t.icon) } catch (e: Exception) { null }
        col.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(label(t.label, 13f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val st = label(status ?: "", 11f, Hot.dim).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true; visibility = if (status == null) View.GONE else View.VISIBLE }
        col.addView(st)
        box.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Hot.pink) }
            visibility = if (changed) View.VISIBLE else View.GONE
        }
        box.addView(dot, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(9), dp(9), 0) })
        box.isClickable = true
        box.setOnClickListener { if (t.menu == "@layers") openLayers() else openEditor(t.menu) }
        refs.add(TileRef(t.menu, st, dot, box, t.label))
        applyTileText(refs.last(), status, changed)
        return box
    }

    private fun applyTileText(r: TileRef, status: String?, changed: Boolean) {
        if (status == null) r.status.visibility = View.GONE else { r.status.text = status; r.status.visibility = View.VISIBLE }
        r.dot.visibility = if (changed) View.VISIBLE else View.GONE
        r.box.contentDescription = r.label + (if (status != null) ", $status" else "") + if (changed) ", changed" else ""
    }

    /** Sizes the dock: the handle row only (mode 0), as tall as its tile row (1), or a free height up to most of the screen (2). */
    private fun applyDockSize(m: Int, liveH: Int = -1) {
        if (!::dockInner.isInitialized) return
        val outer = dockOuter.layoutParams as LinearLayout.LayoutParams
        val cardLp = cardView.layoutParams as LinearLayout.LayoutParams
        val inner = dockInner.layoutParams as LinearLayout.LayoutParams
        val body = dockBody.layoutParams as LinearLayout.LayoutParams
        val grid = m == 2
        if (grid) {
            val pageH = (dockPage?.height ?: 0).let { if (it > 0) it else (resources.displayMetrics.heightPixels * 0.8f).toInt() }
            outer.height = if (liveH > 0) liveH else (pageH * prefs.getFloat("dock_frac", 0.5f)).toInt()
            outer.weight = 0f
        } else {
            outer.height = ViewGroup.LayoutParams.WRAP_CONTENT
            outer.weight = 0f
        }
        cardLp.weight = 1f
        inner.height = if (grid) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        body.height = if (grid) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        body.weight = if (grid) 1f else 0f
        dockBody.visibility = if (m == 0) View.GONE else View.VISIBLE
        dockOuter.layoutParams = outer
        cardView.layoutParams = cardLp
        dockInner.layoutParams = inner
        dockBody.layoutParams = body
    }

    private fun refreshDock(force: Boolean = false, mode: Int? = null) {
        if (!::dockInner.isInitialized) return
        val tiles = dockTiles()
        val m = mode ?: dockMode
        val info = tiles.map { Triple(it, tileStatus(it), menuChanged(it.menu)) }
        val tlabel = if (templates.isEmpty()) "" else (templates[selectedTemplate]?.label ?: layout?.styleInfo?.get("label")?.ifBlank { null } ?: "Default")
        val struct = m.toString() + "|" + tlabel + "|" + selectedTemplate + "|" + info.joinToString(";") { "${it.first.menu}/${it.first.label}/${it.first.icon}/${it.first.fromTemplate}" }
        val sig = struct + "#" + info.joinToString(";") { "${it.second}/${it.third}" }
        if (!force && sig == dockSig) return
        dockSig = sig
        // Only text or dots changed: update the tiles where they are, so nothing blinks.
        if (!force && struct == dockStructSig && tileRefs.size == info.size) {
            for ((k, r) in tileRefs.withIndex()) applyTileText(r, info[k].second, info[k].third)
            return
        }
        dockStructSig = struct
        val refs = ArrayList<TileRef>()
        val icons: Assets? = if (info.any { it.first.icon.contains('.') }) {
            try { Assets(zipFile, state?.files ?: emptyMap(), cacheDir, selectedTemplate.ifBlank { null }, templateAssetDir()) } catch (e: Exception) { null }
        } else {
            null
        }
        val keepScroll = dockScroll
        (statusLine.parent as? ViewGroup)?.removeView(statusLine)
        dockBody.removeAllViews()
        if (mode == null) applyDockSize(m)
        dockHandle.contentDescription = if (m != 0) "Drag or tap to collapse the edit tiles" else "Drag or tap to expand the edit tiles"

        // Template pill (left) and two-state chevron (right) live in the handle row.
        dockPill.text = tlabel
        dockPill.visibility = if (templates.isEmpty()) View.GONE else View.VISIBLE
        dockChev.removeAllViews()
        dockChev.contentDescription = if (m != 0) "Collapse the edit tiles" else "Expand the edit tiles"
        dockChev.addView(HotIcon(this, if (m != 0) "down" else "up").apply { gradient = false }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))

        if (m == 0) {
            tileRefs = emptyList()
        } else if (m == 2) {
            val cols = maxOf(3, (resources.displayMetrics.widthPixels / resources.displayMetrics.density / 130f).toInt())
            val grid = android.widget.GridLayout(this).apply {
                columnCount = cols
                setPadding(dp(10), 0, dp(10), dp(10))
            }
            for ((t, status, changed) in info) {
                val lp = android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                ).apply { width = 0; height = dp(88); setMargins(dp(4), dp(4), dp(4), dp(4)) }
                grid.addView(tileView(t, status, changed, icons, refs), lp)
            }
            val sv = object : ScrollView(this) {
                override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) { super.onScrollChanged(l, t, ol, ot); dockScroll = t }
            }
            sv.addView(grid)
            dockBody.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            sv.post { sv.scrollTo(0, keepScroll) }
        } else {
            dockOuter.post { rowH = dockOuter.height }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), 0, dp(14), 0)
            }
            for ((t, status, changed) in info) {
                row.addView(tileView(t, status, changed, icons, refs), LinearLayout.LayoutParams(dp(92), dp(76)).apply { rightMargin = dp(8) })
            }
            val hs = object : HorizontalScrollView(this) {
                override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) { super.onScrollChanged(l, t, ol, ot); dockScroll = l }
            }
            hs.isHorizontalScrollBarEnabled = false
            hs.addView(row)
            dockBody.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            hs.post { hs.scrollTo(keepScroll, 0) }
        }
        if (m != 0) tileRefs = refs
        try { icons?.close() } catch (e: Exception) { }
        statusLine.setPadding(dp(16), dp(6), dp(16), dp(8))
        if (m != 0) dockBody.addView(statusLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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

    // ---- image layers ----

    private fun openLayers() {
        val l = layout
        val st = state
        if (l == null || st == null) return toast("Choose a layout folder first.")
        val panel = LayersPanel(
            this, l, st,
            onChanged = { renderState(st) },
            pickImage = { replaceId ->
                pendingLayerReplace = replaceId
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_LAYER)
            },
            startEdit = { layer, all -> beginLayerEdit(layer, all) },
        )
        layersPanel = panel
        panel.show()
    }

    /** Adds a layer (or replaces the picture of [replaceId]) from PNG bytes, fitted to the whole card. */
    private fun storeLayer(png: ByteArray, replaceId: String?) {
        val l = layout ?: return
        val st = state ?: return
        val all = ImageLayers.read(st.values)
        val (bw, bh) = ImageLayers.size(png)
        val existing = all.firstOrNull { it.id == replaceId }
        if (existing != null) {
            st.files[existing.file] = png
            st.files.remove(existing.origFile)
            ImageLayers.fit(existing, bw, bh, l.width, l.height)
        } else {
            val id = ImageLayers.newId()
            val top = maxOf(l.elements.maxOfOrNull { it.index } ?: 0f, all.maxOfOrNull { it.z } ?: 0f)
            val layer = Layer(id, "Layer ${all.size + 1}", top + 1f)
            ImageLayers.fit(layer, bw, bh, l.width, l.height)
            st.files[layer.file] = png
            all.add(layer)
        }
        ImageLayers.write(st.values, all)
        renderState(st)
        layersPanel?.refresh()
    }

    private fun handleLayerImage(uri: Uri) {
        val l = layout ?: return
        val replaceId = pendingLayerReplace
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IllegalArgumentException("That file is not an image.")
            Cropper.show(this, bmp, bytes, bounds.outWidth, l.width / l.height, onCrop = { cropped ->
                val out = java.io.ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                storeLayer(out.toByteArray(), replaceId)
            }, onWhole = { original ->
                storeLayer(if (original) fullPng(bytes) else shrinkToPng(bytes), replaceId)
            })
        } catch (e: Throwable) {
            setStatus("Could not use that image: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun beginLayerEdit(layer: Layer, all: List<Layer>) {
        val l = layout ?: return
        val st = state ?: return
        val bytes = st.files[layer.file] ?: return toast("That layer has no picture.")
        val bmp = try {
            val (bw, bh) = ImageLayers.size(bytes)
            var sample = 1
            while (maxOf(bw, bh) / (sample * 2) >= 2048) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Throwable) {
            null
        } ?: return toast("Could not open that picture.")
        editingLayerId = layer.id
        val ed = LayerEditor(layer, bmp, l.width, l.height)
        showTab(0)
        renderState(st)
        cardView.editor = ed
        val win = LayerEditWindow(
            this, ed, layer.name,
            save = {
                val cur = ImageLayers.read(st.values)
                cur.firstOrNull { it.id == layer.id }?.let { c ->
                    c.cx = layer.cx; c.cy = layer.cy; c.w = layer.w; c.h = layer.h; c.rot = layer.rot; c.mx = layer.mx; c.fy = layer.fy
                }
                ImageLayers.write(st.values, cur)
            },
            onTop = { topY ->
                val loc = IntArray(2)
                cardView.getLocationOnScreen(loc)
                cardView.insetBottom = maxOf(0, loc[1] + cardView.height - topY)
            },
            eraserSource = {
                try {
                    // Both pictures at the same size, at most 2048 px on the long side, so Restore lines up.
                    val origBytes = st.files[layer.origFile] ?: st.files[layer.file]!!
                    val (ow, oh) = ImageLayers.size(origBytes)
                    val sc = minOf(1f, 2048f / maxOf(ow, oh))
                    val tw = maxOf(1, Math.round(ow * sc))
                    val th = maxOf(1, Math.round(oh * sc))
                    fun dec(b: ByteArray): Bitmap {
                        val (bw, bh) = ImageLayers.size(b)
                        var s = 1
                        while (maxOf(bw, bh) / (s * 2) >= maxOf(tw, th)) s *= 2
                        var bm = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 })!!
                        if (bm.width != tw || bm.height != th) bm = Bitmap.createScaledBitmap(bm, tw, th, true)
                        return bm
                    }
                    val orig = dec(origBytes)
                    val work = dec(st.files[layer.file]!!).copy(Bitmap.Config.ARGB_8888, true)
                    work to orig
                } catch (e: Throwable) {
                    toast("Could not open the picture for erasing.")
                    null
                }
            },
            saveBitmap = { bm ->
                val out = java.io.ByteArrayOutputStream()
                bm.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (st.files[layer.origFile] == null) st.files[layer.file]?.let { st.files[layer.origFile] = it }
                st.files[layer.file] = out.toByteArray()
            },
            onDone = {
                cardView.editor = null
                cardView.insetBottom = 0
                editingLayerId = null
                editWindow = null
                renderState(st)
                openLayers()
            },
        )
        editWindow = win
        win.show()
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
            namePreview = { maps ->
                val a = Assets(zipFile, st.files, cacheDir, selectedTemplate.ifBlank { null }, templateAssetDir())
                try {
                    Renderer(l, Ctx(l, st.values), a).previewName(maps, 360, 110)
                } finally {
                    a.close()
                }
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

        if (bundledFiles().isNotEmpty()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Hot.outline(dp(16).toFloat(), dp(2), Hot.line)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            if (folderUri == null) {
                card.addView(label("Set up", 13f, Hot.muted))
                card.addView(label("The app carries a built-in layout. Choose or create a folder (for example “Turtlebox GX”) and it puts the layout files and an empty templates folder there. Files already in the folder are kept.", 13f, Hot.dim).apply { setPadding(0, dp(4), 0, 0) })
                card.addView(pill("Set up", true) { setupFolder() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { topMargin = dp(10) })
            } else {
                card.addView(label("Built-in layout", 13f, Hot.muted))
                card.addView(label("Put the layout XML or the zip back to the version the app came with.", 13f, Hot.dim).apply { setPadding(0, dp(4), 0, 0) })
                card.addView(pill("Restore…", false) { restoreDialog() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { topMargin = dp(10) })
            }
            add(card, 12)
        }

        if (templates.isNotEmpty()) {
            val tcard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Hot.outline(dp(16).toFloat(), dp(2), Hot.line)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            tcard.addView(label("Template", 13f, Hot.muted))
            tcard.addView(label(templates[selectedTemplate]?.label ?: layout?.styleInfo?.get("label")?.ifBlank { null } ?: "Default (base layout)", 15f, Color.WHITE, bold = true).apply { setPadding(0, dp(2), 0, 0) })
            templates[selectedTemplate]?.let { ti ->
                val meta = listOf(ti.description, listOf(ti.author, ti.version).filter { it.isNotBlank() }.joinToString(" · ")).filter { it.isNotBlank() }.joinToString("\n")
                if (meta.isNotBlank()) tcard.addView(label(meta, 13f, Hot.dim).apply { setPadding(0, dp(4), 0, 0) })
            }
            tcard.addView(pill("Change", false) {
                showTemplateDialog()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { topMargin = dp(10) })
            add(tcard, 12)
        }

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
        row.addView(pill("New card", false) { if (layout == null) toast("Choose a layout folder first.") else renderCard(null, true) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { leftMargin = dp(8) })
        head.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        box.addView(head)

        val folderCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Hot.outline(dp(16).toFloat(), dp(2), Hot.line)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        folderCard.addView(label("Sets folder", 13f, Hot.muted))
        val sfSaved = prefs.getString("setsTree", null)
        folderCard.addView(label(if (sfSaved == null) "None chosen yet" else "$setsFolderName · ${setRoot?.total ?: 0} sets", 15f, Color.WHITE, bold = true).apply { setPadding(0, dp(2), 0, 0) })
        val fr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fr.addView(pill(if (sfSaved == null) "Choose folder" else "Change", true) { pickSetsFolder() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        if (sfSaved != null) fr.addView(pill("Rescan", false) { scanSetsFolder() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { leftMargin = dp(8) })
        folderCard.addView(fr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        box.addView(folderCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
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

        val root = setRoot
        if (root != null && root.total > 0) {
            fun addSet(f: SetFile, indent: Int) {
                val on = curSet == f.uri
                val r = label(f.name.removeSuffix(".msoe-set"), 15f, if (on) Hot.pinkLight else Color.WHITE, bold = true).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = Hot.outline(dp(14).toFloat(), dp(2), if (on) Hot.pinkDeep else Hot.line)
                    setPadding(dp(16), 0, dp(16), 0)
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnClickListener { addRecent(f.uri, f.name); loadSet(f.uri) }
                }
                box.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8); leftMargin = dp(14 * indent) })
            }
            fun addNode(n: SetNode, indent: Int) {
                for (k in n.children) {
                    val isOpen = openSetFolders.contains(k.path)
                    val h = label((if (isOpen) "▾  " else "▸  ") + k.name + "  ·  ${k.total}", 15f, Hot.pinkLight, bold = true).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        background = Hot.outline(dp(14).toFloat(), dp(2), if (isOpen) Hot.pinkDeep else Hot.line)
                        setPadding(dp(16), 0, dp(16), 0)
                        setSingleLine(true)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setOnClickListener { if (isOpen) openSetFolders.remove(k.path) else openSetFolders.add(k.path); refreshSetsPage() }
                    }
                    box.addView(h, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8); leftMargin = dp(14 * indent) })
                    if (isOpen) {
                        k.files.forEach { addSet(it, indent + 1) }
                        addNode(k, indent + 1)
                    }
                }
            }
            if (root.children.isNotEmpty()) {
                box.addView(label("Folders", 13f, Hot.muted), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
                addNode(root, 0)
            }
            if (root.files.isNotEmpty()) {
                box.addView(label("Sets without a folder", 13f, Hot.muted), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
                root.files.forEach { addSet(it, 0) }
            }
        }

    }

    // ---- files ----

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun pickSetsFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        startActivityForResult(intent, REQ_SETS_TREE)
    }

    /** Lists the .msoe-set files in the chosen sets folder (names only; nothing is opened or drawn). */
    private fun scanSetsFolder() {
        val saved = prefs.getString("setsTree", null) ?: return
        if (setsScanning) return
        setsScanning = true
        thread {
            try {
                val dir = DocumentFile.fromTreeUri(this, Uri.parse(saved))
                if (dir != null && dir.isDirectory) {
                    setsFolderName = dir.name ?: ""
                    val treeUri = Uri.parse(saved)
                    setRoot = try {
                        scanFast(treeUri, DocumentsContract.getTreeDocumentId(treeUri), dir.name ?: "", "", 0)
                    } catch (e: Exception) {
                        scanNode(dir, "", 0)
                    }
                    setFiles = setRoot?.files ?: emptyList()
                }
            } catch (e: Exception) {
            }
            setsScanning = false
            runOnUiThread { refreshSetsPage() }
        }
    }

    // ---- built-in layout: the APK can carry the layout XML and zip; Set up copies them into a folder the user picks ----

    private fun bundledFiles(): List<String> = try {
        assets.list("")?.filter { it.endsWith(".xml") || it.endsWith(".zip") }?.sorted() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun setupFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        startActivityForResult(intent, REQ_SETUP)
    }

    /** Copies the built-in files into [tree] and makes an empty `templates` folder. Existing files are kept unless [overwrite]. */
    private fun copyBundled(tree: Uri, names: List<String>, overwrite: Boolean) {
        val progress = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Setting up")
            .setMessage("Copying the layout files. This can take a few seconds.").setCancelable(false).show()
        thread {
            val notes = ArrayList<String>()
            var failed: String? = null
            try {
                val dir = DocumentFile.fromTreeUri(this, tree) ?: throw IllegalStateException("Cannot open that folder.")
                if (dir.findFile("templates") == null) dir.createDirectory("templates")
                for (n in names) {
                    val existing = dir.findFile(n)
                    if (existing != null && !overwrite) {
                        notes.add("kept $n")
                        continue
                    }
                    val f = existing ?: dir.createFile(if (n.endsWith(".xml")) "text/xml" else "application/zip", n)
                        ?: throw IllegalStateException("Could not create $n.")
                    assets.open(n).use { input ->
                        (contentResolver.openOutputStream(f.uri, "wt") ?: throw IllegalStateException("Could not write $n.")).use { out ->
                            input.copyTo(out, 64 * 1024)
                        }
                    }
                    notes.add("wrote $n")
                }
            } catch (e: SecurityException) {
                failed = "this folder was opened read-only. Use Set up and choose it again to allow writing."
            } catch (e: Throwable) {
                failed = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                progress.dismiss()
                if (failed != null) {
                    toast("Set up failed: $failed")
                } else {
                    toast(notes.joinToString(" · "))
                    scan(tree)
                }
            }
        }
    }

    private fun restoreDialog() {
        val names = bundledFiles()
        val uri = folderUri
        if (names.isEmpty() || uri == null) return
        val canWrite = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!canWrite) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Allow writing")
                .setMessage("This folder was opened for reading only. Choose the same folder again to let the app restore files in it.")
                .setPositiveButton("Choose folder") { _, _ -> setupFolder() }.setNegativeButton("Cancel", null).show()
            return
        }
        val xml = names.filter { it.endsWith(".xml") }
        val zip = names.filter { it.endsWith(".zip") }
        val options = listOf("Layout XML only" to xml, "Pictures and fonts (zip) only" to zip, "Both" to names).filter { it.second.isNotEmpty() }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Restore built-in files")
            .setItems(options.map { it.first }.toTypedArray()) { _, i ->
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Replace your copy?")
                    .setMessage("This overwrites ${options[i].second.joinToString(", ")} in your folder with the built-in version. Any edits you made to it are lost.")
                    .setPositiveButton("Replace") { _, _ -> copyBundled(uri, options[i].second, true) }
                    .setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
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

    private var curSet: Uri? = null
    private var curSetName: String = ""

    private fun setCurSet(uri: Uri?, name: String = "") {
        curSet = uri
        curSetName = name
        prefs.edit().putString("curset", uri?.toString()).putString("cursetname", name).apply()
    }

    private fun saveSet() {
        if (state == null) return toast("No card to save yet.")
        val cur = curSet
        if (cur == null) return saveSetAsNew()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Save card set")
            .setMessage("Replace “${curSetName.removeSuffix(".msoe-set")}”, or keep it and save this card as a new set?")
            .setPositiveButton("Overwrite") { _, _ -> overwriteSet(cur) }
            .setNeutralButton("Save as new") { _, _ -> saveSetAsNew() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun overwriteSet(uri: Uri) {
        val l = layout ?: return
        val st = state ?: return
        try {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(SetWriter.build(l, st)) }
            toast("Saved ${curSetName.removeSuffix(".msoe-set")}.")
        } catch (e: Exception) {
            toast("Could not overwrite that file. Choose where to save instead.")
            saveSetAsNew()
        }
    }

    private fun saveSetAsNew() {
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

    /** The whole picture at full resolution: a PNG is kept byte for byte, anything else is decoded without shrinking. */
    private fun fullPng(bytes: ByteArray): ByteArray {
        if (bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()) return bytes
        return try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalArgumentException("That file is not an image.")
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        } catch (e: OutOfMemoryError) {
            shrinkToPng(bytes)
        }
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
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
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
                runOnUiThread { addRecent(uri, displayName(uri)); setCurSet(uri, displayName(uri)) }
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
            REQ_SETUP -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (e: Exception) {
                    // Some providers refuse persistence; the copy below still works this session.
                }
                prefs.edit().putString("tree", uri.toString()).apply()
                copyBundled(uri, bundledFiles(), false)
            }
            REQ_TREE -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // Some providers refuse persistence; the scan below still works this session.
                }
                prefs.edit().putString("tree", uri.toString()).apply()
                scan(uri)
            }
            REQ_SETS_TREE -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (e: Exception) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e2: Exception) {
                    }
                }
                prefs.edit().putString("setsTree", uri.toString()).apply()
                scanSetsFolder()
            }
            REQ_LAYER -> handleLayerImage(uri)
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
                        store(fullPng(bytes))
                    } else {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                            ?: throw IllegalArgumentException("That file is not an image.")
                        Cropper.show(this, bmp, bytes, bounds.outWidth, aspect, onCrop = { cropped ->
                            val out = java.io.ByteArrayOutputStream()
                            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                            store(out.toByteArray())
                        }, onWhole = { original ->
                            store(if (original) fullPng(bytes) else shrinkToPng(bytes))
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
                    setCurSet(uri, displayName(uri))
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
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (e: Exception) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e2: Exception) {
                    }
                }
                loadSet(uri)
            }
        }
    }

    /** Mirrors an unzipped assets folder into app storage, copying only files whose size or date changed. Returns the file count. */
    private fun syncDevFolder(src: DocumentFile, target: File, manifest: String = "devassets.manifest"): Int {
        target.mkdirs()
        val manifestFile = File(filesDir, manifest)
        val old = HashMap<String, String>()
        if (manifestFile.exists()) manifestFile.readLines().forEach { l -> if (l.contains('\t')) old[l.substringBefore('\t')] = l.substringAfter('\t') }
        val now = LinkedHashMap<String, String>()
        fun walk(d: DocumentFile, rel: String) {
            for (f in d.listFiles()) {
                val n = f.name ?: continue
                val path = if (rel.isEmpty()) n else "$rel/$n"
                if (f.isDirectory) {
                    walk(f, path)
                } else if (f.isFile) {
                    val stamp = "${f.length()}:${f.lastModified()}"
                    now[path] = stamp
                    val out = File(target, path)
                    if (!out.exists() || old[path] != stamp) {
                        out.parentFile?.mkdirs()
                        contentResolver.openInputStream(f.uri)?.use { i -> out.outputStream().use { i.copyTo(it) } }
                    }
                }
            }
        }
        walk(src, "")
        for (path in old.keys) if (path !in now) File(target, path).delete()
        manifestFile.writeText(now.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        return now.size
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
                        baseXml = text
                        val tdir = files.firstOrNull { it.isDirectory && it.name.equals("templates", true) }
                        templatesDoc = tdir
                        templates = if (tdir == null) emptyMap() else tdir.listFiles()
                            .filter { it.isFile && it.name?.endsWith(".xml", true) == true }
                            .mapNotNull { f ->
                                try {
                                    val n = (f.name ?: return@mapNotNull null).removeSuffix(".xml").removeSuffix(".XML")
                                    n to Templates.info(n, contentResolver.openInputStream(f.uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() })
                                } catch (e: Exception) {
                                    null
                                }
                            }.sortedBy { it.first.lowercase() }.toMap(LinkedHashMap())
                        if (selectedTemplate.isNotBlank() && !templates.containsKey(selectedTemplate)) selectedTemplate = ""
                        syncTemplateAssets(selectedTemplate)
                        watchedStamp = watchStamp(xml.uri)
                        try {
                            val l = buildLayout(text)
                            layout = l
                            xmlError = null
                            xmlLoadedAt = System.currentTimeMillis()
                            val images = l.elements.count { it.kind == "image" }
                            xmlNote = "${l.width.toInt()}×${l.height.toInt()} · ${l.datas.size} options · $images images"
                            loaded = true
                        } catch (e: LayoutParser.ParseError) {
                            val lines = (mergedXml ?: text).lines()
                            val from = maxOf(1, e.line - 2)
                            val ctx = (from..minOf(lines.size, e.line)).joinToString("\n") { n ->
                                "${n.toString().padEnd(5)}${lines[n - 1].trim().take(60)}"
                            }
                            xmlError = ParseInfo(e.line, e.message ?: "problem", ctx)
                        }
                    }
                    val dev = if (dir.name.equals(DEV_FOLDER, true)) dir else files.firstOrNull { it.isDirectory && it.name.equals(DEV_FOLDER, true) }
                    if (dev != null) {
                        runOnUiThread { setStatus("Syncing $DEV_FOLDER…") }
                        val target = File(filesDir, "devassets")
                        val n = syncDevFolder(dev, target)
                        zipFile = target
                        zipName = "$DEV_FOLDER (unzipped)"
                        zipOk = true
                        zipInfo = "Dev assets · $n files, read from the folder"
                    } else if (zip == null) {
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
                    if (st != null) renderState(st) else if (zipOk) renderCard(readAutosave())
                }
            }
        }
    }

    /** Starts editing a card (a saved set, or null for the layout's defaults) and draws it. */
    private fun renderCard(set: CardSet?, newCard: Boolean = false) {
        if (newCard) setCurSet(null)
        val st = CardState.from(set)
        if (set != null) {
            val wanted = st.values["template"] ?: ""
            if (wanted != selectedTemplate && (wanted.isBlank() || templates.containsKey(wanted))) {
                selectedTemplate = wanted
                prefs.edit().putString("template", wanted).apply()
                syncTemplateAssets(wanted)
                try {
                    layout = buildLayout(baseXml)
                } catch (e: LayoutParser.ParseError) {
                }
                runOnUiThread { refreshLayoutsPage() }
            }
        } else if (selectedTemplate.isNotBlank()) {
            st.values["template"] = selectedTemplate
        }
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
                assets = Assets(z, st.files, cacheDir, selectedTemplate.ifBlank { null }, templateAssetDir())
                val r = Renderer(l, ctx, assets, st.values)
                r.hideLayerId = editingLayerId
                var aboveBmp: Bitmap? = null
                val editing = editingLayerId?.let { id -> ImageLayers.read(st.values).firstOrNull { it.id == id } }
                if (editing != null) {
                    r.splitZ = editing.z
                    r.part = 1
                    val r2 = Renderer(l, ctx, assets, st.values)
                    r2.hideLayerId = editingLayerId
                    r2.splitZ = editing.z
                    r2.part = 2
                    aboveBmp = r2.render(2f)
                }
                bmp = r.render(2f)
                editAbove = aboveBmp
                val ms = SystemClock.elapsedRealtime() - t0
                val blank = assets.missing.filter { isBlankOnPurpose(it) }
                val real = assets.missing.filter { !isBlankOnPurpose(it) }
                msg.append(st.values["cardname"] ?: "Card").append(" · ${r.drawn} elements in $ms ms")
                if (selectedTemplate.isNotBlank()) msg.append(" · template: $selectedTemplate")
                templateNotes.forEach { n -> msg.append("\n").append(n) }
                val undef = undefinedNames.filter { !st.values.containsKey(it) }
                if (undef.isNotEmpty()) {
                    msg.append("\n${undef.size} name(s) used but never defined: ").append(undef.take(6).joinToString(", "))
                    if (undef.size > 6) msg.append(" +${undef.size - 6} more")
                }
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
                refreshDock()
                if (bmp != null) {
                    lastBmp = bmp
                    cardView.bitmap = bmp
                    cardView.editor?.above = editAbove
                    cardView.invalidate()
                    hits = newHits
                    prefs.edit().putBoolean("restoring", false).apply()
                }
                thread { autosave() }
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
        private const val DEV_FOLDER = "TurtleBox dev"
        private const val REQ_LAYER = 1007
        private const val REQ_SETUP = 1008
        private const val REQ_SETS_TREE = 1006
        private const val REQ_TREE = 1001
        private const val REQ_SET = 1002
        private const val REQ_IMAGE = 1003
        private const val REQ_SAVE = 1004
        private const val REQ_PNG = 1005
    }
}
