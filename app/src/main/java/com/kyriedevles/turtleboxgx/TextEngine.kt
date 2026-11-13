package com.kyriedevles.turtleboxgx

import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan

/** Exact (unrounded) advance width of a string; Paint.measureText rounds up on some devices. */
fun exactWidth(p: android.graphics.Paint, s: String): Float =
    if (s.isEmpty()) 0f else p.getRunAdvance(s, 0, s.length, 0, s.length, false, s.length)

/** Text with its inline tags removed, and the style of every remaining character: 0 plain, 1 bold, 2 italic, 3 both. */
class StyledText(val text: String, val styles: IntArray) {
    val hasStyle: Boolean = styles.any { it != 0 }
}

/**
 * Reads the inline tags <b>..</b> and <i>..</i> (same tags as ygocarder). A tag that is never closed stays on to the
 * end of the text, across lines and paragraphs. Anything else that looks like a tag is left as ordinary text.
 */
fun parseMarkup(raw: String): StyledText {
    if (raw.indexOf('<') < 0) return StyledText(raw, IntArray(raw.length))
    val out = StringBuilder(raw.length)
    val st = IntArray(raw.length)
    var n = 0
    var bold = false
    var italic = false
    var i = 0
    while (i < raw.length) {
        if (raw[i] == '<') {
            var tagLen = 0
            if (raw.startsWith("<b>", i)) { bold = true; tagLen = 3 }
            else if (raw.startsWith("</b>", i)) { bold = false; tagLen = 4 }
            else if (raw.startsWith("<i>", i)) { italic = true; tagLen = 3 }
            else if (raw.startsWith("</i>", i)) { italic = false; tagLen = 4 }
            if (tagLen > 0) {
                i += tagLen
                continue
            }
        }
        out.append(raw[i])
        st[n] = (if (bold) 1 else 0) or (if (italic) 2 else 0)
        n++
        i++
    }
    return StyledText(out.toString(), st.copyOf(n))
}

/** The text options of one box, already turned into numbers. Zero means "not set". */
class TextOptions(
    val condense: Float = 0f, // 0.3..1.0 (or a percent such as 85)
    val fitMode: Int = 1, // 1 squeeze, 2 shrink first, 3 balanced, 4/5/6 squeeze presets, 7 keep size
    val minSizePct: Float = 0f,
    val maxSizePct: Float = 0f,
    val minLines: Float = 0f,
    val justify: Float = 0f, // 0 off, 100 light, 200 normal, 400 full, 500 balanced reflow
    val letterSpace: Float = 0f, // em
    val lastStretch: Float = 0f, // % of the last line's gap to the box width that is closed (0..100, fractions allowed)
    val hScale: Float = 100f, // horizontal scale % of the whole block (100 = normal)
    val vScale: Float = 100f, // vertical scale % of the whole block
    val baselineShift: Float = 0f, // px, positive raises the text
    val wordSpace: Float = 100f, // % of the normal space width
    val fontScale: Float = 0f, // manual font size in % of the layout size (0 = automatic fit)
    val flow: Float = 0f, // % of the whole-text reflow toward the narrowest wrap with the same line count (0..100)
    val spacing: Float = 1f, // line spacing multiplier
)

/**
 * Fits a block of text into a box the way the old app did, then draws it with justified lines.
 *
 * Fit: find the largest font size that fits when the text is squeezed to a floor width, then use the
 * least squeezing that still fits at that size. Justify: every full line is stretched to the box width;
 * the last line of a paragraph only when its gaps are small enough (the Justify option), and "balanced"
 * first re-wraps the paragraph a little narrower so the lines come out even.
 */
class TextEngine(
    rawText: String,
    private val paint: TextPaint,
    private val boxWidth: Int,
    private val boxHeight: Float,
    private val opt: TextOptions,
    /** Optional first line (Fusion / Synchro / Xyz / Link materials): same size as the text, but squeezed on its own to stay on one line. */
    private val materials: String = "",
    /** Font size of the materials line as a percent of the text size (100 = same size). */
    private val materialsPct: Float = 100f,
    /** Width of the materials line as a percent of its natural width (100 = natural). Above 100 stretches it, but never past the box width. */
    private val materialsWidthPct: Float = 100f,
    /** Height of the materials row as a percent of a normal line (100 = normal). Lower pulls the text under it up. */
    private val materialsLinePct: Float = 100f,
) {
    // Horizontal / vertical scale work like Photoshop's: they stretch the glyphs after the normal fit. Horizontal scale
    // re-wraps the lines at the scaled width; vertical scale stretches each line about its baseline (leading does not change).
    private val hs = (opt.hScale.takeIf { it > 0f } ?: 100f) / 100f
    private val vs = (opt.vScale.takeIf { it > 0f } ?: 100f) / 100f
    private val width: Int = boxWidth
    private val height: Float = boxHeight
    private var effWidth = boxWidth
    private val styled = parseMarkup(rawText)
    private val text: String = styled.text
    private val styles: IntArray = styled.styles
    /** What the line breaker measures: the plain string when there is no markup (unchanged behaviour), else a string with style spans. */
    private val source: CharSequence = if (styled.hasStyle) styledSource() else text
    private val baseSize = paint.textSize
    private var layout: StaticLayout? = null
    private var lineWidthLimit = width.toFloat()
    private var matLayout: StaticLayout? = null
    private var matScale = 1f
    private var matH = 0f

    /** The materials line laid out alone (never wrapped), so its height and natural width can be read from it. */
    private fun buildMaterials(): StaticLayout =
        StaticLayout.Builder.obtain(materials, 0, materials.length, paint, 100000)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, opt.spacing)
            .setIncludePad(true)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .build()

    private fun materialsHeight(): Float {
        if (materials.isEmpty()) return 0f
        val bodySize = paint.textSize
        paint.textSize = bodySize * materialsPct / 100f
        val h = buildMaterials().height.toFloat() * materialsLinePct / 100f
        paint.textSize = bodySize
        return h
    }

    private fun spanStyle(v: Int): Int = when (v) {
        1 -> Typeface.BOLD
        2 -> Typeface.ITALIC
        else -> Typeface.BOLD_ITALIC
    }

    private fun styledSource(): CharSequence {
        val s = SpannableString(text)
        var a = 0
        while (a < styles.size) {
            var b = a + 1
            while (b < styles.size && styles[b] == styles[a]) b++
            if (styles[a] != 0) s.setSpan(StyleSpan(spanStyle(styles[a])), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            a = b
        }
        return s
    }

    /** The paint for one style: the box paint (same size, squeeze and spacing) with bold and/or italic applied. */
    private fun paintFor(style: Int): TextPaint {
        if (style == 0) return paint
        val p = TextPaint(paint)
        StyleSpan(spanStyle(style)).updateDrawState(p)
        return p
    }

    /** Calls [fn] for each stretch of equal style inside [from, to). */
    private inline fun runs(from: Int, to: Int, fn: (Int, Int, Int) -> Unit) {
        var a = from
        while (a < to) {
            val s = if (a < styles.size) styles[a] else 0
            var b = a + 1
            while (b < to && b < styles.size && styles[b] == s) b++
            fn(a, b, s)
            a = b
        }
    }

    /** Exact width of text[from, to) with every stretch measured in its own style. */
    private fun rangeWidth(from: Int, to: Int): Float {
        if (to <= from) return 0f
        if (!styled.hasStyle) return exactWidth(paint, text.substring(from, to))
        var w = 0f
        runs(from, to) { a, b, s -> w += exactWidth(paintFor(s), text.substring(a, b)) }
        return w
    }

    /** Width used to place a word inside a justified line (measureText, as the wrapping sees it). */
    private fun prefixWidth(from: Int, to: Int): Float {
        if (to <= from) return 0f
        if (!styled.hasStyle) return paint.measureText(text, from, to)
        var w = 0f
        runs(from, to) { a, b, s -> w += paintFor(s).measureText(text, a, b) }
        return w
    }

    private fun drawRange(c: Canvas, from: Int, to: Int, x: Float, baseline: Float) {
        if (to <= from) return
        if (!styled.hasStyle) {
            c.drawText(text, from, to, x, baseline, paint)
            return
        }
        var cx = x
        runs(from, to) { a, b, s ->
            val p = paintFor(s)
            c.drawText(text, a, b, cx, baseline, p)
            cx += exactWidth(p, text.substring(a, b))
        }
    }

    private fun build(w: Int): StaticLayout =
        StaticLayout.Builder.obtain(source, 0, source.length, paint, w.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, opt.spacing)
            .setIncludePad(true)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
            .build()

    private fun fits(size: Float, scaleX: Float): Boolean {
        paint.textSize = size
        paint.textScaleX = scaleX
        val sl = build(width)
        return materialsHeight() + sl.height <= height
    }

    /** Widest line as the drawing code measures it (trailing spaces and newlines trimmed). */
    private fun widestLine(sl: StaticLayout): Float {
        var widest = 0f
        for (i in 0 until sl.lineCount) {
            val start = sl.getLineStart(i)
            var end = sl.getLineEnd(i)
            while (end > start && (text[end - 1] == '\n' || text[end - 1] == ' ')) end--
            if (end <= start) continue
            widest = maxOf(widest, rangeWidth(start, end))
        }
        return widest
    }

    /** Chooses the font size and squeeze, and lays the text out. Returns the laid-out height. */
    fun fit(): Float {
        paint.letterSpacing = opt.letterSpace
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
            paint.textSize = baseSize
            val fm = paint.fontMetrics
            val ratio = (fm.bottom - fm.top) * opt.spacing / baseSize
            var cap = height / (ratio * opt.minLines)
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
        paint.textScaleX = phase1
        while (Math.abs(hi - lo) > 0.001f) {
            val mid = (lo + hi) / 2f
            if (fits(mid, phase1)) lo = mid else hi = mid
        }
        val size = lo
        paint.textSize = size
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
        paint.textSize = size
        paint.textScaleX = scale
        paint.letterSpacing = opt.letterSpace

        var sl = build(width)
        // Safety net: if any line is drawn wider than the box (wrap and draw measuring differently), squeeze until it fits.
        var guard = 0
        while (guard++ < 6) {
            val widest = widestLine(sl)
            if (widest <= width + 0.5f || widest <= 0f) break
            val next = (paint.textScaleX * width / widest).coerceAtLeast(0.3f)
            if (next >= paint.textScaleX) break
            paint.textScaleX = next
            sl = build(width)
        }
        lineWidthLimit = width.toFloat()
        val flowPct = if (opt.justify >= 500f) 100f else opt.flow.coerceIn(0f, 100f)
        if (flowPct > 0f && sl.lineCount >= 2) {
            // Re-wrap at the narrowest width that keeps the same line count, then stretch to the full width.
            val n = sl.lineCount
            var a = (width * 0.8f).toInt()
            var b = width
            var best: StaticLayout? = null
            while (a < b) {
                val mid = (a + b) shr 1
                val t = build(mid)
                if (t.lineCount == n) {
                    best = t
                    b = mid
                } else {
                    a = mid + 1
                }
            }
            if (best != null) {
                val target = (width - (width - best.width) * flowPct / 100f).toInt().coerceIn(best.width, width)
                sl = if (flowPct >= 100f || target >= width) best else build(target).takeIf { it.lineCount == n } ?: best
            }
        }
        effWidth = width
        if (hs != 1f) {
            effWidth = (width / hs).toInt().coerceAtLeast(1)
            sl = build(effWidth)
        }
        layout = sl
        matLayout = null
        matH = 0f
        if (materials.isNotEmpty()) {
            // Same font size as the text, but its own squeeze: natural width unless it is too long for one line.
            val bodyScale = paint.textScaleX
            val bodySize = paint.textSize
            paint.textScaleX = 1f
            paint.textSize = bodySize * materialsPct / 100f
            val natural = exactWidth(paint, materials)
            // The width setting stretches or squeezes it; it never goes past the box, so a long line still fits on one line.
            matScale = if (natural > 0f) minOf(materialsWidthPct / 100f, width / natural).coerceAtLeast(0.3f) else 1f
            val ml = buildMaterials()
            matLayout = ml
            matH = ml.height.toFloat() * materialsLinePct / 100f
            paint.textSize = bodySize
            paint.textScaleX = bodyScale
        }
        return sl.height.toFloat() + matH
    }

    private fun drawMaterials(c: Canvas, left: Float, top: Float, h: Int, ml: StaticLayout) {
        val bodyScale = paint.textScaleX
        val bodySize = paint.textSize
        paint.textScaleX = matScale
        paint.textSize = bodySize * materialsPct / 100f
        val mw = exactWidth(paint, materials)
        val x = left + when (h) {
            2 -> width - mw
            1 -> (width - mw) / 2f
            else -> 0f
        }
        c.drawText(materials, x, top + ml.getLineBaseline(0), paint)
        paint.textSize = bodySize
        paint.textScaleX = bodyScale
    }

    /** Draws the laid-out text with its top at [top]. [h] is 0 left, 1 centre, 2 right. */
    fun draw(c: Canvas, left: Float, top: Float, h: Int) {
        val sl = layout ?: return
        val w = effWidth.toFloat()
        val spaceW = exactWidth(paint, " ")
        val baseSpacing = paint.letterSpacing
        matLayout?.let { drawMaterials(c, left, top, h, it) }
        val saved = c.save()
        if (hs != 1f) c.scale(hs, 1f, left, top)
        val bodyTop = top + matH
        for (i in 0 until sl.lineCount) {
            val start = sl.getLineStart(i)
            var end = sl.getLineEnd(i)
            while (end > start && (text[end - 1] == '\n' || text[end - 1] == ' ')) end--
            if (end <= start) continue
            val seg = text.substring(start, end)
            val baseline = bodyTop + sl.getLineBaseline(i) - opt.baselineShift
            val lineSaved = c.save()
            if (vs != 1f) c.scale(1f, vs, left, baseline)
            val lw = rangeWidth(start, end)
            val endsParagraph = i == sl.lineCount - 1 || text[maxOf(sl.getLineEnd(i) - 1, 0)] == '\n'
            val gaps = seg.count { it == ' ' }
            val extra = w - lw
            var justified = false
            var spread = 0f
            if (h == 0 && sl.lineCount > 1 && extra > 0f && (gaps > 0 || (i == sl.lineCount - 1 && opt.lastStretch > 0f))) {
                if (!endsParagraph) {
                    justified = gaps > 0
                    spread = if (gaps > 0) extra / gaps else 0f
                } else if (opt.lastStretch > 0f && i == sl.lineCount - 1) {
                    // Continuous: close lastStretch % of the gap. Letters take up to 0.08 em first, the rest goes to the gaps.
                    val want = extra * opt.lastStretch.coerceIn(0f, 100f) / 100f
                    val perChar = minOf(want / (seg.length * paint.textSize), 0.08f)
                    paint.letterSpacing = baseSpacing + perChar
                    val nw = rangeWidth(start, end)
                    justified = true
                    spread = if (gaps > 0) maxOf(0f, (lw + want - nw) / gaps) else 0f
                } else if (gaps > 0 && opt.justify > 0f && extra / gaps <= spaceW * opt.justify / 100f) {
                    // Last line: spread a little over the letters first (up to 0.08 em), the rest over the gaps.
                    val perChar = minOf(extra / (seg.length * paint.textSize), 0.08f)
                    paint.letterSpacing = baseSpacing + perChar
                    val nw = rangeWidth(start, end)
                    justified = true
                    spread = maxOf(0f, (w - nw) / gaps)
                }
            }
            var xoff = 0f
            if (!justified && opt.wordSpace > 0f && opt.wordSpace != 100f && gaps > 0) {
                // Word spacing: extra (or less) room at every space, the line is then aligned as a whole.
                spread = (opt.wordSpace / 100f - 1f) * spaceW
                val lineW = lw + spread * gaps
                xoff = when (h) {
                    2 -> w - lineW
                    1 -> (w - lineW) / 2f
                    else -> 0f
                }
                justified = true
            }
            if (!justified) {
                val x = left + when (h) {
                    2 -> w - lw
                    1 -> (w - lw) / 2f
                    else -> 0f
                }
                drawRange(c, start, end, x, baseline)
            } else {
                // Place each word from the width of the whole line up to it, so the metrics match the wrapping.
                var gap = 0
                var from = 0
                while (from <= seg.length) {
                    var to = seg.indexOf(' ', from)
                    if (to < 0) to = seg.length
                    if (to > from) {
                        val px = prefixWidth(start, start + from)
                        drawRange(c, start + from, start + to, left + xoff + px + spread * gap, baseline)
                    }
                    if (to >= seg.length) break
                    gap++
                    from = to + 1
                }
            }
            c.restoreToCount(lineSaved)
            paint.letterSpacing = baseSpacing
        }
        c.restoreToCount(saved)
    }
}
