package com.kyriedevles.turtleboxgx.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichMarkupTest {
    private fun extraAt(r: RichText, i: Int): RunExtra? = extraOf(r.styles[i], r.extras)

    @Test
    fun textWithoutTagsIsUntouched() {
        val r = parseRich("Once per turn, you can draw 1 card.")
        assertEquals("Once per turn, you can draw 1 card.", r.text)
        assertTrue(r.styles.all { it == 0 })
    }

    @Test
    fun boldAndItalicKeepTheirOldBits() {
        val r = parseRich("a<b>b</b><i>c</i><b><i>d</i></b>")
        assertEquals("abcd", r.text)
        assertEquals(listOf(0, 1, 2, 3), r.styles.map { it and 3 })
        assertTrue(r.styles.all { extraOf(it, r.extras) == null })
    }

    @Test
    fun underlineStrikeScriptAndSmallCapsAreRead() {
        val r = parseRich("<u>a</u><s>b</s><sup>c</sup><sub>d</sub><sc>e</sc>f")
        assertEquals("abcdef", r.text)
        assertTrue(extraAt(r, 0)!!.underline)
        assertTrue(extraAt(r, 1)!!.strike)
        assertEquals(1, extraAt(r, 2)!!.script)
        assertEquals(2, extraAt(r, 3)!!.script)
        assertTrue(extraAt(r, 4)!!.smallCaps)
        assertNull(extraAt(r, 5))
    }

    @Test
    fun stylesCombineAndEndWhereTheirTagEnds() {
        val r = parseRich("<b><u>ab</u>c</b>d")
        assertEquals("abcd", r.text)
        assertEquals(1, r.styles[0] and 3)
        assertTrue(extraAt(r, 0)!!.underline)
        assertEquals(1, r.styles[2] and 3)
        assertNull(extraAt(r, 2))
        assertEquals(0, r.styles[3])
    }

    @Test
    fun anUnclosedTagStaysOnToTheEnd() {
        val r = parseRich("a<u>bc\nde")
        assertEquals("abc\nde", r.text)
        assertNull(extraAt(r, 0))
        for (i in 1 until r.text.length) assertTrue("char $i", extraAt(r, i)!!.underline)
    }

    @Test
    fun tagNamesAreNotCaseSensitive() {
        val r = parseRich("<U>a</U><COLOR=#FF0000>b</Color>")
        assertEquals("ab", r.text)
        assertTrue(extraAt(r, 0)!!.underline)
        assertEquals(0xFFFF0000.toInt(), extraAt(r, 1)!!.color)
    }

    @Test
    fun colourTagsNestAndCloseTheInnermost() {
        val r = parseRich("<color=#00ff00>a<color=#0000ff>b</color>c</color>d")
        assertEquals("abcd", r.text)
        assertEquals(0xFF00FF00.toInt(), extraAt(r, 0)!!.color)
        assertEquals(0xFF0000FF.toInt(), extraAt(r, 1)!!.color)
        assertEquals(0xFF00FF00.toInt(), extraAt(r, 2)!!.color)
        assertNull(extraAt(r, 3))
    }

    @Test
    fun hexColoursComeInThreeForms() {
        assertEquals(0xFFFF0000.toInt(), parseHexColor("#f00"))
        assertEquals(0xFF112233.toInt(), parseHexColor("#112233"))
        assertEquals(0x80112233.toInt(), parseHexColor("#80112233"))
        assertNull(parseHexColor("red"))
        assertNull(parseHexColor("#12"))
        assertNull(parseHexColor("#12345g"))
    }

    @Test
    fun aBadColourOrAnUnknownTagStaysVisibleAsText() {
        val bad = parseRich("<color=zzz>a")
        assertEquals("<color=zzz>a", bad.text)
        val other = parseRich("<Monster Effect> and <3")
        assertEquals("<Monster Effect> and <3", other.text)
        val open = parseRich("1 < 2 and no end")
        assertEquals("1 < 2 and no end", open.text)
    }

    @Test
    fun aFontTagKeepsItsNameWithoutWordJoiners() {
        val r = parseRich("<font=font/RO⁠ rush.otf>ab</font>c")
        assertEquals("abc", r.text)
        assertEquals("font/RO rush.otf", extraAt(r, 0)!!.font)
        assertNull(extraAt(r, 2))
    }

    @Test
    fun equalExtrasShareOneStyleId() {
        val r = parseRich("<u>a</u>b<u>c</u>")
        assertEquals(r.styles[0], r.styles[2])
        assertNotEquals(r.styles[0], r.styles[1])
        assertEquals(2, r.extras.size)
        assertFalse(r.extras[0].underline)
    }

    @Test
    fun allCapsLeavesTagsAlone() {
        assertEquals("A <b>X</b> <font=ab/cd.ttf>Y</font> <color=#ff0000>Z", uppercaseOutsideTags("a <b>x</b> <font=ab/cd.ttf>y</font> <color=#ff0000>z"))
        assertEquals("PLAIN TEXT", uppercaseOutsideTags("plain text"))
        // Not a known tag, so it is ordinary text and is upper-cased.
        assertEquals("<MONSTER EFFECT>", uppercaseOutsideTags("<Monster Effect>"))
    }
}
