package com.kyriedevles.turtleboxgx

import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Exact (unrounded) advance width of a string; Paint.measureText rounds up on some devices. */
fun exactWidth(p: android.graphics.Paint, s: String): Float =
    if (s.isEmpty()) 0f else p.getRunAdvance(s, 0, s.length, 0, s.length, false, s.length)

/** The text options of one box, already turned into numbers. Zero means "not set". */
class TextOptions(
    val condense: Float = 0f, // 0.3..1.0 (or a percent such as 85)
    val fitMode: Int = 1, // 1 squeeze, 2 shrink first, 3 balanced, 4/5/6 squeeze presets, 7 keep size
    val minSizePct: Float = 0f,
    val maxSizePct: Float = 0f,
    val minLines: Float = 0f,
    val justify: Float = 0f, // 0 off, 100 light, 200 normal, 400 full, 500 balanced reflow
    val letterSpace: Float = 0f, // em
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
class TextEngine(private val text: String, private val paint: TextPaint, private val width: Int, private val height: Float, private val opt: TextOptions) {
    private val baseSize = paint.textSize
    private var layout: StaticLayout? = null
    private var lineWidthLimit = width.toFloat()

    private fun build(w: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, w.coerceAtLeast(1))
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
        return sl.height <= height
    }

    var debug: String = ""
        private set

    /** One-line report: box width, size, squeeze, and for the widest line its measured width versus its ink bounds. */
    private fun describe(sl: StaticLayout): String {
        var wi = -1
        var wm = 0f
        for (i in 0 until sl.lineCount) {
            var end = sl.getLineEnd(i)
            val start = sl.getLineStart(i)
            while (end > start && (text[end - 1] == '\n' || text[end - 1] == ' ')) end--
            if (end <= start) continue
            val m = exactWidth(paint, text.substring(start, end))
            if (m > wm) { wm = m; wi = i }
        }
        if (wi < 0) return "text: empty"
        var end = sl.getLineEnd(wi)
        val start = sl.getLineStart(wi)
        while (end > start && (text[end - 1] == '\n' || text[end - 1] == ' ')) end--
        val r = android.graphics.Rect()
        paint.getTextBounds(text, start, end, r)
        val adv = FloatArray(end - start)
        val full = paint.getTextRunAdvances(text.toCharArray(), start, end - start, 0, text.length, false, adv, 0)
        return "text: box=%.1f size=%.2f scaleX=%.3f ls=%.3f lines=%d widestMeasure=%.1f inkRight=%.1f runAdv=%.1f layoutLineW=%.1f".format(
            width.toFloat(), paint.textSize, paint.textScaleX, paint.letterSpacing, sl.lineCount, wm, r.right.toFloat(), full, sl.getLineWidth(wi))
    }

    /** Widest line as the drawing code measures it (trailing spaces and newlines trimmed). */
    private fun widestLine(sl: StaticLayout): Float {
        var widest = 0f
        for (i in 0 until sl.lineCount) {
            val start = sl.getLineStart(i)
            var end = sl.getLineEnd(i)
            while (end > start && (text[end - 1] == '\n' || text[end - 1] == ' ')) end--
            if (end <= start) continue
            widest = maxOf(widest, exactWidth(paint, text.substring(start, end)))
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
        if (!fits(size, 1f)) {
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
        if (opt.justify >= 500f && sl.lineCount >= 2) {
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
            if (best != null) sl = best
        }
        layout = sl
        debug = describe(sl)
        return sl.height.toFloat()
    }

    /** Draws the laid-out text with its top at [top]. [h] is 0 left, 1 centre, 2 right. */
    fun draw(c: Canvas, left: Float, top: Float, h: Int) {
        val sl = layout ?: return
        val w = width.toFloat()
        val spaceW = exactWidth(paint, " ")
        val baseSpacing = paint.letterSpacing
        var maxRight = 0f
        var justifiedLines = 0
        for (i in 0 until sl.lineCount) {
            val start = sl.getLineStart(i)
            var end = sl.getLineEnd(i)
            while (end > start && (text[end - 1] == '\n' || text[end - 1] == ' ')) end--
            if (end <= start) continue
            val seg = text.substring(start, end)
            val baseline = top + sl.getLineBaseline(i)
            val lw = exactWidth(paint, seg)
            val endsParagraph = i == sl.lineCount - 1 || text[maxOf(sl.getLineEnd(i) - 1, 0)] == '\n'
            val gaps = seg.count { it == ' ' }
            val extra = w - lw
            var justified = false
            var spread = 0f
            if (h == 0 && sl.lineCount > 1 && gaps > 0 && extra > 0f) {
                if (!endsParagraph) {
                    justified = true
                    spread = extra / gaps
                } else if (opt.justify > 0f && extra / gaps <= spaceW * opt.justify / 100f) {
                    // Last line: spread a little over the letters first (up to 0.08 em), the rest over the gaps.
                    val perChar = minOf(extra / (seg.length * paint.textSize), 0.08f)
                    paint.letterSpacing = baseSpacing + perChar
                    val nw = exactWidth(paint, seg)
                    justified = true
                    spread = maxOf(0f, (w - nw) / gaps)
                }
            }
            if (!justified) {
                val x = left + when (h) {
                    2 -> w - lw
                    1 -> (w - lw) / 2f
                    else -> 0f
                }
                c.drawText(seg, x, baseline, paint)
                maxRight = maxOf(maxRight, x - left + lw)
            } else {
                justifiedLines++
                var x = left
                var from = 0
                while (from <= seg.length) {
                    var to = seg.indexOf(' ', from)
                    if (to < 0) to = seg.length
                    if (to > from) {
                        val word = seg.substring(from, to)
                        c.drawText(word, x, baseline, paint)
                        x += exactWidth(paint, word)
                    }
                    if (to >= seg.length) break
                    x += exactWidth(paint, " ") + spread
                    from = to + 1
                }
                maxRight = maxOf(maxRight, x - left)
            }
            paint.letterSpacing = baseSpacing
        }
        debug += " | drawnRight=%.1f justifiedLines=%d".format(maxRight, justifiedLines)
    }
}
