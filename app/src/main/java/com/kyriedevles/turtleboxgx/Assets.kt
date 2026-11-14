package com.kyriedevles.turtleboxgx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** A saved card: its values (`<key>/<value>` pairs) and the files stored beside them (the artwork). */
class CardSet(val values: Map<String, String>, val files: Map<String, ByteArray>) {
    companion object {
        fun read(bytes: ByteArray): CardSet {
            val files = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) files[e.name] = zis.readBytes()
                    e = zis.nextEntry
                }
            }
            val setBytes = files.remove("set") ?: throw IllegalArgumentException("This file has no \"set\" entry.")
            val values = LinkedHashMap<String, String>()
            val p = XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(StringReader(String(setBytes, Charsets.UTF_8).trimStart('﻿')))
            var key: String? = null
            var inData = false
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "data" -> {
                            inData = true
                            key = null
                        }
                        "key" -> if (inData) key = p.nextText()
                        "value" -> if (inData) {
                            val v = p.nextText()
                            key?.let { values[it] = v }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && p.name == "data") {
                    inData = false
                }
                event = p.next()
            }
            return CardSet(values, files)
        }
    }
}

/**
 * Finds images and fonts: first in the card set (its own artwork), then in the layout zip.
 * Names are matched exactly, then ignoring upper/lower case. Names that are not found are remembered in [missing].
 */
class Assets(private val layoutZip: File?, private val setFiles: Map<String, ByteArray>, private val cacheDir: File) {
    private val zip: ZipFile? = layoutZip?.takeIf { it.isFile }?.let { ZipFile(it) }
    private val dirIndex = HashMap<String, File>()
    private val zipIndex = HashMap<String, String>()
    private val setIndex = HashMap<String, String>()
    private val fonts = HashMap<String, Typeface?>()
    val missing = LinkedHashSet<String>()

    init {
        zip?.entries()?.let { en ->
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (!e.isDirectory) zipIndex[e.name.lowercase()] = e.name
            }
        }
        setFiles.keys.forEach { setIndex[it.lowercase()] = it }
        layoutZip?.takeIf { it.isDirectory }?.let { root ->
            root.walkTopDown().filter { it.isFile }.forEach { f -> dirIndex[f.relativeTo(root).path.replace('\\', '/').lowercase()] = f }
        }
    }

    private fun clean(name: String): String = name.trim().replace('\\', '/').removePrefix("./").trimStart('/')

    fun bytes(rawName: String): ByteArray? {
        val name = clean(rawName)
        if (name.isEmpty()) return null
        setFiles[name]?.let { return it }
        setIndex[name.lowercase()]?.let { return setFiles[it] }
        dirIndex[name.lowercase()]?.let { return it.readBytes() }
        val z = zip ?: return null
        val actual = zipIndex[name.lowercase()] ?: return null
        val entry = z.getEntry(actual) ?: return null
        return z.getInputStream(entry).use { it.readBytes() }
    }

    fun bitmap(name: String): Bitmap? {
        val b = bytes(name)
        if (b == null) {
            if (name.isNotBlank()) missing.add(name)
            return null
        }
        val bmp = BitmapFactory.decodeByteArray(b, 0, b.size)
        if (bmp == null) missing.add("$name (cannot decode)")
        return bmp
    }

    fun font(name: String): Typeface? {
        if (fonts.containsKey(name)) return fonts[name]
        var tf: Typeface? = null
        val b = bytes(name)
        if (b != null) {
            try {
                val dir = File(cacheDir, "fonts").apply { mkdirs() }
                val f = File(dir, "${name.hashCode()}_${b.size}.${name.substringAfterLast('.', "ttf")}")
                if (!f.exists()) f.writeBytes(b)
                tf = Typeface.createFromFile(f)
            } catch (e: Exception) {
                missing.add("$name (cannot load font)")
            }
        } else if (name.isNotBlank()) {
            missing.add(name)
        }
        fonts[name] = tf
        return tf
    }

    fun close() {
        try {
            zip?.close()
        } catch (e: Exception) {
        }
    }
}
