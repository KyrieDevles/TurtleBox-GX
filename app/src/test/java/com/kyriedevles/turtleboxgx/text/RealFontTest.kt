package com.kyriedevles.turtleboxgx.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Line breaks with the real MatrixBook advances and kerning (from src/test/resources/matrixbook-metrics.txt), checked
 * against real Photoshop output: the user's S10 proxy template PSD (settings and drawn pixels read on 2026-11-27) and the
 * Endymion proxy image. See the Project doc claude/psd-template-findings.md.
 */
private object MatrixBook {
    val adv = HashMap<Int, Int>()
    val kern = HashMap<Long, Int>()
    var ascent = 925
    var descent = 208
    var yMax = 925
    var yMin = -208

    init {
        val stream = MatrixBook::class.java.classLoader!!.getResourceAsStream("matrixbook-metrics.txt")
            ?: error("matrixbook-metrics.txt missing from test resources")
        stream.bufferedReader().forEachLine { line ->
            val p = line.trim().split(' ')
            when (p[0]) {
                "A" -> adv[p[1].toInt()] = p[2].toInt()
                "K" -> kern[(p[1].toLong() shl 32) or p[2].toLong()] = p[3].toInt()
                "V" -> { ascent = p[1].toInt(); descent = -p[2].toInt(); yMax = p[3].toInt(); yMin = p[4].toInt() }
            }
        }
    }
}

/** Width of text in MatrixBook: advances, kern pairs inside the run, and tracking after every character. */
private class MatrixMetrics(val size: Float, val scaleX: Float) : Metrics {
    override fun advance(text: String, from: Int, to: Int, style: Int, letterEm: Float): Float {
        var w = 0f
        for (i in from until to) {
            val c = text[i].code
            if (c == 0x2060) continue
            w += (MatrixBook.adv[c] ?: MatrixBook.adv['?'.code]!!) / 1000f * size * scaleX + letterEm * size * scaleX
            if (i + 1 < to) w += (MatrixBook.kern[(c.toLong() shl 32) or text[i + 1].code.toLong()] ?: 0) / 1000f * size * scaleX
        }
        return w
    }
    override val top = -MatrixBook.yMax / 1000f * size
    override val ascent = -MatrixBook.ascent / 1000f * size
    override val descent = MatrixBook.descent / 1000f * size
    override val bottom = -MatrixBook.yMin / 1000f * size
    // Height of "d" in MatrixBook (760 units), which is where Photoshop puts the first baseline.
    override val firstLineAscent = 0.760f * size
}

private val matrix = MetricsFactory { s, x -> MatrixMetrics(s, x) }

private fun lines(text: String, size: Float, boxWidth: Float, opt: LayoutOptions): List<String> {
    val core = LayoutCore(Source(text, IntArray(text.length)), matrix, boxWidth, 100000f, opt, { 0f }, size)
    val r = core.place(size, 1f)
    return r.lines.map { text.substring(it.start, it.end).trim() }
}

class RealFontTest {
    // Photoshop's Justification defaults, which every body-text box of the template uses.
    private fun photoshop(trackingThousandths: Int, composer: Int = 0, min: Float = 80f, desired: Float = 100f, max: Float = 133f) =
        LayoutOptions(letterSpace = trackingThousandths / 1000f, wordSpace = desired, wordSpaceMin = min, wordSpaceMax = max, composer = composer)

    private val spellEffect = "Fusion Summon 1 \"Ancient Gear\" Fusion Monster from your Extra Deck, using monsters from your " +
        "hand or field as Fusion Material. If you use \"Ancient Gear Golem\" or \"Ancient Gear Golem - Ultimate Pound\" you " +
        "control as Fusion Material, you can also use monsters from your Deck as material."

    private val pendulumLarge = "You can reduce the battle damage you take from an attack involving a Pendulum Monster you " +
        "control to 0. During your End Phase: You can destroy this card, and if you        do, add 1 Pendulum Monster with " +
        "1500 or less ATK from your Deck to your hand. You can only use each Pendulum Effect of \"Odd-Eyes Pendulum Dragon\" " +
        "once per turn."

    @Test
    fun templateSpellEffectSqueezesSpacesLikePhotoshop() {
        // Template layer: 24.55 pt, tracking +7, box 681.14 wide. Photoshop's last line ends at 390.9 (measured).
        val ps = lines(spellEffect, 24.55359f, 681.13831f, photoshop(7))
        assertEquals(5, ps.size)
        assertEquals("monsters from your Deck as material.", ps.last())
        // Line 4 ends with "use" only because its spaces may shrink a little: at 100% it is within a hair of the box
        // width, so the test without squeezing is left to the Pendulum case below, where the difference is clear.
        assertEquals("Ultimate Pound\" you control as Fusion Material, you can also use", ps[3])
    }

    @Test
    fun templatePendulumLargeHasSixLinesLikePhotoshop() {
        // Template layer: 24.58 pt, tracking -25, box 557.55 wide. Photoshop drew 6 lines, the last ending at 518.7.
        val ps = lines(pendulumLarge, 24.57978f, 557.54724f, photoshop(-25))
        assertEquals(6, ps.size)
        assertEquals("Effect of \"Odd-Eyes Pendulum Dragon\" once per turn.", ps.last())
        val old = lines(pendulumLarge, 24.57978f, 557.54724f, photoshop(-25, min = 100f))
        assertEquals(7, old.size)
    }

    private val endymionEffect = listOf(
        "Once per turn, when a Spell/Trap Card or effect is activated (Quick Effect): You can return 1 card",
        "you control with a Spell Counter to the hand, and if you do, negate the activation, and if you",
        "do that, destroy it. Then, you can place the same number of Spell Counters on this card that",
        "the returned card had. While this card has a Spell Counter, your opponent cannot target it with",
        "card effects, also it cannot be destroyed by your opponent’s card effects. When this card with",
        "a Spell Counter is destroyed by battle: You can add 1 Normal Spell from your Deck to your hand.",
    )
    private val endymionPendulum = listOf(
        "You can remove 6 Spell Counters from your field; Special Summon this",
        "card, then destroy cards on the field up to the number of cards you",
        "control that can have a Spell Counter, and if you do, place Spell Counters",
        "on this card equal to the number of cards destroyed. You can only use",
        "this effect of \"Endymion, the Mighty Master of Magic\" once per turn.",
    )

    // Measured from the proxy image: 16.36 px per em across (effect) and 17.63 (pendulum), in boxes 683.05 and 555.8 wide.
    // Word spacing as the proxy's spaces suggest. Desired 170-175% (Minimum 80% and Maximum 133% of it, the same ratios as
    // Photoshop's defaults) gives both blocks exactly at the measured widths; 150-165% gives only the pendulum.
    private val endymionOpt = photoshop(0, composer = 1, min = 136f, desired = 170f, max = 226f)

    @Test
    fun everyLineComposerReproducesTheEndymionProxyEffect() {
        assertEquals(endymionEffect, lines(endymionEffect.joinToString(" "), 16.36f, 683.05f, endymionOpt))
    }

    @Test
    fun everyLineComposerReproducesTheEndymionProxyPendulum() {
        assertEquals(endymionPendulum, lines(endymionPendulum.joinToString(" "), 17.63f, 555.8f, endymionOpt))
    }

    @Test
    fun singleLineComposerCannotReproduceTheEndymionEffect() {
        // Line 1 is wider than line 2 plus "do", so filling one line at a time always pulls "do" up.
        val single = lines(endymionEffect.joinToString(" "), 16.36f, 683.05f, photoshop(0, composer = 0, min = 136f, desired = 170f, max = 226f))
        assertTrue(single != endymionEffect)
    }

    @Test
    fun firstBaselineSitsWherePhotoshopPutsIt() {
        // Template effect text: 24.55 pt with leading 25.04 and 27.04 (two layers). Photoshop's first baseline measured
        // 0.765 em below the box top in both, whatever the leading; within 0.01 em is within the measurement error.
        for (lead in listOf(25.04466f, 27.04f)) {
            val opt = LayoutOptions(leadingPx = lead)
            val r = LayoutCore(Source("Once per turn", IntArray(13)), matrix, 681f, 1000f, opt, { 0f }, 24.55359f).place(24.55359f, 1f)
            assertEquals(0.765f * 24.55359f, r.lines[0].baseline, 0.01f * 24.55359f)
            assertEquals(lead, r.lineHeight, 0.001f)
        }
    }

    @Test
    fun noLineIsWiderThanTheBoxWithEitherComposer() {
        val text = endymionEffect.joinToString(" ") + "\n" + spellEffect + "\n" + pendulumLarge
        for (composer in 0..1) for (min in listOf(60f, 80f, 100f)) for (w in listOf(300f, 457f, 683f)) {
            val opt = photoshop(0, composer = composer, min = min)
            val core = LayoutCore(Source(text, IntArray(text.length)), matrix, w, 100000f, opt, { 0f }, 16f)
            val r = core.place(16f, 1f)
            for (line in r.lines) assertTrue("line ${line.index} is ${line.width} wide in a $w box", line.width <= w + 0.05f)
        }
    }
}
