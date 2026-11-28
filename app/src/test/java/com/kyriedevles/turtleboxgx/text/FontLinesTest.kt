package com.kyriedevles.turtleboxgx.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FontLinesTest {
    /** A minimal font file with head, OS/2 and post tables. */
    private fun font(upm: Int, strikePos: Int, strikeSize: Int, ulPos: Int, ulThick: Int): ByteArray {
        val tables = listOf("head" to 54, "OS/2" to 96, "post" to 32)
        var off = 12 + tables.size * 16
        val b = ByteArray(off + tables.sumOf { it.second })
        fun w16(o: Int, v: Int) { b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte() }
        fun w32(o: Int, v: Int) { w16(o, v ushr 16); w16(o + 2, v and 0xffff) }
        w16(4, tables.size)
        val at = HashMap<String, Int>()
        for ((i, t) in tables.withIndex()) {
            val r = 12 + i * 16
            t.first.toByteArray(Charsets.ISO_8859_1).copyInto(b, r)
            w32(r + 8, off); w32(r + 12, t.second)
            at[t.first] = off
            off += t.second
        }
        w16(at["head"]!! + 18, upm)
        w16(at["OS/2"]!! + 26, strikeSize); w16(at["OS/2"]!! + 28, strikePos)
        w16(at["post"]!! + 8, ulPos); w16(at["post"]!! + 10, ulThick)
        return b
    }

    @Test
    fun readsTheModdedNameFontsValues() {
        // MatrixSmallCapsRegularModded.otf: strikeout 258 / 49, underline 0 / 0 (broken), 1200 units per em.
        val f = FontLines.parse(font(1200, 258, 49, 0, 0))!!
        assertEquals(258f / 1200f, f.strikePos!!, 1e-6f)
        assertEquals(49f / 1200f, f.strikeSize!!, 1e-6f)
        assertNull(f.underlinePos)
    }

    @Test
    fun readsUnderlineBelowTheBaseline() {
        val f = FontLines.parse(font(1200, 311, 60, -83, 42))!!
        assertEquals(-83f / 1200f, f.underlinePos!!, 1e-6f)
        assertEquals(42f / 1200f, f.underlineSize!!, 1e-6f)
    }

    @Test
    fun notAFont() {
        assertNull(FontLines.parse(ByteArray(5)))
    }
}
