package com.kyriedevles.turtleboxgx.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagEditTest {
    @Test
    fun setsATagOnARange() {
        assertEquals("ab<size=14>cd</size>ef", TagEdit.set("abcdef", 2, 4, "size", "14"))
    }

    @Test
    fun splitsAndMergesTags() {
        // Changing the middle of a tagged run splits it; setting it back merges it again.
        val split = TagEdit.set("<size=14>abcdef</size>", 2, 4, "size", "10")
        assertEquals("<size=14>ab</size><size=10>cd</size><size=14>ef</size>", split)
        assertEquals("<size=14>abcdef</size>", TagEdit.set(split, 2, 4, "size", "14"))
    }

    @Test
    fun removesATag() {
        assertEquals("abcdef", TagEdit.set("ab<tracking=-20>cd</tracking>ef", 0, 6, "tracking", null))
    }

    @Test
    fun keepsOtherTagsAndText() {
        val raw = "<b>Quick</b> <Monster Effect>: draw<br>1"
        val out = TagEdit.set(raw, 0, 5, "color", "#ff0000")
        assertEquals("<color=#ff0000><b>Quick</b></color> <Monster Effect>: draw<br>1", out)
        assertEquals(TagEdit.plain(raw), TagEdit.plain(out))
        // The engine reads the same letters and styles either way.
        assertEquals(parseRich(raw.replace("<br>", " ")).text, parseRich(out.replace("<br>", " ")).text)
    }

    @Test
    fun flagsAndScript() {
        assertEquals("a<b>bc</b>d", TagEdit.set("abcd", 1, 3, "b", ""))
        assertEquals("a<sup>bc</sup>d", TagEdit.set("abcd", 1, 3, "script", "sup"))
    }

    @Test
    fun readsValues() {
        val raw = "ab<size=14>cd</size>ef"
        assertTrue((TagEdit.valueOf(raw, 2, 4, "size") as TagEdit.Value.Same).v == "14")
        assertTrue(TagEdit.valueOf(raw, 1, 4, "size") is TagEdit.Value.Mixed)
        assertTrue((TagEdit.valueOf(raw, 0, 2, "size") as TagEdit.Value.Same).v == null)
        // A space at the edge of a highlight does not make it mixed.
        assertTrue((TagEdit.valueOf("a <size=14>cd</size>", 1, 4, "size") as TagEdit.Value.Same).v == "14")
    }

    @Test
    fun mapsToRawOffsets() {
        val raw = "ab<size=14>cd</size>e<br>f"
        assertEquals(11..12, TagEdit.rawRange(raw, 2, 4))
        assertEquals(2, TagEdit.visibleAt(raw, 11))
        assertEquals("abcde f", TagEdit.plain(raw))
        assertEquals(21..24, TagEdit.rawRange(raw, 5, 6))
    }

    @Test
    fun alignsWithTheEngineText() {
        // keepWord adds a word joiner after "/", and [[ ]] is removed by the engine.
        val map = TagEdit.align("[[a/b]]", "A/⁠B")
        assertEquals(listOf(2, 3, -1, 4), map.toList())
    }

    @Test
    fun editsTheNewTags() {
        assertEquals("a<kern=-20>b</kern>c", TagEdit.set("abc", 1, 2, "kern", "-20"))
        assertEquals("<caps>ab</caps>c", TagEdit.set("abc", 0, 2, "caps", ""))
        assertEquals("a<sup>b</sup>c", TagEdit.set("abc", 1, 2, "script", "sup"))
        assertEquals("abc", TagEdit.plain("<fauxitalic>a</fauxitalic><vscale=120>b</vscale><fauxbold=30>c</fauxbold>"))
    }

    @Test
    fun paragraphSettingsGoOnTheParagraphsTouched() {
        val raw = "one\ntwo\nthree"
        val r = TagEdit.setPara(raw, 5, 6, "align", "right")
        assertEquals("one\n<para align=right>two\nthree", r)
        assertEquals("one\ntwo\nthree", TagEdit.plain(r))
        val r2 = TagEdit.set(r, 2, 6, "para.left", "10")
        assertEquals("<para left=10>one\n<para align=right left=10>two\nthree", r2)
        assertTrue(TagEdit.valueOf(r2, 5, 6, "para.align").let { it is TagEdit.Value.Same && it.v == "right" })
        assertEquals("one\ntwo\nthree", TagEdit.plain(TagEdit.setPara(TagEdit.setPara(r2, 0, 1, "left", null), 4, 5, "align", null).let { TagEdit.setPara(it, 4, 5, "left", null) }))
        // Other tags still work next to it.
        assertEquals("one\n<para align=right><b>two</b>\nthree", TagEdit.set(r, 4, 7, "b", ""))
    }
}
