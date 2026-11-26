package com.kyriedevles.turtleboxgx.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Fake font: every character is half the font size wide, bold is 20% wider, tracking adds em * size per character. */
private class FakeMetrics(val size: Float, val scaleX: Float) : Metrics {
    override fun advance(text: String, from: Int, to: Int, style: Int, letterEm: Float): Float {
        var n = 0
        for (i in from until to) if (text[i] != '⁠') n++
        val bold = if ((style and 1) != 0) 1.2f else 1f
        return n * (size * 0.5f * scaleX * bold + letterEm * size)
    }
    override val top = -size * 1.0f
    override val ascent = -size * 0.9f
    override val descent = size * 0.2f
    override val bottom = size * 0.3f
}

private val fake = MetricsFactory { s, x -> FakeMetrics(s, x) }

private fun source(t: String) = Source(t, IntArray(t.length))

private fun core(
    t: String,
    w: Float,
    h: Float = 10000f,
    opt: LayoutOptions = LayoutOptions(),
    base: Float = 20f,
    align: Int = 0,
): LayoutCore = LayoutCore(source(t), fake, w, h, opt, { 0f }, base).also { it.align = align }

private fun right(r: LayoutResult, p: Placed, text: String): Float {
    val m = FakeMetrics(r.size, r.scaleX)
    return p.x + m.advance(text, p.start, p.end, 0, 0f)
}

class LayoutCoreTest {
    private val words = listOf("Once", "per", "turn,", "you", "can", "Tribute", "1", "monster", "you", "control;", "Special", "Summon", "1", "Fiend-Type", "monster", "from", "your", "Deck.", "A/B", "testing")

    private fun randomText(rnd: Random, n: Int): String {
        val sb = StringBuilder()
        for (i in 0 until n) {
            if (i > 0) sb.append(if (rnd.nextInt(12) == 0) "\n" else " ")
            sb.append(words[rnd.nextInt(words.size)])
        }
        return sb.toString()
    }

    @Test
    fun noLineIsWiderThanTheBoxAtAnyWordSpacing() {
        val rnd = Random(7)
        for (ws in 30..320 step 10) {
            for (round in 0 until 20) {
                val t = randomText(rnd, 5 + rnd.nextInt(40))
                val r = core(t, 300f, opt = LayoutOptions(wordSpace = ws.toFloat())).place(20f, 1f)
                for (p in r.words) {
                    val e = right(r, p, t)
                    assertTrue("word ends at $e past 300 (ws=$ws, text=$t)", e <= 300.5f)
                }
                assertTrue("widest ${r.widest} (ws=$ws)", r.widest <= 300.5f)
            }
        }
    }

    @Test
    fun everyCharacterIsPlacedOnceInOrder() {
        val rnd = Random(3)
        for (round in 0 until 50) {
            val t = randomText(rnd, 30)
            val r = core(t, 220f, opt = LayoutOptions(wordSpace = 180f)).place(20f, 1f)
            val joined = r.words.joinToString("") { t.substring(it.start, it.end) }
            val expected = t.replace(" ", "").replace("\n", "")
            assertEquals(expected, joined)
        }
    }

    @Test
    fun widerWordSpacingNeverGivesFewerLines() {
        val t = words.joinToString(" ")
        var last = 0
        for (ws in listOf(50f, 100f, 150f, 200f, 300f)) {
            val n = core(t, 260f, opt = LayoutOptions(wordSpace = ws)).place(20f, 1f).lines.size
            assertTrue("lines $n at $ws, before $last", n >= last)
            last = n
        }
    }

    @Test
    fun fullLinesAreStretchedToTheBoxWidth() {
        val t = words.joinToString(" ")
        val r = core(t, 260f).place(20f, 1f)
        assertTrue(r.lines.size > 2)
        for (li in 0 until r.lines.size - 1) {
            val line = r.words.filter { it.line == li }
            val end = right(r, line.last(), t)
            assertEquals("line $li", 260f, end, 0.6f)
        }
    }

    @Test
    fun rightAlignedLinesEndAtTheBox() {
        val r = core("Hello world and more words here", 200f, align = 2).place(20f, 1f)
        for (li in 0 until r.lines.size) {
            val line = r.words.filter { it.line == li }
            assertEquals(200f, right(r, line.last(), "Hello world and more words here"), 0.6f)
        }
    }

    @Test
    fun aWordLongerThanTheBoxIsCutNotOverflowed() {
        val t = "short supercalifragilisticexpialidocious end"
        val r = core(t, 120f).place(20f, 1f)
        for (p in r.words) assertTrue(right(r, p, t) <= 120.5f)
        assertEquals(t.replace(" ", ""), r.words.joinToString("") { t.substring(it.start, it.end) })
    }

    @Test
    fun paragraphBreaksAndBlankLinesAreKept() {
        assertEquals(2, core("a\nb", 100f).place(20f, 1f).lines.size)
        assertEquals(3, core("a\n\nb", 100f).place(20f, 1f).lines.size)
        assertEquals(2, core("a\n", 100f).place(20f, 1f).lines.size)
        assertEquals(1, core("", 100f).place(20f, 1f).lines.size)
    }

    @Test
    fun aWordJoinerStopsTheBreakAfterASlash() {
        val t = "xx ab/⁠cd"
        val r = core(t, 70f).place(20f, 1f)
        assertEquals(2, r.lines.size)
        val second = r.words.filter { it.line == 1 }
        assertEquals(1, second.size)
        assertEquals("ab/⁠cd", t.substring(second[0].start, second[0].end))
    }

    @Test
    fun theFitShrinksTheTextUntilItIsTallEnoughForTheBox() {
        val t = (words + words + words).joinToString(" ")
        val c = core(t, 300f, h = 200f, base = 30f)
        val r = c.layout()
        assertTrue("size ${r.size}", r.size < 30f)
        assertTrue("height ${r.textHeight}", r.textHeight <= 200f + 0.01f)
        for (p in r.words) assertTrue(right(r, p, t) <= 300.5f)
    }

    @Test
    fun theFitKeepsTheBaseSizeWhenTheTextAlreadyFits() {
        val r = core("Short text.", 300f, h = 200f, base = 30f).layout()
        assertEquals(30f, r.size, 0.01f)
        assertEquals(1f, r.scaleX, 0.001f)
    }

    @Test
    fun theFitHoldsAtExtremeWordSpacing() {
        val t = (words + words).joinToString(" ")
        for (ws in listOf(170f, 240f, 400f)) {
            val r = core(t, 300f, h = 260f, base = 30f, opt = LayoutOptions(wordSpace = ws)).layout()
            for (p in r.words) assertTrue("ws=$ws", right(r, p, t) <= 300.5f)
            assertTrue("ws=$ws height ${r.textHeight}", r.textHeight <= 260.01f)
        }
    }

    @Test
    fun lastLineStretchClosesTheGapToTheBox() {
        val t = words.joinToString(" ")
        val r = core(t, 260f, opt = LayoutOptions(lastStretch = 100f)).place(20f, 1f)
        val line = r.words.filter { it.line == r.lines.size - 1 }
        val m = FakeMetrics(r.size, r.scaleX)
        val em = r.lines.last().letterExtraEm
        val end = line.last().x + m.advance(t, line.last().start, line.last().end, 0, em)
        assertEquals(260f, end, 1.0f)
    }

    @Test
    fun horizontalScaleWrapsAtTheScaledWidth() {
        val t = words.joinToString(" ")
        val plain = core(t, 300f).place(20f, 1f)
        val wide = core(t, 300f, opt = LayoutOptions(hScale = 150f)).place(20f, 1f)
        assertTrue(wide.lines.size >= plain.lines.size)
        assertEquals(200f, wide.wrapWidth, 0.01f)
    }

    @Test
    fun aForcedBreakEndsTheLineButStillJustifiesIt() {
        val t = "aa bb\u2028cc dd ee"
        val r = core(t, 200f).place(20f, 1f)
        assertEquals(2, r.lines.size)
        val first = r.words.filter { it.line == 0 }
        assertEquals("aa", t.substring(first[0].start, first[0].end))
        assertEquals("bb", t.substring(first[1].start, first[1].end))
        assertEquals(200f, right(r, first.last(), t), 0.6f)
        val second = r.words.filter { it.line == 1 }
        assertEquals("cc", t.substring(second[0].start, second[0].end))
    }

    @Test
    fun forcedBreaksReproduceChosenLinesAtAnyWordSpacing() {
        val t = "Once per turn,\u2028when a Spell/Trap\u2028Card or effect"
        for (ws in listOf(60f, 100f, 200f)) {
            val r = core(t, 400f, opt = LayoutOptions(wordSpace = ws)).place(20f, 1f)
            assertEquals(3, r.lines.size)
            for (p in r.words) assertTrue(right(r, p, t) <= 400.5f)
        }
    }

    private fun fixedCore(t: String, w: Float, opt: LayoutOptions = LayoutOptions()): Pair<LayoutCore, FixedResult> {
        val fr = extractFixedLines(t, IntArray(t.length))
        return LayoutCore(Source(fr.text, fr.styles), fake, w, 10000f, opt, { 0f }, 20f, fr.spans) to fr
    }

    @Test
    fun wholeLineBracketsAreRemovedAndRecorded() {
        val fr = extractFixedLines("[[aa bb]][[cc]]\n[[dd]]", IntArray(22))
        assertEquals("aa bbcc\ndd", fr.text)
        assertEquals(3, fr.spans.size)
        assertEquals("aa bb", fr.text.substring(fr.spans[0].start, fr.spans[0].end))
        assertEquals("cc", fr.text.substring(fr.spans[1].start, fr.spans[1].end))
        assertEquals("dd", fr.text.substring(fr.spans[2].start, fr.spans[2].end))
    }

    @Test
    fun bracketsInsideAParagraphStayOrdinaryText() {
        val t = "see [[x]] more [[y]] text"
        val fr = extractFixedLines(t, IntArray(t.length))
        assertEquals(t, fr.text)
        assertEquals(0, fr.spans.size)
    }

    @Test
    fun aShortFixedLineStretchesAndALongOneSqueezes() {
        // Box 200 wide, 10 px per character at size 20.
        val t = "[[aa bb]][[aaaa bbbb cccc dddd eeee ffff]]"
        val (c, fr) = fixedCore(t, 200f)
        val r = c.place(20f, 1f)
        assertEquals(2, r.lines.size)
        val first = r.words.filter { it.line == 0 }
        assertEquals(200f, right(r, first.last(), fr.text), 0.6f)
        assertEquals(1f, r.lines[0].scaleX, 0.0001f)
        val second = r.lines[1]
        assertTrue("scale ${second.scaleX}", second.scaleX < 1f)
        val line2 = r.words.filter { it.line == 1 }
        assertEquals(200f, right(r, line2.last(), fr.text) * second.scaleX, 0.6f)
    }

    @Test
    fun fixedLinesKeepTheProxyLineBreaks() {
        val t = "[[Once per turn, when a Spell/Trap Card or effect is activated]]\n[[you control with a Spell Counter to the hand, and if you do]]\n[[do that, destroy it.]]"
        val (c, _) = fixedCore(t, 360f, LayoutOptions(wordSpace = 100f))
        val r = c.place(20f, 1f)
        assertEquals(3, r.lines.size)
        for (l in r.lines) assertTrue(l.width <= 360.5f)
    }

    @Test
    fun aFixedLineNeverWrapsAtAnyWordSpacing() {
        val t = "[[Once per turn, when a Spell/Trap Card or effect is activated (Quick Effect)]]\n[[you can return 1 card you control with a Spell Counter]]"
        for (ws in listOf(50f, 100f, 250f)) {
            val (c, _) = fixedCore(t, 360f, LayoutOptions(wordSpace = ws))
            val r = c.place(20f, 1f)
            assertEquals(2, r.lines.size)
            for (l in r.lines) assertTrue(l.width <= 360.5f)
        }
    }
}
