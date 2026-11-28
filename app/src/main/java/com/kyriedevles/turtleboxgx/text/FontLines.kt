package com.kyriedevles.turtleboxgx.text

import java.util.WeakHashMap

/**
 * Where a font says its underline and strikethrough go, read straight from the font file (OS/2 and post tables), in
 * em. Android does not always pass these on: for some fonts it falls back to a fixed strikethrough at 0.29 em, which
 * sits at the top of small capitals. Photoshop uses the font's own values, so the engine does too.
 *
 * Positions are the top of the line, + above the baseline. A value is null when the font has none (or a zero
 * thickness, as a broken font may).
 */
data class FontLines(val strikePos: Float?, val strikeSize: Float?, val underlinePos: Float?, val underlineSize: Float?) {
    companion object {
        private val byFont = WeakHashMap<Any, FontLines>()

        /** Remembers [lines] for a loaded font object (a Typeface). */
        @Synchronized fun register(font: Any, lines: FontLines?) {
            if (lines != null) byFont[font] = lines
        }

        @Synchronized fun of(font: Any?): FontLines? = font?.let { byFont[it] }

        /** A typeface derived from [from] (bold or italic of it) uses the same values. */
        @Synchronized fun alias(font: Any?, from: Any) {
            if (font != null && !byFont.containsKey(font)) byFont[from]?.let { byFont[font] = it }
        }

        /** Reads a TrueType / OpenType file. Null when it is not one or the tables are missing. */
        fun parse(b: ByteArray): FontLines? {
            try {
                fun u16(o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
                fun s16(o: Int) = u16(o).toShort().toInt()
                fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong()
                if (b.size < 12) return null
                val n = u16(4)
                var head = -1
                var os2 = -1
                var post = -1
                for (i in 0 until n) {
                    val r = 12 + i * 16
                    if (r + 16 > b.size) return null
                    val tag = String(b, r, 4, Charsets.ISO_8859_1)
                    val off = u32(r + 8).toInt()
                    when (tag) {
                        "head" -> head = off
                        "OS/2" -> os2 = off
                        "post" -> post = off
                    }
                }
                if (head < 0 || head + 20 > b.size) return null
                val upm = u16(head + 18).toFloat().takeIf { it > 0f } ?: return null
                var sp: Float? = null
                var ss: Float? = null
                if (os2 >= 0 && os2 + 30 <= b.size) {
                    val size = s16(os2 + 26)
                    if (size > 0) { ss = size / upm; sp = s16(os2 + 28) / upm }
                }
                var up: Float? = null
                var us: Float? = null
                if (post >= 0 && post + 12 <= b.size) {
                    val th = s16(post + 10)
                    if (th > 0) { us = th / upm; up = s16(post + 8) / upm }
                }
                return FontLines(sp, ss, up, us)
            } catch (e: Exception) {
                return null
            }
        }
    }
}
