package com.kyriedevles.turtleboxgx.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake font that honours <size> and <scale> runs: each character is half its run's size wide. Ascent 0.8, descent 0.2 em. */
private class SizedMetrics(val size: Float, val scaleX: Float, val extras: List<RunExtra>) : Metrics {
    override fun advance(text: String, from: Int, to: Int, style: Int, letterEm: Float): Float {
        val ex = extraOf(style, extras)
        val s = ex?.sizeAt(size) ?: size
        val sx = scaleX * ((ex?.scale ?: 0f).takeIf { it > 0f }?.div(100f) ?: 1f)
        return (to - from) * (s * 0.5f * sx + letterEm * s)
    }
    override val top get() = -size
    override val ascent get() = -size * 0.8f
    override val descent get() = size * 0.2f
    override val bottom get() = size * 0.25f
    override val emSize get() = size
}

class RunTagsTest {
    private fun core(raw: String, width: Float, height: Float, opt: LayoutOptions): LayoutCore {
        val r = parseRich(raw)
        val spec: (Int) -> RunSpec? = { st ->
            extraOf(st, r.extras)?.let { e -> if (e.sizePx > 0f || e.sizePct > 0f || e.tracking != null || e.leading > 0f) RunSpec(e.sizePx, e.sizePct, e.tracking?.div(1000f), e.leading) else null }
        }
        return LayoutCore(Source(r.text, r.styles, spec), { s, sx -> SizedMetrics(s, sx, r.extras) }, width, height, opt, { 0f }, 10f)
    }

    @Test
    fun parsesCharacterTags() {
        val r = parseRich("a<size=12>b<size=50%>c</size>d</size><tracking=-20>e</tracking><leading=11.5>f</leading><scale=90>g</scale><baseline=1.5>h</baseline>")
        assertEquals("abcdefgh", r.text)
        fun ex(i: Int) = extraOf(r.styles[i], r.extras)
        assertNull(ex(0))
        assertEquals(12f, ex(1)!!.sizePx, 0f)
        assertEquals(50f, ex(2)!!.sizePct, 0f)
        assertEquals(12f, ex(3)!!.sizePx, 0f)
        assertEquals(-20f, ex(4)!!.tracking!!, 0f)
        assertEquals(11.5f, ex(5)!!.leading, 0f)
        assertEquals(90f, ex(6)!!.scale, 0f)
        assertEquals(1.5f, ex(7)!!.baseline, 0f)
    }

    @Test
    fun badValuesStayAsText() {
        assertEquals("<size=big>x", parseRich("<size=big>x").text)
        assertEquals("<leading=0>x", parseRich("<leading=0>x").text)
        assertEquals("X<SIZE=10>Y", uppercaseOutsideTags("x<SIZE=10>y").let { it.replace("<SIZE=10>", "<SIZE=10>") })
        assertEquals("AB<size=10>C</size>", uppercaseOutsideTags("ab<size=10>c</size>"))
    }

    @Test
    fun runSizeWidensItsWords() {
        // Box size 10: each letter 5 px. "aa" at size 20 is 20 px, so "aa bb" = 20 + space 5 + 10.
        val r = core("<size=20>aa</size> bb", 200f, 100f, LayoutOptions(fontScale = 100f)).layout()
        assertEquals(1, r.lines.size)
        assertEquals(25f, r.words[1].x, 0.01f)
    }

    @Test
    fun percentSizeFollowsTheBox() {
        val a = core("<size=200%>aa</size> bb", 400f, 100f, LayoutOptions(fontScale = 100f)).layout()
        val b = core("<size=200%>aa</size> bb", 400f, 100f, LayoutOptions(fontScale = 50f)).layout()
        assertEquals(25f, a.words[1].x, 0.01f)
        assertEquals(12.5f, b.words[1].x, 0.01f)
    }

    @Test
    fun runTrackingReplacesTheBoxTracking() {
        // Box tracking 0.1 em (1 px per letter at size 10); the run's 0 removes it for "aa".
        val r = core("<tracking=0>aa</tracking> bb", 200f, 100f, LayoutOptions(fontScale = 100f, letterSpace = 0.1f)).layout()
        // "aa" = 10, then the space carries the box tracking: 5 + 1.
        assertEquals(16f, r.words[1].x, 0.01f)
    }

    @Test
    fun aBigWordPushesOnlyItsLineDown() {
        // Fixed leading 12; a run with its own leading 20 on line 2 makes only that gap bigger.
        val opt = LayoutOptions(fontScale = 100f, leadingPx = 12f, photoshopFirstBaseline = true)
        val plain = core("aaaa\\nbbbb\\ncccc".replace("\\n", "\n"), 200f, 200f, opt).layout()
        val tagged = core("aaaa\nb<leading=20>bb</leading>b\ncccc", 200f, 200f, opt).layout()
        assertEquals(plain.lines[0].baseline, tagged.lines[0].baseline, 0.01f)
        assertEquals(plain.lines[1].baseline + 8f, tagged.lines[1].baseline, 0.01f)
        assertEquals(tagged.lines[1].baseline + 12f, tagged.lines[2].baseline, 0.01f)
    }

    @Test
    fun autoLeadingGrowsWithSize() {
        // Automatic leading (Leading px 0): a double-size run doubles its line's leading.
        val opt = LayoutOptions(fontScale = 100f, photoshopFirstBaseline = true)
        val plain = core("aa\nbb", 200f, 200f, opt).layout()
        val tagged = core("aa\n<size=20>bb</size>", 200f, 200f, opt).layout()
        val gap = plain.lines[1].baseline - plain.lines[0].baseline
        assertEquals(gap * 2f, tagged.lines[1].baseline - tagged.lines[0].baseline, 0.01f)
        assertTrue(tagged.textHeight > plain.textHeight)
    }

    @Test
    fun bigFirstLineSitsLower() {
        val opt = LayoutOptions(fontScale = 100f, leadingPx = 12f, photoshopFirstBaseline = true)
        val plain = core("aa\nbb", 200f, 200f, opt).layout()
        val tagged = core("<size=20>aa</size>\nbb", 200f, 200f, opt).layout()
        assertEquals(plain.lines[0].baseline * 2f, tagged.lines[0].baseline, 0.01f)
    }

    @Test
    fun fitShrinksAroundFixedRuns() {
        // A px-sized run keeps its size while the rest shrinks to fit.
        val r = core("<size=10>aaaa</size> bbbb cccc dddd", 60f, 14f, LayoutOptions(minSizePct = 10f, maxSizePct = 100f)).layout()
        assertTrue(r.size < 10f)
        assertEquals(1, r.lines.size)
    }
}
