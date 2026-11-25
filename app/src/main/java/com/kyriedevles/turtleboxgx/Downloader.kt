package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Gets the layout files from the repo's "Assets" (base layout) and "Templates" (DLC) releases into the chosen folder.
 * Template files go into the folder's `templates` subfolder.
 *
 * Each finished download is remembered (size and release time) so the app can tell an update from a file the user edited.
 * Downloads go to `name.part` first, so an interrupted one carries on where it stopped.
 */
class Downloader(private val activity: Activity, private val tree: Uri, private val onDone: () -> Unit) {

    class Remote(val name: String, val size: Long, val url: String, val updated: String, val templates: Boolean) {
        /** new, update, current, edited (yours differs, no newer version), conflict (newer version and yours is edited). */
        var state = "new"
        var title = ""
        var desc = ""
        var by = ""
        var ver = ""
    }

    /** One thing to get: the base layout, or one template (its xml and zip together). */
    class Item(val key: String, val templates: Boolean, val files: List<Remote>) {
        var title = key
        var desc = ""
        var by = ""
        var ver = ""
        val size get() = files.sumOf { it.size }
        val state: String
            get() = when {
                files.all { it.state == "new" } -> "new"
                files.any { it.state == "update" || it.state == "conflict" || it.state == "new" } -> "update"
                files.any { it.state == "edited" } -> "edited"
                else -> "current"
            }
        /** The files that still need fetching. */
        val todo get() = files.filter { it.state == "new" || it.state == "update" || it.state == "conflict" }
    }

    companion object {
        private const val REPO = "KyrieDevles/TurtleBox-GX"

        fun prefs(a: Activity) = a.getSharedPreferences("turtlebox_dl", Activity.MODE_PRIVATE)

        fun sizeText(b: Long) = if (b >= 1_000_000) String.format("%.1f MB", b / 1_000_000.0) else String.format("%.0f KB", b / 1000.0)

        private fun open(url: String, json: Boolean): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "TurtleBox-GX")
            if (json) setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 15000
            readTimeout = 30000
        }

        /** Blocking: reads both releases and works out each file's state against the folder. Throws on network trouble. */
        fun fetch(activity: Activity, tree: Uri, withInfo: Boolean): List<Remote> {
            val dir = DocumentFile.fromTreeUri(activity, tree) ?: throw IllegalStateException("Cannot open that folder.")
            val tdir = dir.findFile("templates")
            val sp = prefs(activity)
            val out = ArrayList<Remote>()
            for ((tag, isTemplates) in listOf("Assets" to false, "Templates" to true)) {
                val c = open("https://api.github.com/repos/$REPO/releases/tags/$tag", true)
                if (c.responseCode == 404) continue
                if (c.responseCode != 200) throw IllegalStateException("GitHub said ${c.responseCode}. Try again later.")
                val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("assets")
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    val n = a.getString("name")
                    if (!n.endsWith(".xml") && !n.endsWith(".zip")) continue
                    val r = Remote(n, a.getLong("size"), a.getString("browser_download_url"), a.optString("updated_at", ""), isTemplates)
                    val have = (if (isTemplates) tdir else dir)?.findFile(n)
                    if (have == null) {
                        r.state = "new"
                    } else {
                        val local = have.length()
                        val rec = sp.getString("rec:$n", null)?.split("|")
                        val outdated: Boolean
                        val edited: Boolean
                        if (rec != null && rec.size == 2) {
                            edited = local != (rec[0].toLongOrNull() ?: -1L)
                            outdated = rec[0] != r.size.toString() || rec[1] != r.updated
                        } else {
                            // Not downloaded by this app: the same size counts as the same file, anything else may be the user's own.
                            edited = local != r.size
                            outdated = local != r.size
                        }
                        r.state = when {
                            !outdated && !edited -> "current"
                            !outdated -> "edited"
                            !edited -> "update"
                            else -> "conflict"
                        }
                    }
                    if (withInfo && isTemplates && n.endsWith(".xml") && r.size < 300_000) {
                        try {
                            val p = android.util.Xml.newPullParser()
                            open(r.url, false).inputStream.use { st ->
                                p.setInput(st, null)
                                var ev = p.eventType
                                while (ev != XmlPullParser.START_TAG && ev != XmlPullParser.END_DOCUMENT) ev = p.next()
                                if (ev == XmlPullParser.START_TAG && p.name == "template") {
                                    r.title = p.getAttributeValue(null, "label") ?: ""
                                    r.desc = p.getAttributeValue(null, "description") ?: ""
                                    r.by = p.getAttributeValue(null, "author") ?: ""
                                    r.ver = p.getAttributeValue(null, "version") ?: ""
                                }
                            }
                        } catch (e: Exception) {
                            // No details for this one; the file name still shows.
                        }
                    }
                    out.add(r)
                }
            }
            return out
        }

        /** The base layout as one item, and each template (xml and zip with the same name) as another. */
        fun group(remotes: List<Remote>): List<Item> {
            val items = ArrayList<Item>()
            val base = remotes.filter { !it.templates }
            if (base.isNotEmpty()) items.add(Item("layout", false, base).apply { title = "Layout"; desc = "XML and pictures" })
            remotes.filter { it.templates }.groupBy { it.name.substringBeforeLast('.') }.forEach { (k, fs) ->
                val x = fs.firstOrNull { it.name.endsWith(".xml") }
                items.add(Item(k, true, fs.sortedBy { it.name }).apply {
                    title = x?.title?.ifBlank { null } ?: k
                    desc = x?.desc ?: ""
                    by = x?.by ?: ""
                    ver = x?.ver ?: ""
                })
            }
            return items
        }

        /** How many items have an update, or are new since the list was last opened. */
        fun count(activity: Activity, items: List<Item>): Int {
            val seen = prefs(activity).getStringSet("seen", emptySet()) ?: emptySet()
            return items.count { it.state == "update" || (it.state == "new" && it.key !in seen) }
        }

        fun markSeen(activity: Activity, items: List<Item>) {
            prefs(activity).edit().putStringSet("seen", items.map { it.key }.toSet()).apply()
        }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()

    private fun copyFile(src: DocumentFile, dst: DocumentFile) {
        val cr = activity.contentResolver
        (cr.openInputStream(src.uri) ?: throw IllegalStateException("Could not read ${src.name}.")).use { input ->
            (cr.openOutputStream(dst.uri, "wt") ?: throw IllegalStateException("Could not write ${dst.name}.")).use { out ->
                input.copyTo(out, 64 * 1024)
            }
        }
    }

    /**
     * Downloads the files one after another on a background thread. `progress` gets the bytes done so far for a file;
     * `end` gets what happened (and the reason, if it stopped early). Setting `cancel` pauses and keeps the part file.
     */
    fun transfer(files: List<Remote>, cancel: AtomicBoolean, progress: (Remote, Long) -> Unit, end: (List<String>, String?) -> Unit) {
        thread {
            val notes = ArrayList<String>()
            var failed: String? = null
            val sp = prefs(activity)
            try {
                val dir = DocumentFile.fromTreeUri(activity, tree) ?: throw IllegalStateException("Cannot open that folder.")
                val tdir = dir.findFile("templates") ?: dir.createDirectory("templates") ?: throw IllegalStateException("Could not create the templates folder.")
                for (r in files) {
                    if (cancel.get()) break
                    val target = if (r.templates) tdir else dir
                    val partName = r.name + ".part"
                    var part = target.findFile(partName)
                    var from = 0L
                    // A part file only counts if it is from the same release and shorter than the whole file.
                    if (part != null && sp.getString("part:${r.name}", "") == r.updated && part.length() in 1 until r.size) from = part.length()
                    else { part?.delete(); part = null }
                    if (part == null) part = target.createFile("application/octet-stream", partName) ?: throw IllegalStateException("Could not create ${r.name}.")
                    sp.edit().putString("part:${r.name}", r.updated).apply()

                    var c = open(r.url, false)
                    if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
                    var code = c.responseCode
                    val append = from > 0 && code == 206
                    if (from > 0 && code != 206) {
                        // The server sent the whole file; start the part file again.
                        from = 0
                        if (code != 200) { c.disconnect(); c = open(r.url, false); code = c.responseCode }
                    }
                    if (code != 200 && code != 206) throw IllegalStateException("${r.name}: server said $code.")
                    var done = from
                    val out = try {
                        activity.contentResolver.openOutputStream(part.uri, if (append) "wa" else "wt")
                    } catch (e: Exception) {
                        null
                    } ?: throw IllegalStateException("Could not write ${r.name}.")
                    var lastUi = 0L
                    try {
                        c.inputStream.use { input ->
                            out.use { o ->
                                val buf = ByteArray(64 * 1024)
                                while (!cancel.get()) {
                                    val k = input.read(buf)
                                    if (k < 0) break
                                    o.write(buf, 0, k)
                                    done += k
                                    val now = System.currentTimeMillis()
                                    if (now - lastUi > 200) {
                                        lastUi = now
                                        progress(r, done)
                                    }
                                }
                            }
                        }
                    } catch (e: java.io.IOException) {
                        throw IllegalStateException("${r.name}: the connection dropped at ${sizeText(done)} of ${sizeText(r.size)}. Download again to carry on.")
                    }
                    if (cancel.get()) {
                        notes.add("paused ${r.name} at ${sizeText(done)}")
                        break
                    }
                    if (r.size > 0 && part.length() != r.size) {
                        throw IllegalStateException("${r.name} came down incomplete (${sizeText(part.length())} of ${sizeText(r.size)}). Download again to carry on.")
                    }
                    // Finished: keep a copy of the user's edited file, then put the new one in place.
                    val existing = target.findFile(r.name)
                    if (existing != null) {
                        if (r.state == "conflict" || r.state == "edited") {
                            val bak = target.findFile(r.name + ".bak") ?: target.createFile("application/octet-stream", r.name + ".bak")
                            if (bak != null) copyFile(existing, bak)
                            notes.add("kept your copy as ${r.name}.bak")
                        }
                        existing.delete()
                    }
                    if (!part.renameTo(r.name)) {
                        val nf = target.createFile(if (r.name.endsWith(".xml")) "text/xml" else "application/zip", r.name)
                            ?: throw IllegalStateException("Could not create ${r.name}.")
                        copyFile(part, nf)
                        part.delete()
                    }
                    sp.edit().putString("rec:${r.name}", "${r.size}|${r.updated}").remove("part:${r.name}").apply()
                    progress(r, r.size)
                    notes.add("got ${r.name}")
                }
            } catch (e: SecurityException) {
                failed = "this folder was opened read-only. Choose the folder again from Set up to allow writing."
            } catch (e: Throwable) {
                failed = e.message ?: e.javaClass.simpleName
            }
            end(notes, failed)
        }
    }

    /** A small progress box for downloads started from the Layouts page. */
    fun downloadWithDialog(files: List<Remote>, then: () -> Unit) {
        if (files.isEmpty()) { toast("Nothing to download."); return }
        val cancel = AtomicBoolean(false)
        val text = TextView(activity).apply { setTextColor(Theme.text); setTextSize(14f) }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        val dlg = ThemedBuilder(activity).setTitle("Downloading").setView(box).setCancelable(false)
            .setNegativeButton("Pause") { _, _ -> cancel.set(true) }.show()
        transfer(files, cancel, { r, d ->
            activity.runOnUiThread {
                text.text = "${r.name}\n${sizeText(d)} of ${sizeText(r.size)}"
                bar.progress = if (r.size > 0) (d * 1000 / r.size).toInt().coerceIn(0, 1000) else 0
            }
        }) { notes, failed ->
            activity.runOnUiThread {
                dlg.dismiss()
                if (failed != null) toast("Download stopped: $failed") else toast(notes.joinToString(" · "))
                then()
            }
        }
    }

    // ---- the Get files page ----

    private class RowRef(val item: Item, val sub: TextView, val bar: ProgressBar, val button: TextView)

    fun show() {
        val dlg = Dialog(activity, android.R.style.Theme_Material_NoActionBar)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.ground)
            fitsSystemWindows = true
        }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(10))
        }
        head.addView(TextView(activity).apply {
            text = "GET FILES"
            setTextColor(Theme.pink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.25f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(activity).apply {
            text = "DONE"
            gravity = Gravity.CENTER
            setTextColor(Theme.pink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setPadding(dp(16), 0, dp(16), 0)
            background = Theme.choice(false, dp(8).toFloat(), dp(1).toFloat() * 1.5f, dp(4).toFloat())
            setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val sv = ScrollView(activity).apply { addView(list) }
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dlg.setContentView(root)

        var busy = false
        var cancel = AtomicBoolean(false)
        val refs = ArrayList<RowRef>()
        val doneBytes = HashMap<String, Long>()
        var active: List<Remote> = emptyList()

        fun message(text: String) {
            list.removeAllViews()
            list.addView(TextView(activity).apply {
                this.text = text
                setTextColor(Theme.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(4), dp(12), dp(4), 0)
            })
        }

        fun updateRows() {
            for (ref in refs) {
                val mine = ref.item.files.filter { f -> active.any { it.name == f.name } }
                if (mine.isEmpty()) continue
                val total = mine.sumOf { it.size }
                val done = mine.sumOf { doneBytes[it.name] ?: 0L }
                val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                ref.bar.visibility = View.VISIBLE
                ref.bar.progress = pct * 10
                ref.button.text = "$pct%  ⏸"
            }
        }

        lateinit var load: () -> Unit

        fun start(files: List<Remote>) {
            if (files.isEmpty()) return
            if (busy) { toast("Wait for the current download, or pause it."); return }
            busy = true
            cancel = AtomicBoolean(false)
            active = files
            doneBytes.clear()
            updateRows()
            transfer(files, cancel, { r, d -> activity.runOnUiThread { doneBytes[r.name] = d; updateRows() } }) { notes, failed ->
                activity.runOnUiThread {
                    busy = false
                    active = emptyList()
                    if (failed != null) toast("Download stopped: $failed") else toast(notes.joinToString(" · "))
                    if (dlg.isShowing) load()
                }
            }
        }

        fun buttonStyle(b: TextView, kind: String, text: String) {
            b.text = text
            b.typeface = Typeface.DEFAULT_BOLD
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            b.letterSpacing = 0.08f
            b.gravity = Gravity.CENTER
            b.setPadding(dp(10), 0, dp(10), 0)
            when (kind) {
                "on" -> { b.setTextColor(Theme.onPink); b.background = Theme.choice(true, dp(8).toFloat(), 0f, dp(4).toFloat()) }
                "ok" -> { b.setTextColor(Color.parseColor("#5fe08a")); b.background = Theme.choice(false, dp(8).toFloat(), dp(1).toFloat() * 1.5f, dp(3).toFloat()) }
                else -> { b.setTextColor(Theme.pink); b.background = Theme.choice(false, dp(8).toFloat(), dp(1).toFloat() * 1.5f, dp(3).toFloat()) }
            }
        }

        fun build(items: List<Item>) {
            markSeen(activity, items)
            list.removeAllViews()
            refs.clear()
            val todo = items.filter { it.state == "new" || it.state == "update" }
            if (todo.isNotEmpty()) {
                val all = todo.flatMap { it.todo }
                val b = TextView(activity)
                buttonStyle(b, "on", "GET EVERYTHING NEW  ·  ${sizeText(all.sumOf { it.size })}")
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                b.setOnClickListener { start(all) }
                list.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6); bottomMargin = dp(6) })
            }
            fun heading(t: String) = list.addView(TextView(activity).apply {
                text = t.uppercase()
                setTextColor(Theme.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                letterSpacing = 0.12f
                setPadding(dp(4), 0, 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16); bottomMargin = dp(8) })
            fun row(item: Item) {
                val r = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.choice(false, dp(12).toFloat(), dp(1).toFloat() * 1.5f, dp(4).toFloat())
                    setPadding(dp(14), dp(12), dp(12), dp(12))
                }
                val mid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                mid.addView(TextView(activity).apply {
                    text = item.title
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                })
                val status = when (item.state) {
                    "update" -> "Update available"
                    "edited" -> "Edited by you"
                    "new" -> ""
                    else -> "Up to date"
                }
                val sub = TextView(activity).apply {
                    text = listOf(item.desc, sizeText(item.size), status).filter { it.isNotBlank() }.joinToString(" · ")
                    setTextColor(if (item.state == "update") Theme.pink else Theme.muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                }
                mid.addView(sub)
                val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
                mid.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(6) })
                r.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
                val btn = TextView(activity)
                when (item.state) {
                    "new" -> buttonStyle(btn, "on", "GET")
                    "update" -> buttonStyle(btn, "on", "UPDATE")
                    else -> buttonStyle(btn, "ok", "INSTALLED")
                }
                btn.setOnClickListener {
                    if (busy && active.any { a -> item.files.any { it.name == a.name } }) cancel.set(true)
                    else if (item.state == "new" || item.state == "update") start(item.todo)
                }
                r.addView(btn, LinearLayout.LayoutParams(dp(104), dp(38)))
                list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                refs.add(RowRef(item, sub, bar, btn))
            }
            items.filter { !it.templates }.let { if (it.isNotEmpty()) { heading("Base layout"); it.forEach { i -> row(i) } } }
            items.filter { it.templates }.let { if (it.isNotEmpty()) { heading("DLC templates"); it.forEach { i -> row(i) } } }
            list.addView(TextView(activity).apply {
                text = "Checked just now. Templates you have are managed from the Layouts page."
                setTextColor(Theme.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(4), dp(14), dp(4), 0)
            })
            Theme.unclip(list)
        }

        load = {
            message("Checking GitHub…")
            thread {
                var items: List<Item> = emptyList()
                var error: String? = null
                try {
                    items = group(fetch(activity, tree, true))
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
                activity.runOnUiThread {
                    if (!dlg.isShowing) return@runOnUiThread
                    when {
                        error != null -> message("Could not reach GitHub. Check your internet connection and try again.\n\n$error")
                        items.isEmpty() -> message("No layout files were found on the releases.")
                        else -> build(items)
                    }
                }
            }
        }
        dlg.setOnDismissListener {
            cancel.set(true)
            onDone()
        }
        dlg.show()
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        load()
    }
}
