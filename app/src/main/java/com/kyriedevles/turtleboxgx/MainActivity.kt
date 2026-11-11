package com.kyriedevles.turtleboxgx

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Phase 1a: proves the build pipeline and the "layout lives in a folder, not in the app" idea.
 * Pick the folder that holds your layout .xml and .zip; the app reads both and reports what it found.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("gx", MODE_PRIVATE) }

    private val pink = Color.parseColor("#ff4da6")
    private val pinkSoft = Color.parseColor("#ffc9e3")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
            text = "Version ${versionName()} · If you can read this, the GitHub build and install work."
            setTextColor(pinkSoft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, dp(24))
        })

        val button = Button(this).apply {
            text = "Choose layout folder"
            setAllCaps(false)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#ff6ec7"), Color.parseColor("#ff1493"), Color.parseColor("#ff00aa"))
            ).apply { cornerRadius = dp(26).toFloat() }
            setOnClickListener { pickFolder() }
        }
        root.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        )

        status = TextView(this).apply {
            text = "No folder chosen yet.\n\nPick the folder that holds yugioh-9-en.xml and yugioh-9-en.zip."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(24), 0, 0)
            setTextIsSelectable(true)
        }
        root.addView(status)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        })

        // Re-open the folder chosen last time.
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
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        startActivityForResult(intent, REQ_TREE)
    }

    @Deprecated("Fine for this small activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Some providers refuse persistence; the scan below still works this session.
        }
        prefs.edit().putString("tree", uri.toString()).apply()
        scan(uri)
    }

    private fun scan(tree: Uri) {
        status.text = "Reading folder…"
        thread {
            val sb = StringBuilder()
            try {
                val dir = DocumentFile.fromTreeUri(this, tree)
                if (dir == null || !dir.isDirectory) {
                    sb.append("Cannot open that folder.")
                } else {
                    val files = dir.listFiles()
                    sb.append("Folder: ${dir.name}\n")
                    sb.append("Items:  ${files.size}\n")
                    files.take(12).forEach { sb.append("  • ${it.name}\n") }
                    if (files.size > 12) sb.append("  … and ${files.size - 12} more\n")
                    sb.append("\n")

                    val xml = files.firstOrNull { it.isFile && it.name?.endsWith(".xml", true) == true }
                    val zip = files.firstOrNull { it.isFile && it.name?.endsWith(".zip", true) == true }

                    if (xml == null) sb.append("No .xml layout found in this folder.\n\n") else sb.append(describeXml(xml))
                    if (zip == null) sb.append("No .zip assets found in this folder.\n") else sb.append(describeZip(zip))
                }
            } catch (e: Exception) {
                sb.append("Error: ${e.javaClass.simpleName}: ${e.message}")
            }
            runOnUiThread { status.text = sb.toString() }
        }
    }

    private fun describeXml(file: DocumentFile): String {
        val sb = StringBuilder()
        sb.append("Layout: ${file.name}\n")
        val text = contentResolver.openInputStream(file.uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
            .trimStart('﻿')
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(text))
        val counts = linkedMapOf("data" to 0, "bool" to 0, "text" to 0, "image" to 0, "item" to 0)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.name
                    if (counts.containsKey(name)) counts[name] = counts.getValue(name) + 1
                }
                event = parser.next()
            }
            sb.append("  Valid XML, ${text.length / 1024} KB\n")
            counts.forEach { (k, v) -> sb.append("  <$k>: $v\n") }
        } catch (e: XmlPullParserException) {
            sb.append("  XML problem at line ${parser.lineNumber}, column ${parser.columnNumber}:\n")
            sb.append("  ${e.message}\n")
        }
        sb.append("\n")
        return sb.toString()
    }

    private fun describeZip(file: DocumentFile): String {
        val sb = StringBuilder()
        sb.append("Assets: ${file.name}\n")
        var entries = 0
        var images = 0
        var fonts = 0
        ZipInputStream(BufferedInputStream(contentResolver.openInputStream(file.uri)!!)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries++
                    val n = entry.name.lowercase()
                    if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")) images++
                    if (n.endsWith(".ttf") || n.endsWith(".otf")) fonts++
                }
                entry = zis.nextEntry
            }
        }
        sb.append("  Files:  $entries\n")
        sb.append("  Images: $images\n")
        sb.append("  Fonts:  $fonts\n")
        return sb.toString()
    }

    companion object {
        private const val REQ_TREE = 1001
    }
}
