package com.kyriedevles.turtleboxgx.text

/**
 * The layout core of the new text engine. Pure Kotlin: no Android classes, so it runs in plain JVM tests.
 *
 * One rule runs through it: every width that decides a line break, a justified gap or a drawn position comes from the
 * same [Metrics.advance] call. Nothing is measured a second way, so a line can never be drawn wider than it was wrapped.
 */

/** Font measurements at one size and horizontal squeeze. Heights are in px, ascent and top are negative like Android's. */
interface Metrics {
    /** Advance of text[from, to) in one [style] (bit 0 bold, bit 1 italic) with [letterEm] tracking added after every character. */
    fun advance(text: String, from: Int, to: Int, style: Int, letterEm: Float): Float
    val top: Float
    val ascent: Float
    val descent: Float
    val bottom: Float
    // The same four numbers rounded the way Paint.getFontMetricsInt gives them. Android's StaticLayout (the old engine)
    // lays automatic-leading text out with these, so the New engine does too. They default to the exact ones.
    val lineTop: Float get() = top
    val lineAscent: Float get() = ascent
    val lineDescent: Float get() = descent
    val lineBottom: Float get() = bottom
    /**
     * How far below the box top the first baseline sits, positive px. Photoshop puts it at the height of the letter "d"
     * whatever the leading (measured on the user's template PSD, 0.765 em for MatrixBook against "d" = 0.760 em). The
     * default is the font ascent, which is what the engine used before.
     */
    val firstLineAscent: Float get() = -ascent
}

fun interface MetricsFactory {
    fun at(size: Float, scaleX: Float): Metrics
}

/** The options the layout needs, already as numbers. Zero means "not set" where it says so. Same meaning as TextOptions. */
class LayoutOptions(
    val condense: Float = 0f,
    val fitMode: Int = 1,
    val minSizePct: Float = 0f,
    val maxSizePct: Float = 0f,
    val minLines: Float = 0f,
    val justify: Float = 0f,
    val letterSpace: Float = 0f,
    val lastStretch: Float = 0f,
    val hScale: Float = 100f,
    val wordSpace: Float = 100f,
    val leadingPx: Float = 0f,
    val fontScale: Float = 0f,
    val flow: Float = 0f,
    val spacing: Float = 1f,
    /** Paragraph settings in card px (like Photoshop's Paragraph panel). First-line indent may be negative (hanging). */
    val indentLeft: Float = 0f,
    val indentRight: Float = 0f,
    val indentFirst: Float = 0f,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 0f,
    /**
     * Photoshop's Justification word spacing, in % of a normal space. [wordSpace] is Desired. Minimum is how far spaces may
     * shrink to fit a word on a justified line (0 = same as Desired, which never shrinks); Maximum is where a stretched
     * line starts to count as bad for the Every-line composer (0 = 133% of Desired). Spaces still stretch past it.
     */
    val wordSpaceMin: Float = 0f,
    val wordSpaceMax: Float = 0f,
    /** Line breaking, like Photoshop's Paragraph menu: 0 Single-line Composer, 1 Every-line Composer. */
    val composer: Int = 0,
    /**
     * First baseline where Photoshop puts it (the "d" height, [Metrics.firstLineAscent]). Off: the font ascent with
     * Leading px, the rounded font top with automatic leading, as before. Opt-in per box, because boxes laid out for the
     * old position (ATK/DEF values, small-caps fonts) would move.
     */
    val photoshopFirstBaseline: Boolean = false,
    /**
     * Justification, like Photoshop's paragraph alignment buttons. -1: the old rule (full lines justified when the
     * alignment is left, never otherwise). 0: none (ragged, Photoshop's Left / Centre / Right). 1: full lines justified and
     * the last line of each paragraph aligned by [LayoutCore.align] (Justify Last Left / Centred / Right). 2: every line
     * justified, the last one too, by widening the spaces (Justify All).
     */
    val justifyMode: Int = -1,
)

/** One word (or any unbreakable piece) of a line: text[start, end) drawn at [x] from the box's left edge. */
class Placed(val line: Int, val start: Int, val end: Int, val x: Float, val baseline: Float)

class LaidLine(
    val index: Int,
    val start: Int,
    val end: Int,
    val baseline: Float,
    /** Extra tracking (em) this line was stretched with on top of the normal letter spacing. */
    val letterExtraEm: Float,
    val width: Float,
    /** Horizontal squeeze applied to the whole line (a non-breakable line that was too long). 1 = none. */
    val scaleX: Float = 1f,
    /** Where the line starts, from the box's left edge (its paragraph indent). The squeeze above is applied about this x. */
    val originX: Float = 0f,
)

class LayoutResult(
    val size: Float,
    val scaleX: Float,
    /** Width the text was wrapped at (the box width divided by the horizontal scale). */
    val wrapWidth: Float,
    val lineHeight: Float,
    val lines: List<LaidLine>,
    val words: List<Placed>,
    /** Height of the laid-out lines, without the reserved materials row. */
    val textHeight: Float,
    val widest: Float,
)

/** Inline style of every character: 0 plain, 1 bold, 2 italic, 3 both. */
class Source(val text: String, val styles: IntArray) {
    init { require(styles.size == text.length) { "one style per character" } }
}

private class Atom(var start: Int, var end: Int, var spaces: Int, var endsParagraph: Boolean, var forced: Boolean = false) {
    var width = 0f
    /** >= 0 when the atom belongs to a non-breakable line ([[ ]]); every atom of one such line shares the id. */
    var lock = -1
    var lockJust = false
}

/** [paraFirst]: the line is the first of its paragraph (it gets the first-line indent and the space before). */
private class RawLine(
    val first: Int,
    val last: Int,
    val endsParagraph: Boolean,
    val paraFirst: Boolean = false,
    /** The line ended only because the next word did not fit, so a composer may move this break. */
    val soft: Boolean = false,
)

class LayoutCore(
    private val src: Source,
    private val factory: MetricsFactory,
    private val boxWidth: Float,
    private val boxHeight: Float,
    private val opt: LayoutOptions,
    /** Height the materials row takes at a given body font size (0 when there is none). */
    private val reserve: (Float) -> Float = { 0f },
    private val baseSize: Float,
    /** Whole-line spans that never wrap: too long they squeeze, and the justified kind expands to fill the width. */
    private val fixed: List<FixedSpan> = emptyList(),
) {
    private val text = src.text
    private val styles = src.styles
    private val hs = (opt.hScale.takeIf { it > 0f } ?: 100f) / 100f
    private val wrapWidth = boxWidth / hs
    private val wordSpacePct = if (opt.wordSpace > 0f) opt.wordSpace else 100f
    /** Minimum and maximum word spacing as fractions of Desired (spaces are measured at Desired). */
    private val wsMinF = ((if (opt.wordSpaceMin > 0f) opt.wordSpaceMin else wordSpacePct) / wordSpacePct).coerceIn(0.05f, 1f)
    private val wsMaxF = ((if (opt.wordSpaceMax > 0f) opt.wordSpaceMax else wordSpacePct * 1.33f) / wordSpacePct).coerceAtLeast(1f)

    // Paragraph indents are in card px. The whole block is drawn with the horizontal scale applied to the canvas, so they
    // are divided by it here to stay the same size on the card.
    private val indL = opt.indentLeft / hs
    private val indR = opt.indentRight / hs
    private val indF = opt.indentFirst / hs

    /** Where a line starts, from the box's left edge. */
    private fun lineX(paraFirst: Boolean): Float = (indL + if (paraFirst) indF else 0f).coerceAtLeast(0f)

    /** The width a line has between its indents, when the box is [breakWidth] wide. */
    private fun lineAvail(breakWidth: Float, paraFirst: Boolean): Float =
        (breakWidth - lineX(paraFirst) - indR).coerceAtLeast(1f)

    /** Extra height of all the paragraph breaks: space after the paragraph above plus space before this one. */
    private fun paraSpacing(lines: List<RawLine>): Float {
        val per = opt.spaceBefore + opt.spaceAfter
        if (per <= 0f) return 0f
        var n = 0
        for ((i, l) in lines.withIndex()) if (i > 0 && l.paraFirst) n++
        return n * per
    }

    private fun isCjk(c: Char): Boolean =
        c in '぀'..'ヿ' || c in '㐀'..'䶿' || c in '一'..'鿿' || c in '＀'..'￯' || c in '가'..'힯'

    /** The unbreakable pieces of the text, in order. A piece owns the spaces that follow it. */
    private fun tokenize(): MutableList<Atom> {
        val out = ArrayList<Atom>()
        var pos = 0
        for ((id, f) in fixed.withIndex()) {
            tokenizeRange(out, pos, f.start, -1, false)
            val before = out.size
            tokenizeRange(out, f.start, f.end, id, f.justified)
            pos = f.end
            // The line break that ends the span belongs to its last piece, so it does not make an empty line.
            if (pos < text.length && (text[pos] == '\n' || text[pos] == '\u2028') && out.size > before) {
                val last = out.last()
                if (text[pos] == '\n') last.endsParagraph = true else last.forced = true
                pos++
            }
        }
        tokenizeRange(out, pos, text.length, -1, false)
        return out
    }

    private fun tokenizeRange(out: MutableList<Atom>, from: Int, to: Int, lock: Int, lockJust: Boolean) {
        var i = from
        val n = to
        while (i < n) {
            val s = i
            var contentEnd = i
            var spaces = 0
            var para = false
            var forced = false
            var done = false
            while (i < n && !done) {
                val c = text[i]
                when {
                    c == '\u2028' -> {
                        // A forced line break inside a paragraph: the line before it is still justified.
                        contentEnd = i
                        i++
                        forced = true
                        done = true
                    }
                    c == '\n' -> {
                        contentEnd = i
                        i++
                        para = true
                        done = true
                    }
                    c == ' ' -> {
                        // Content ends at the first space; the run of spaces belongs to this piece.
                        contentEnd = i
                        while (i < n && text[i] == ' ') { i++; spaces++ }
                        if (i < n && text[i] == '\n') { i++; para = true }
                        else if (i < n && text[i] == '\u2028') { i++; forced = true; spaces = 0 }
                        done = true
                    }
                    else -> {
                        i++
                        contentEnd = i
                        val next = if (i < n) text[i] else '\u0000'
                        val breakHere = (c == '-' && i < n && next != ' ' && next != '\n' && s < i - 1) ||
                            (c == '/' && next != '\u2060' && i < n && next != ' ' && next != '\n') ||
                            c == '\u200b' ||
                            (i < n && next != ' ' && next != '\n' && (isCjk(c) || isCjk(next)) && next != '\u2060')
                        if (breakHere) done = true
                    }
                }
            }
            out.add(Atom(s, contentEnd, spaces, para, forced).also { it.lock = lock; it.lockJust = lockJust })
        }
    }

    private fun runWidth(m: Metrics, a: Int, b: Int, letterEm: Float): Float {
        if (b <= a) return 0f
        var w = 0f
        var x = a
        while (x < b) {
            val st = styles[x]
            var y = x + 1
            while (y < b && styles[y] == st) y++
            w += m.advance(text, x, y, st, letterEm)
            x = y
        }
        return w
    }

    /** Width of one space character after word spacing. */
    private fun spaceW(m: Metrics, at: Int, letterEm: Float): Float =
        m.advance(" ", 0, 1, if (at in styles.indices) styles[at] else 0, letterEm) * wordSpacePct / 100f

    private fun spacesWidth(m: Metrics, from: Int, count: Int, letterEm: Float): Float {
        var w = 0f
        for (k in 0 until count) w += spaceW(m, from + k, letterEm)
        return w
    }

    /**
     * Breaks the text into lines at [breakWidth]. First a greedy pass (Photoshop's Single-line Composer): a word joins the
     * line when the line still fits with its spaces at the minimum word spacing. A line is only wider than that when a
     * single character does not fit. With the Every-line Composer, the soft breaks of each paragraph are then chosen again
     * by [composeEveryLine].
     */
    private fun wrap(m: Metrics, breakWidth: Float): Pair<List<RawLine>, List<Atom>> {
        val letterEm = opt.letterSpace
        val atoms = tokenize()
        val lines = ArrayList<RawLine>()
        var lineFirst = -1
        // Width of the line being built: its pieces, and its spaces at Desired word spacing.
        var curA = 0f
        var curS = 0f
        var curAvail = 0f
        // The line being built is the first of its paragraph at the start and after every paragraph break.
        var pf = true
        fun addLine(first: Int, last: Int, endsPara: Boolean, soft: Boolean = false) {
            lines.add(RawLine(first, last, endsPara, pf, soft))
            pf = endsPara
        }
        var i = 0
        while (i < atoms.size) {
            val a = atoms[i]
            a.width = runWidth(m, a.start, a.end, letterEm)
            if (lineFirst < 0) {
                val avail = lineAvail(breakWidth, pf)
                if (a.lock < 0 && a.width > avail + 0.01f && a.end - a.start > 1) {
                    // Too long for a line of its own: cut it where it stops fitting; the rest carries on.
                    var cut = a.start + 1
                    while (cut < a.end && runWidth(m, a.start, cut + 1, letterEm) <= avail + 0.01f) cut++
                    val tail = Atom(cut, a.end, a.spaces, a.endsParagraph)
                    a.end = cut
                    a.spaces = 0
                    a.width = runWidth(m, a.start, a.end, letterEm)
                    atoms.add(i + 1, tail)
                    // A cut piece is a whole line by itself.
                    addLine(i, i, false)
                    i++
                    continue
                }
                lineFirst = i
                curA = a.width
                curS = 0f
                curAvail = avail
            } else {
                val prev = atoms[i - 1]
                val gap = spacesWidth(m, prev.end, prev.spaces, letterEm)
                if (a.lock != prev.lock) {
                    // A non-breakable line starts or ends here: it always has a line to itself.
                    addLine(lineFirst, i - 1, false)
                    lineFirst = -1
                    continue
                }
                if (a.lock >= 0 || curA + a.width + (curS + gap) * wsMinF <= curAvail + 0.01f) {
                    curA += a.width
                    curS += gap
                } else {
                    addLine(lineFirst, i - 1, false, soft = true)
                    lineFirst = -1
                    continue // place this piece again as the first of a new line
                }
            }
            if (a.endsParagraph) {
                addLine(lineFirst, i, true)
                lineFirst = -1
            } else if (a.forced) {
                addLine(lineFirst, i, false)
                lineFirst = -1
            }
            i++
        }
        if (lineFirst >= 0) addLine(lineFirst, atoms.size - 1, true)
        // A text that ends with a paragraph break has an empty last line, like any editor.
        if (text.isEmpty() || text.endsWith("\n")) addLine(atoms.size, atoms.size - 1, true)
        if (opt.composer == 1) return composeEveryLine(m, breakWidth, lines, atoms) to atoms
        return lines to atoms
    }

    /**
     * Photoshop's Every-line Composer, as far as it can be copied: within each run of lines joined by soft breaks, choose
     * the breaks that make the spacing most even across all the lines, instead of filling each line in turn.
     *
     * Adobe does not publish its scoring, so this uses the classic paragraph-breaking method (Knuth and Plass, as in TeX):
     * each line scores how far its spaces must stretch or shrink from Desired, measured against the room up to Maximum or
     * down to Minimum, cubed. Squeezing below Minimum is not allowed. The last line of a run is free as long as it fits.
     * Every line also costs a little, so fewer lines win a tie. With this scoring the breaks of the Endymion proxy come out
     * exactly (see LayoutCoreTest), which is the evidence it matches Photoshop closely enough.
     */
    private fun composeEveryLine(m: Metrics, breakWidth: Float, greedy: List<RawLine>, atoms: List<Atom>): List<RawLine> {
        val out = ArrayList<RawLine>(greedy.size)
        var k = 0
        while (k < greedy.size) {
            // A run: lines k..e where every line but the last ended softly (and none is a fixed [[ ]] line).
            var e = k
            while (e < greedy.size - 1 && greedy[e].soft && greedy[e].last >= greedy[e].first &&
                atoms[greedy[e].first].lock < 0) e++
            val run = greedy.subList(k, e + 1)
            if (run.size < 2 || atoms[run[0].first].lock >= 0) {
                out.addAll(run)
            } else {
                out.addAll(composeRun(m, breakWidth, run, atoms))
            }
            k = e + 1
        }
        return out
    }

    private fun composeRun(m: Metrics, breakWidth: Float, run: List<RawLine>, atoms: List<Atom>): List<RawLine> {
        val letterEm = opt.letterSpace
        val from = run.first().first
        val to = run.last().last
        val n = to - from + 1
        // Gap after each atom (its spaces at Desired), used between it and the next atom on the same line.
        val gapW = FloatArray(n) { spacesWidth(m, atoms[from + it].end, atoms[from + it].spaces, letterEm) }
        val gapN = IntArray(n) { atoms[from + it].spaces }
        val inf = Float.MAX_VALUE
        // best[j]: lowest cost of breaking atoms from..from+j-1 into lines; prev[j]: where the last of those lines starts.
        val best = FloatArray(n + 1) { inf }
        val prev = IntArray(n + 1) { -1 }
        best[0] = 0f
        val firstPara = run.first().paraFirst
        for (j in 1..n) {
            val isLast = j == n
            var aW = 0f
            var sW = 0f
            var spaces = 0
            var i = j - 1
            while (i >= 0) {
                aW += atoms[from + i].width
                if (i < j - 1) {
                    sW += gapW[i]
                    spaces += gapN[i]
                }
                val avail = lineAvail(breakWidth, i == 0 && firstPara)
                val atMin = aW + sW * wsMinF
                if (atMin > avail + 0.01f && i < j - 1) break // more pieces only make it wider
                if (best[i] == inf) { i--; continue }
                val bad: Float = when {
                    isLast -> if (atMin <= avail + 0.01f || i == j - 1) 0f else inf
                    spaces == 0 || sW <= 0f -> if (i == j - 1) 10000f else inf
                    else -> {
                        val q = (avail - aW) / sW // space width needed, as a fraction of Desired
                        val t = if (q >= 1f) (q - 1f) / (wsMaxF - 1f).coerceAtLeast(0.01f) else (1f - q) / (1f - wsMinF).coerceAtLeast(0.01f)
                        minOf(100f * t * t * t, 10000f)
                    }
                }
                if (bad < inf) {
                    val c = best[i] + bad + 1f
                    if (c < best[j]) {
                        best[j] = c
                        prev[j] = i
                    }
                }
                i--
            }
        }
        if (best[n] == inf) return run
        val starts = ArrayList<Int>()
        var j = n
        while (j > 0) {
            starts.add(prev[j])
            j = prev[j]
        }
        starts.reverse()
        val res = ArrayList<RawLine>(starts.size)
        for ((idx, s) in starts.withIndex()) {
            val end = if (idx + 1 < starts.size) starts[idx + 1] - 1 else n - 1
            val last = idx == starts.size - 1
            res.add(
                RawLine(
                    from + s, from + end,
                    endsParagraph = if (last) run.last().endsParagraph else false,
                    paraFirst = idx == 0 && firstPara,
                    soft = !last || run.last().soft,
                )
            )
        }
        return res
    }

    private fun lineHeightOf(m: Metrics): Float {
        val mult = if (opt.leadingPx > 0f) {
            val h = m.descent - m.ascent
            if (h > 0f) opt.leadingPx / h else opt.spacing
        } else opt.spacing
        // Automatic leading, like StaticLayout: lines are ascent-to-descent apart. (The font's full top-to-bottom height
        // only counts above the first line and below the last one, see blockHeight.)
        val base = if (opt.leadingPx > 0f) m.descent - m.ascent else m.lineDescent - m.lineAscent
        return base * mult
    }

    /** The first baseline, from the box top. */
    private fun firstBaseline(m: Metrics): Float =
        if (opt.photoshopFirstBaseline) m.firstLineAscent else -(if (opt.leadingPx > 0f) m.ascent else m.lineTop)

    /**
     * Height of [n] lines without paragraph spacing. With Leading px every line is that tall. With automatic leading the
     * first line starts at the font's top and the last ends at its bottom, and the lines in between are one line height
     * apart, which is how StaticLayout (and so the old engine) counts.
     */
    private fun blockHeight(m: Metrics, n: Int): Float {
        if (n <= 0) return 0f
        val lh = lineHeightOf(m)
        return if (opt.leadingPx > 0f) n * lh else (m.lineBottom - m.lineTop) + (n - 1) * lh
    }

    /** How many lines the text takes at this size and squeeze, and how tall that is. Used by the fit search. */
    private fun heightAt(size: Float, scaleX: Float): Float {
        val m = factory.at(size, scaleX)
        val (lines, _) = wrap(m, wrapWidth)
        return reserve(size) + blockHeight(m, lines.size) + paraSpacing(lines)
    }

    private fun fits(size: Float, scaleX: Float): Boolean = heightAt(size, scaleX) <= boxHeight

    /** Chooses the font size and squeeze like the old engine, then lays the text out. */
    fun layout(): LayoutResult {
        var floor = if (opt.condense <= 0f) 1f else opt.condense
        if (floor > 1f) floor /= 100f
        floor = floor.coerceIn(0.3f, 1f)
        var phase1 = floor
        var phase2Floor = floor
        when (opt.fitMode) {
            2 -> phase1 = 1f
            3 -> phase1 = (1f + floor) / 2f
            4 -> { phase1 = 0.85f; phase2Floor = 0.85f }
            5 -> { phase1 = 0.70f; phase2Floor = 0.70f }
            6 -> { phase1 = 0.55f; phase2Floor = 0.55f }
        }
        var minSize = baseSize * 0.3f
        var maxSize = baseSize
        if (opt.minSizePct > 0f) minSize = opt.minSizePct * baseSize / 100f
        if (opt.maxSizePct > 0f) maxSize = opt.maxSizePct * baseSize / 100f
        if (maxSize < minSize) maxSize = minSize
        if (opt.minLines > 0f) {
            val m0 = factory.at(baseSize, 1f)
            val ratio = (m0.bottom - m0.top) * opt.spacing / baseSize
            var cap = boxHeight / (ratio * opt.minLines)
            if (cap < minSize) cap = minSize
            if (cap < maxSize) maxSize = cap
        }
        if (opt.fitMode == 7) minSize = maxSize
        if (opt.fontScale > 0f) {
            minSize = opt.fontScale * baseSize / 100f
            maxSize = minSize
        }

        var lo = minSize
        var hi = maxSize
        while (Math.abs(hi - lo) > 0.001f) {
            val mid = (lo + hi) / 2f
            if (fits(mid, phase1)) lo = mid else hi = mid
        }
        val size = lo
        // The size search ran at the first-phase squeeze; draw at full width unless that squeeze is really needed.
        var scale = 1f
        if (opt.fontScale <= 0f && !fits(size, 1f)) {
            var a = phase2Floor
            var b = 1f
            while (b - a > 0.005f) {
                val mid = (a + b) / 2f
                if (fits(size, mid)) a = mid else b = mid
            }
            scale = a
        }
        return place(size, scale)
    }

    /** Lays the text out at a fixed size and squeeze: line breaks, justified gaps, positions. */
    fun place(size: Float, scaleX: Float): LayoutResult {
        val m = factory.at(size, scaleX)
        val letterEm = opt.letterSpace
        var (lines, atoms) = wrap(m, wrapWidth)

        val flowPct = if (opt.justify >= 500f) 100f else opt.flow.coerceIn(0f, 100f)
        if (flowPct > 0f && lines.size >= 2) {
            // Re-wrap at the narrowest width that keeps the same line count, then stretch to the full width.
            val n = lines.size
            var a = (wrapWidth * 0.8f).toInt()
            var b = wrapWidth.toInt()
            var best: Pair<List<RawLine>, List<Atom>>? = null
            var bestW = wrapWidth
            while (a < b) {
                val mid = (a + b) shr 1
                val t = wrap(m, mid.toFloat())
                if (t.first.size == n) { best = t; bestW = mid.toFloat(); b = mid } else a = mid + 1
            }
            if (best != null) {
                val target = (wrapWidth - (wrapWidth - bestW) * flowPct / 100f).coerceIn(bestW, wrapWidth)
                val chosen = if (flowPct >= 100f || target >= wrapWidth) best
                else wrap(m, target).takeIf { it.first.size == n } ?: best
                lines = chosen.first
                atoms = chosen.second
            }
        }

        val lineH = lineHeightOf(m)
        val first = firstBaseline(m)
        val laid = ArrayList<LaidLine>()
        val words = ArrayList<Placed>()
        var widest = 0f
        val plainSpace = m.advance(" ", 0, 1, 0, letterEm)
        val justifyFull = if (opt.justifyMode < 0) align == 0 else opt.justifyMode >= 1
        val justifyAll = opt.justifyMode == 2
        // Space after the paragraph above and before this one, added once per paragraph break.
        var extraY = 0f

        for ((li, rl) in lines.withIndex()) {
            if (li > 0 && rl.paraFirst) extraY += opt.spaceBefore + opt.spaceAfter
            val baseline = first + li * lineH + extraY
            val lx = lineX(rl.paraFirst)
            val avail = lineAvail(wrapWidth, rl.paraFirst)
            if (rl.last < rl.first) {
                laid.add(LaidLine(li, text.length, text.length, baseline, 0f, 0f, 1f, lx))
                continue
            }
            val atomsOfLine = atoms.subList(rl.first, rl.last + 1)
            val start = atomsOfLine.first().start
            val end = atomsOfLine.last().end
            // Natural width and the gaps between pieces (every space character is a gap).
            fun natural(em: Float): Float {
                var w = 0f
                for ((k, at) in atomsOfLine.withIndex()) {
                    w += runWidth(m, at.start, at.end, em)
                    if (k < atomsOfLine.size - 1) w += spacesWidth(m, at.end, at.spaces, em)
                }
                return w
            }
            val lw = natural(letterEm)
            val gaps = atomsOfLine.dropLast(1).sumOf { it.spaces }
            val extra = avail - lw
            val isLastLine = li == lines.size - 1
            // A forced line break (<br>, Photoshop's Shift+Enter) ends the line like a paragraph end, for justification
            // only: Photoshop does not stretch the line before it (seen in the user's template PSD).
            val endsForced = atomsOfLine.last().forced
            val endsParagraph = rl.endsParagraph || isLastLine || endsForced
            var justified = false
            var spread = 0f
            var letterExtra = 0f
            val contentLen = end - start
            val locked = atomsOfLine.first().lock >= 0
            var lineScale = 1f

            if (locked) {
                if (lw > avail + 0.01f) {
                    lineScale = (avail / lw).coerceAtLeast(0.3f)
                } else if (atomsOfLine.first().lockJust && gaps > 0 && extra > 0f) {
                    justified = true
                    spread = extra / gaps
                }
            } else if (extra < -0.01f && gaps > 0) {
                // The line only fits with its spaces squeezed toward the minimum word spacing (any alignment).
                justified = true
                spread = extra / gaps
            } else if (justifyFull && (lines.size > 1 || justifyAll) && extra > 0f &&
                (gaps > 0 || (isLastLine && opt.lastStretch > 0f))) {
                if (!endsParagraph) {
                    justified = gaps > 0
                    spread = if (gaps > 0) extra / gaps else 0f
                } else if (justifyAll && gaps > 0) {
                    // Justify All: the last line is stretched too, by its spaces only (letter spacing 0, like Photoshop).
                    justified = true
                    spread = extra / gaps
                } else if (opt.lastStretch > 0f && isLastLine) {
                    val want = extra * opt.lastStretch.coerceIn(0f, 100f) / 100f
                    letterExtra = minOf(want / (contentLen.coerceAtLeast(1) * size), 0.08f)
                    val nw = natural(letterEm + letterExtra)
                    justified = true
                    spread = if (gaps > 0) maxOf(0f, (lw + want - nw) / gaps) else 0f
                } else if (gaps > 0 && opt.justify > 0f && extra / gaps <= plainSpace * opt.justify / 100f) {
                    letterExtra = minOf(extra / (contentLen.coerceAtLeast(1) * size), 0.08f)
                    val nw = natural(letterEm + letterExtra)
                    justified = true
                    spread = maxOf(0f, (avail - nw) / gaps)
                }
            }
            val em = letterEm + letterExtra
            val total = if (lineScale < 1f) avail else if (justified) natural(em) + spread * gaps else lw
            var x = lx + if (justified) 0f else when (align) {
                2 -> avail - lw
                1 -> (avail - lw) / 2f
                else -> 0f
            }
            for ((k, at) in atomsOfLine.withIndex()) {
                if (at.end > at.start) words.add(Placed(li, at.start, at.end, x, baseline))
                x += runWidth(m, at.start, at.end, em)
                if (k < atomsOfLine.size - 1) x += spacesWidth(m, at.end, at.spaces, em) + spread * at.spaces
            }
            laid.add(LaidLine(li, start, end, baseline, letterExtra, total, lineScale, lx))
            widest = maxOf(widest, lx + total)
        }
        return LayoutResult(size, scaleX, wrapWidth, lineH, laid, words, blockHeight(m, lines.size) + extraY, widest)
    }

    /** Horizontal alignment: 0 left (and justified), 1 centre, 2 right. Set before [layout]. */
    var align: Int = 0
}


/** A whole line that is never wrapped: [start, end) in the cleaned text. */
class FixedSpan(val start: Int, val end: Int, val justified: Boolean)

class FixedResult(val text: String, val styles: IntArray, val spans: List<FixedSpan>)

/**
 * Reads the whole-line syntax of ygocarder: [[line]] is a justified, non-breakable line (squeezed if too long, stretched
 * to the full width if short). The brackets are removed and the line is recorded. A pair only counts when it covers a
 * whole line, that is when it starts the text or a line and ends at the end of the text, at a line break, or right
 * before another such pair; brackets anywhere else are ordinary text.
 */
fun extractFixedLines(text: String, styles: IntArray): FixedResult {
    if (!text.contains("[[")) return FixedResult(text, styles, emptyList())
    val out = StringBuilder(text.length)
    val outSt = ArrayList<Int>(text.length)
    val spans = ArrayList<FixedSpan>()
    val n = text.length
    fun isBreak(k: Int) = k >= n || text[k] == '\n' || text[k] == '\u2028'
    fun boundaryAfter(k: Int): Boolean {
        var m = k
        while (m < n && text[m] == ' ') m++
        return isBreak(m) || text.startsWith("[[", m)
    }
    var i = 0
    var atLineStart = true
    while (i < n) {
        if (atLineStart && text.startsWith("[[", i)) {
            var j = i + 2
            var found = -1
            while (j < n && !isBreak(j)) {
                if (text.startsWith("]]", j) && boundaryAfter(j + 2)) { found = j; break }
                j++
            }
            if (found > i + 2) {
                val start = out.length
                for (k in i + 2 until found) { out.append(text[k]); outSt.add(styles[k]) }
                spans.add(FixedSpan(start, out.length, true))
                i = found + 2
                while (i < n && text[i] == ' ') i++
                atLineStart = true
                continue
            }
        }
        out.append(text[i]); outSt.add(styles[i])
        atLineStart = text[i] == '\n' || text[i] == '\u2028'
        i++
    }
    return FixedResult(out.toString(), outSt.toIntArray(), spans)
}
