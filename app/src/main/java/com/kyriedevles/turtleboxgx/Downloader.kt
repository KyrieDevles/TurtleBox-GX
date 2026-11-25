package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Lists the files on the repo's "Assets" (base layout) and "Templates" (DLC) releases and downloads the ones the user ticks
 * into the chosen folder. Template files go into the folder's `templates` subfolder.
 */
class Downloader(private val activity: Activity, private val tree: Uri, private val onDone: () -> Unit) {

    private class Remote(val name: String, val size: Long, val url: String, val templates: Boolean) {
        var state = "new"
    }

    private val repo = "KyrieDevles/TurtleBox-GX"
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()

    private fun size(b: Long) = if (b >= 1_000_000) String.format("%.1f MB", b / 1_000_000.0) else String.format("%.0f KB", b / 1000.0)

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("User-Agent", "TurtleBox-GX")
        setRequestProperty("Accept", "application/vnd.github+json")
        connectTimeout = 15000
        readTimeout = 30000
    }

    fun show() {
        val wait = ThemedBuilder(activity).setTitle("Checking GitHub").setMessage("Looking for layout files…").setCancelable(false).show()
        thread {
            val found = ArrayList<Remote>()
            var error: String? = null
            try {
                val dir = DocumentFile.fromTreeUri(activity, tree) ?: throw IllegalStateException("Cannot open that folder.")
                val tdir = dir.findFile("templates")
                for ((tag, isTemplates) in listOf("Assets" to false, "Templates" to true)) {
                    try {
                        val c = open("https://api.github.com/repos/$repo/releases/tags/$tag")
                        if (c.responseCode != 200) continue
                        val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("assets")
                        for (i in 0 until arr.length()) {
                            val a = arr.getJSONObject(i)
                            val n = a.getString("name")
                            if (!n.endsWith(".xml") && !n.endsWith(".zip")) continue
                            val r = Remote(n, a.getLong("size"), a.getString("browser_download_url"), isTemplates)
                            val have = (if (isTemplates) tdir else dir)?.findFile(n)
                            r.state = when {
                                have == null -> "new"
                                have.length() == r.size -> "installed"
                                else -> "different"
                            }
                            found.add(r)
                        }
                    } catch (e: java.io.IOException) {
                        throw e
                    }
                }
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
        val labels = files.map {
            val where = if (it.templates) "templates/" else ""
            val st = when (it.state) {
                "installed" -> "  ·  already here"
                "different" -> "  ·  differs, replaces yours"
                else -> ""
            }
            "$where${it.name}  ·  ${size(it.size)}$st"
        }.toTypedArray()
        // Base layout files that are missing or different start ticked; optional templates start ticked only when they differ.
        val checked = BooleanArray(files.size) { val f = files[it]; if (f.templates) f.state == "different" else f.state != "installed" }
        ThemedBuilder(activity).setTitle("Download layout files")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Download") { _, _ ->
                val picked = files.filterIndexed { i, _ -> checked[i] }
                if (picked.isEmpty()) toast("Nothing ticked.") else download(picked)
            }
            .setNegativeButton("Cancel", null).show()
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
            .setNegativeButton("Cancel") { _, _ -> cancel.set(true) }.show()
        thread {
            val notes = ArrayList<String>()
            var failed: String? = null
            try {
                val dir = DocumentFile.fromTreeUri(activity, tree) ?: throw IllegalStateException("Cannot open that folder.")
                val tdir = dir.findFile("templates") ?: dir.createDirectory("templates") ?: throw IllegalStateException("Could not create the templates folder.")
                for ((n, r) in picked.withIndex()) {
                    if (cancel.get()) break
                    val target = if (r.templates) tdir else dir
                    val existing = target.findFile(r.name)
                    val f = existing ?: target.createFile(if (r.name.endsWith(".xml")) "text/xml" else "application/zip", r.name)
                        ?: throw IllegalStateException("Could not create ${r.name}.")
                    val c = (URL(r.url).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("User-Agent", "TurtleBox-GX")
                        connectTimeout = 15000
                        readTimeout = 30000
                    }
                    if (c.responseCode != 200) throw IllegalStateException("${r.name}: server said ${c.responseCode}.")
                    var done = 0L
                    c.inputStream.use { input ->
                        (activity.contentResolver.openOutputStream(f.uri, "wt") ?: throw IllegalStateException("Could not write ${r.name}.")).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var lastUi = 0L
                            while (!cancel.get()) {
                                val k = input.read(buf)
                                if (k < 0) break
                                out.write(buf, 0, k)
                                done += k
                                val now = System.currentTimeMillis()
                                if (now - lastUi > 200) {
                                    lastUi = now
                                    val frac = if (r.size > 0) (done * 1000 / r.size).toInt() else 0
                                    activity.runOnUiThread {
                                        text.text = "${n + 1} of ${picked.size}: ${r.name}\n${size(done)} of ${size(r.size)}"
                                        bar.progress = frac.coerceIn(0, 1000)
                                    }
                                }
                            }
                        }
                    }
                    if (cancel.get()) {
                        f.delete()
                        notes.add("cancelled")
                        break
                    }
                    if (r.size > 0 && done != r.size) {
                        f.delete()
                        throw IllegalStateException("${r.name} came down incomplete (${size(done)} of ${size(r.size)}). Try again.")
                    }
                    notes.add("got ${r.name}")
                }
            } catch (e: SecurityException) {
                failed = "this folder was opened read-only. Choose the folder again from Set up to allow writing."
            } catch (e: Throwable) {
                failed = e.message ?: e.javaClass.simpleName
            }
            activity.runOnUiThread {
                dlg.dismiss()
                if (failed != null) toast("Download failed: $failed") else toast(notes.joinToString(" · "))
                onDone()
            }
        }
    }
}
