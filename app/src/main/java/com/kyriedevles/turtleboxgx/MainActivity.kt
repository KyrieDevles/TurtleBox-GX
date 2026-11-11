package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlin.concurrent.thread

/**
 * Phase 1b: pick the folder with your layout .xml and .zip, then draw a card from it.
 * "Draw default card" uses the layout's own default values; "Open card set" draws a saved .msoe-set.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var drawButton: Button
    private lateinit var setButton: Button
    private val prefs by lazy { getSharedPreferences("gx", MODE_PRIVATE) }

    private lateinit var saveButton: Button
    private var layout: Layout? = null
    private var zipFile: File? = null
    private var state: CardState? = null
    private var hits: List<Hit> = emptyList()
    private var pendingImageKey: String? = null

    private val pink = Color.parseColor("#ff4da6")
    private val pinkSoft = Color.parseColor("#ffc9e3")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun hotButton(label: String, filled: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setAllCaps(false)
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        if (filled) {
            setTextColor(Color.BLACK)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#ff6ec7"), Color.parseColor("#ff1493"), Color.parseColor("#ff00aa")),
            ).apply { cornerRadius = dp(24).toFloat() }
        } else {
            setTextColor(Color.parseColor("#ff6ec7"))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(2), Color.parseColor("#ff1493"))
                cornerRadius = dp(24).toFloat()
            }
        }
        setOnClickListener { onClick() }
    }

    private fun addButton(root: LinearLayout, b: Button) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        lp.topMargin = dp(10)
        root.addView(b, lp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "TurtleBox GX"
            setTextColor(pink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Version ${versionName()} · phase 1b: draw a card from your layout"
            setTextColor(pinkSoft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, dp(14))
        })

        addButton(root, hotButton("Choose layout folder", true) { pickFolder() })
        drawButton = hotButton("Draw default card", false) { renderCard(null) }.apply { isEnabled = false }
        addButton(root, drawButton)
        setButton = hotButton("Open card set (.msoe-set)", false) { pickSet() }.apply { isEnabled = false }
        addButton(root, setButton)
        saveButton = hotButton("Save card set", false) { saveSet() }.apply { isEnabled = false }
        addButton(root, saveButton)

        status = TextView(this).apply {
            text = "No folder chosen yet.\n\nPick the folder that holds yugioh-9-en.xml and yugioh-9-en.zip."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(18), 0, dp(12))
            setTextIsSelectable(true)
        }
        root.addView(status)

        preview = ImageView(this).apply {
            adjustViewBounds = true
            setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_UP && v.width > 0) onTap(ev.x / v.width, ev.y / v.height)
                true
            }
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        })

        val saved = prefs.getString("tree", null)
        if (saved != null) {
            val uri = Uri.parse(saved)
            val stillAllowed = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (stillAllowed) scan(uri)
        }
    }

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

    private fun saveSet() {
        val st = state ?: return
        val name = (st.values["cardname"] ?: "card").replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "card" }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "$name.msoe-set")
        startActivityForResult(intent, REQ_SAVE)
    }

    /** A tap on the card preview, as fractions of its width and height: open the topmost tappable element. */
    private fun onTap(fx: Float, fy: Float) {
        val l = layout ?: return
        val st = state ?: return
        val x = fx * l.width
        val y = fy * l.height
        val hit = hits.lastOrNull { it.rect.contains(x, y) } ?: return
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
        ).open(hit.click)
    }

    /** Artwork can be huge; keep it at most 2000 px on its long side and store it as PNG. */
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
        startActivityForResult(intent, REQ_SET)
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
                    val png = shrinkToPng(bytes)
                    val def = l.datas[key]?.default ?: ""
                    var fileName = Ctx(l, st.values).sub(def).ifBlank { "$key.png" }
                    if (!fileName.contains('.')) fileName += ".png"
                    st.files[fileName] = png
                    st.values[key] = fileName
                    renderState(st)
                } catch (e: Exception) {
                    status.text = "Could not use that image:\n${e.javaClass.simpleName}: ${e.message}"
                }
            }
            REQ_SAVE -> {
                val l = layout ?: return
                val st = state ?: return
                try {
                    contentResolver.openOutputStream(uri)!!.use { it.write(SetWriter.build(l, st)) }
                    status.text = "Saved ${st.values["cardname"] ?: "card"}."
                } catch (e: Exception) {
                    status.text = "Could not save:\n${e.javaClass.simpleName}: ${e.message}"
                }
            }
            REQ_SET -> {
                status.text = "Reading card set…"
                thread {
                    try {
                        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        renderCard(CardSet.read(bytes))
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "Could not read that card set:\n${e.javaClass.simpleName}: ${e.message}" }
                    }
                }
            }
        }
    }

    private fun scan(tree: Uri) {
        status.text = "Reading folder…"
        drawButton.isEnabled = false
        setButton.isEnabled = false
        thread {
            val sb = StringBuilder()
            var ok = false
            try {
                val dir = DocumentFile.fromTreeUri(this, tree)
                if (dir == null || !dir.isDirectory) {
                    sb.append("Cannot open that folder.")
                } else {
                    val files = dir.listFiles()
                    sb.append("Folder: ${dir.name} (${files.size} items)\n\n")
                    val xml = files.firstOrNull { it.isFile && it.name?.endsWith(".xml", true) == true }
                    val zip = files.firstOrNull { it.isFile && it.name?.endsWith(".zip", true) == true }
                    if (xml == null) sb.append("No .xml layout found in this folder.\n")
                    if (zip == null) sb.append("No .zip assets found in this folder.\n")
                    if (xml != null) {
                        val text = contentResolver.openInputStream(xml.uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        try {
                            val l = LayoutParser.parse(text)
                            layout = l
                            val images = l.elements.count { it.kind == "image" }
                            sb.append("Layout ${xml.name}: ${l.width.toInt()}×${l.height.toInt()}, ")
                            sb.append("${l.datas.size} options, ${l.bools.size} conditions,\n")
                            sb.append("  $images images, ${l.elements.size - images} text boxes\n")
                            ok = true
                        } catch (e: LayoutParser.ParseError) {
                            sb.append("Layout problem at line ${e.line}, column ${e.column}:\n  ${e.message}\n")
                        }
                    }
                    if (zip != null) {
                        val target = File(filesDir, "layout.zip")
                        val stamp = "${zip.length()}:${zip.lastModified()}"
                        if (!target.exists() || prefs.getString("zipStamp", "") != stamp) {
                            runOnUiThread { status.text = "Copying ${zip.name} (${zip.length() / 1024 / 1024} MB)…" }
                            contentResolver.openInputStream(zip.uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                            prefs.edit().putString("zipStamp", stamp).apply()
                        }
                        zipFile = target
                        sb.append("Assets ${zip.name}: ${target.length() / 1024 / 1024} MB ready\n")
                    } else {
                        ok = false
                    }
                }
            } catch (e: Exception) {
                sb.append("Error: ${e.javaClass.simpleName}: ${e.message}")
                ok = false
            }
            runOnUiThread {
                status.text = sb.toString()
                drawButton.isEnabled = ok
                setButton.isEnabled = ok
            }
        }
    }

    /** Starts editing a card (a saved set, or null for the layout's defaults) and draws it. */
    private fun renderCard(set: CardSet?) {
        val st = CardState.from(set)
        state = st
        renderState(st)
    }

    /** Draws the card being edited on a background thread and shows it. */
    private fun renderState(st: CardState) {
        val l = layout ?: return
        val z = zipFile
        runOnUiThread { status.text = "Drawing…" }
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
                msg.append("Drew ${r.drawn} elements in $ms ms.\n")
                msg.append("Card: ${st.values["cardname"] ?: "(default)"} · tap the card to edit\n")
                newHits = r.hits
                r.notes.forEach { msg.append(it).append('\n') }
                val blank = assets.missing.filter { isBlankOnPurpose(it) }
                val real = assets.missing.filter { !isBlankOnPurpose(it) }
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
                status.text = msg.toString()
                if (bmp != null) {
                    preview.setImageBitmap(bmp)
                    hits = newHits
                    saveButton.isEnabled = true
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
    }
}
