package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.net.Uri
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
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
 * Lists the files on the repo's "Assets" (base layout) and "Templates" (DLC) releases and downloads the ones the user ticks
 * into the chosen folder. Template files go into the folder's `templates` subfolder.
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
                    if (withInfo && n.endsWith(".xml") && r.size < 300_000) {
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

        /** Blocking: how many files have an update, or are new since the list was last opened. */
        fun countUpdates(activity: Activity, tree: Uri): Int {
            val seen = prefs(activity).getStringSet("seen", emptySet()) ?: emptySet()
            return fetch(activity, tree, false).count { it.state == "update" || it.state == "conflict" || (it.state == "new" && it.name !in seen) }
        }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()

    fun show() {
        val wait = ThemedBuilder(activity).setTitle("Checking GitHub").setMessage("Looking for layout files…").setCancelable(false).show()
        thread {
            var found: List<Remote> = emptyList()
            var error: String? = null
            try {
                found = fetch(activity, tree, true)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            activity.runOnUiThread {
                wait.dismiss()
                when {
                    error != null -> ThemedBuilder(activity).setTitle("Could not reach GitHub")
                        .setMessage("Check your internet connection and try again.\n\n$error").setPositiveButton("OK", null).show()
                    found.isEmpty() -> ThemedBuilder(activity).setTitle("Nothing to download")
                        .setMessage("No layout files were found on the releases.").setPositiveButton("OK", null).show()
                    else -> choose(found)
                }
            }
        }
    }

    private fun choose(files: List<Remote>) {
        prefs(activity).edit().putStringSet("seen", files.map { it.name }.toSet()).apply()
        val labels = files.map { r ->
            val sb = StringBuilder()
            sb.append(if (r.templates) "Template · " else "").append(r.title.ifBlank { r.name }).append("  ·  ").append(sizeText(r.size))
            if (r.title.isNotBlank()) sb.append("\n").append(r.name)
            if (r.desc.isNotBlank()) sb.append("\n").append(r.desc)
            val who = listOf(if (r.by.isNotBlank()) "by ${r.by}" else "", if (r.ver.isNotBlank()) "v${r.ver}" else "").filter { it.isNotBlank() }.joinToString(" · ")
            if (who.isNotBlank()) sb.append("\n").append(who)
            sb.append("\n").append(
                when (r.state) {
                    "current" -> "Already here, up to date"
                    "edited" -> "Already here. Yours is edited, so it is left alone"
                    "update" -> "Update available"
                    "conflict" -> "Newer version, but yours is edited. Yours is saved as ${r.name}.bak if you replace it"
                    else -> "Not in your folder yet"
                },
            )
            sb.toString()
        }.toTypedArray()
        // Base layout files that are missing or out of date start ticked; optional templates only when an update exists.
        val checked = BooleanArray(files.size) { val f = files[it]; if (f.state == "update") true else if (f.state == "new") !f.templates else false }
        ThemedBuilder(activity).setTitle("Download layout files")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Download") { _, _ ->
                val picked = files.filterIndexed { i, _ -> checked[i] }
                if (picked.isEmpty()) toast("Nothing ticked.") else download(picked)
            }
            .setNeutralButton("Get everything") { _, _ ->
                val picked = files.filter { it.state == "new" || it.state == "update" }
                if (picked.isEmpty()) toast("Everything is already up to date.") else download(picked)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun copyFile(src: DocumentFile, dst: DocumentFile) {
        val cr = activity.contentResolver
        (cr.openInputStream(src.uri) ?: throw IllegalStateException("Could not read ${src.name}.")).use { input ->
            (cr.openOutputStream(dst.uri, "wt") ?: throw IllegalStateException("Could not write ${dst.name}.")).use { out ->
                input.copyTo(out, 64 * 1024)
            }
        }
    }

    private fun download(picked: List<Remote>) {
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
        thread {
            val notes = ArrayList<String>()
            var failed: String? = null
            val sp = prefs(activity)
            try {
                val dir = DocumentFile.fromTreeUri(activity, tree) ?: throw IllegalStateException("Cannot open that folder.")
                val tdir = dir.findFile("templates") ?: dir.createDirectory("templates") ?: throw IllegalStateException("Could not create the templates folder.")
                for ((n, r) in picked.withIndex()) {
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
                    var append = from > 0 && code == 206
                    if (from > 0 && code != 206) {
                        // The server sent the whole file; start the part file again.
                        from = 0
                        if (code != 200) { c.disconnect(); c = open(r.url, false); code = c.responseCode }
                    }
                    if (code != 200 && code != 206) throw IllegalStateException("${r.name}: server said $code.")
                    var done = from
                    val cr = activity.contentResolver
                    val out = try {
                        cr.openOutputStream(part.uri, if (append) "wa" else "wt")
                    } catch (e: Exception) {
                        null
                    }
                    if (out == null) throw IllegalStateException("Could not write ${r.name}.")
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
                                        val frac = if (r.size > 0) (done * 1000 / r.size).toInt() else 0
                                        activity.runOnUiThread {
                                            text.text = "${n + 1} of ${picked.size}: ${r.name}\n${sizeText(done)} of ${sizeText(r.size)}" + if (append) "  (resumed)" else ""
                                            bar.progress = frac.coerceIn(0, 1000)
                                        }
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
                    notes.add("got ${r.name}")
                }
            } catch (e: SecurityException) {
                failed = "this folder was opened read-only. Choose the folder again from Set up to allow writing."
            } catch (e: Throwable) {
                failed = e.message ?: e.javaClass.simpleName
            }
            activity.runOnUiThread {
                dlg.dismiss()
                if (failed != null) toast("Download stopped: $failed") else toast(notes.joinToString(" · "))
                onDone()
            }
        }
    }
}
