package com.kyriedevles.turtleboxgx.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import com.kyriedevles.turtleboxgx.TextOptions
import com.kyriedevles.turtleboxgx.exactWidth

private class StyledTextView(val text: String, val styles: IntArray)

/** Which text engine draws multi-line text boxes. The old one stays available while the new one is proven. */
object TextSwitch {
    @JvmField var useNew: Boolean = false
}

private fun spanStyle(v: Int): Int = when (v and 3) {
    1 -> Typeface.BOLD
    2 -> Typeface.ITALIC
    3 -> Typeface.BOLD_ITALIC
    else -> Typeface.NORMAL
}

// Super/subscript and small caps proportions. These are InDesign's documented defaults (58.3% size, 33.3% shift, 70% small
// caps); Photoshop's own values were not checked, so they are named here to be easy to change.
private const val SCRIPT_SIZE = 0.583f
private const val SCRIPT_SHIFT = 0.333f
private const val SMALL_CAPS_SIZE = 0.7f

/** Calls [block] for each stretch of text[from, to) that is all lowercase or all not lowercase. */
private inline fun runsByCase(text: String, from: Int, to: Int, block: (Int, Int, Boolean) -> Unit) {
    var a = from
    while (a < to) {
        val lower = text[a].isLowerCase()
        var b = a + 1
        while (b < to && text[b].isLowerCase() == lower) b++
        block(a, b, lower)
        a = b
    }
}

/** [Metrics] read from a real paint. The same paints draw the text, so measuring and drawing cannot disagree. */
class PaintMetrics(
    base: TextPaint,
    private val size: Float,
    scaleX: Float,
    private val extras: List<RunExtra> = emptyList(),
    private val fonts: (String) -> Typeface? = { null },
) : Metrics {
    private val plain = TextPaint(base).also { it.textSize = size; it.textScaleX = scaleX }
    private val cache = HashMap<Int, TextPaint>()
    private val smallCache = HashMap<Int, TextPaint>()
    private val fm = plain.fontMetrics
    private val fmi = plain.fontMetricsInt

    /** What a style id carries beyond bold and italic, or null. */
    fun extra(style: Int): RunExtra? = extraOf(style, extras)

    private fun build(style: Int, sizeMul: Float): TextPaint {
        val ex = extra(style)
        val p = TextPaint(plain)
        ex?.font?.let { f -> fonts(f)?.let { p.typeface = it } }
        val k = style and 3
        if (k != 0) StyleSpan(spanStyle(k)).updateDrawState(p)
        ex?.color?.let { p.color = it }
        var mul = sizeMul
        if (ex != null && ex.script != 0) mul *= SCRIPT_SIZE
        p.textSize = size * mul
        return p
    }

    fun paintFor(style: Int, letterEm: Float): TextPaint {
        val p = cache.getOrPut(style) { build(style, 1f) }
        p.letterSpacing = letterEm
        return p
    }

    /** The paint for the lowercase letters of a small-caps run: capitals drawn smaller. */
    fun smallPaintFor(style: Int, letterEm: Float): TextPaint {
        val p = smallCache.getOrPut(style) { build(style, SMALL_CAPS_SIZE) }
        p.letterSpacing = letterEm
        return p
    }

    override fun advance(text: String, from: Int, to: Int, style: Int, letterEm: Float): Float {
        val ex = extra(style)
        if (ex == null || !ex.smallCaps) return exactWidth(paintFor(style, letterEm), text.substring(from, to))
        var w = 0f
        runsByCase(text, from, to) { a, b, lower ->
            w += if (lower) exactWidth(smallPaintFor(style, letterEm), text.substring(a, b).uppercase())
            else exactWidth(paintFor(style, letterEm), text.substring(a, b))
        }
        return w
    }

    override val top get() = fm.top
    override val ascent get() = fm.ascent
    override val descent get() = fm.descent
    override val bottom get() = fm.bottom
    override val lineTop get() = fmi.top.toFloat()
    override val lineAscent get() = fmi.ascent.toFloat()
    override val lineDescent get() = fmi.descent.toFloat()
    override val lineBottom get() = fmi.bottom.toFloat()
}

/**
 * Same job and same entry points as the old TextEngine (fit, then draw), but the lines are broken, justified and
 * placed by [LayoutCore], and drawn exactly where it put them.
 */
class NewTextEngine(
    rawText: String,
    private val paint: TextPaint,
    private val boxWidth: Int,
    private val boxHeight: Float,
    private val opt: TextOptions,
    private val materials: String = "",
    private val materialsPct: Float = 100f,
    private val materialsWidthPct: Float = 100f,
    private val materialsLinePct: Float = 100f,
    /** Finds the typeface a <font=name> tag asks for; null when the name is unknown (the box's own font is kept). */
    private val fontLookup: (String) -> Typeface? = { null },
) {
    // <br> is a forced line break inside a paragraph (like Photoshop's Shift+Enter): the line before it is still justified.
    private val parsed = parseRich(rawText.replace("<br>", "\u2028", ignoreCase = true))
    // [[ ]] around a whole line: it never wraps; too long it squeezes, short it stretches to the full width.
    private val fixedLines = extractFixedLines(parsed.text, parsed.styles)
    private val styled = StyledTextView(fixedLines.text, fixedLines.styles)
    private val text = styled.text
    private val baseSize = paint.textSize
    private val hs = (opt.hScale.takeIf { it > 0f } ?: 100f) / 100f
    private val vs = (opt.vScale.takeIf { it > 0f } ?: 100f) / 100f
    private val core = LayoutCore(
        Source(text, styled.styles),
        { size, scaleX -> PaintMetrics(paint, size, scaleX, parsed.extras, fontLookup) },
        boxWidth.toFloat(),
        boxHeight,
        LayoutOptions(
            condense = opt.condense, fitMode = opt.fitMode, minSizePct = opt.minSizePct, maxSizePct = opt.maxSizePct,
            minLines = opt.minLines, justify = opt.justify, letterSpace = opt.letterSpace, lastStretch = opt.lastStretch,
            hScale = opt.hScale, wordSpace = opt.wordSpace, leadingPx = opt.leadingPx, fontScale = opt.fontScale,
            flow = opt.flow, spacing = opt.spacing,
            indentLeft = opt.indentLeft, indentRight = opt.indentRight, indentFirst = opt.indentFirst,
            spaceBefore = opt.spaceBefore, spaceAfter = opt.spaceAfter,
            wordSpaceMin = opt.wordSpaceMin, wordSpaceMax = opt.wordSpaceMax, composer = opt.composer,
        ),
        { size -> materialsHeight(size) },
        baseSize,
        fixedLines.spans,
    )
    private var result: LayoutResult? = null
    private var placedAlign = 0
    private var matLayout: StaticLayout? = null
    private var matScale = 1f
    private var matH = 0f
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun matBase(bodySize: Float): Float = if (opt.fontScale > 0f) baseSize else bodySize

    private fun matPaint(bodySize: Float): TextPaint = TextPaint(paint).also {
        it.letterSpacing = 0f
        it.textSize = matBase(bodySize) * materialsPct / 100f
    }

    private fun buildMaterials(p: TextPaint): StaticLayout =
        StaticLayout.Builder.obtain(materials, 0, materials.length, p, 100000)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, opt.spacing)
            .setIncludePad(true)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .build()

    private fun materialsHeight(bodySize: Float): Float {
        if (materials.isEmpty()) return 0f
        return buildMaterials(matPaint(bodySize)).height.toFloat() * materialsLinePct / 100f
    }

    /** Chooses the size and squeeze and lays the text out. Returns the height used, materials row included. */
    fun fit(): Float {
        core.align = 0
        placedAlign = 0
        val r = core.layout()
        result = r
        matLayout = null
        matH = 0f
        if (materials.isNotEmpty()) {
            val p = matPaint(r.size)
            p.textScaleX = 1f
            val natural = exactWidth(p, materials)
            // The width setting stretches or squeezes it; it never goes past the box, so a long line still fits on one line.
            matScale = if (natural > 0f) minOf(materialsWidthPct / 100f, boxWidth / natural).coerceAtLeast(0.3f) else 1f
            val ml = buildMaterials(p)
            matLayout = ml
            matH = ml.height.toFloat() * materialsLinePct / 100f
        }
        return r.textHeight + matH
    }

    private fun drawMaterials(c: Canvas, left: Float, top: Float, h: Int, size: Float, ml: StaticLayout) {
        val p = matPaint(size)
        p.textScaleX = matScale
        val mw = exactWidth(p, materials)
        val x = left + when (h) {
            2 -> boxWidth - mw
            1 -> (boxWidth - mw) / 2f
            else -> 0f
        }
        c.drawText(materials, x, top + ml.getLineBaseline(0), p)
    }

    /**
     * Draws text[a, b), all in style [st], with its left edge at [x]. Small caps draw the lowercase letters as smaller
     * capitals; superscript and subscript sit above or below the baseline. Returns the advance.
     */
    private fun drawRun(c: Canvas, pm: PaintMetrics, a: Int, b: Int, x: Float, baseline: Float, st: Int, em: Float, bodySize: Float): Float {
        val ex = pm.extra(st)
        val y = when (ex?.script) {
            1 -> baseline - bodySize * SCRIPT_SHIFT
            2 -> baseline + bodySize * SCRIPT_SHIFT
            else -> baseline
        }
        if (ex == null || !ex.smallCaps) {
            val p = pm.paintFor(st, em)
            c.drawText(text, a, b, x, y, p)
            return exactWidth(p, text.substring(a, b))
        }
        var cx = x
        runsByCase(text, a, b) { s, e, lower ->
            if (lower) {
                val p = pm.smallPaintFor(st, em)
                val seg = text.substring(s, e).uppercase()
                c.drawText(seg, cx, y, p)
                cx += exactWidth(p, seg)
            } else {
                val p = pm.paintFor(st, em)
                c.drawText(text, s, e, cx, y, p)
                cx += exactWidth(p, text.substring(s, e))
            }
        }
        return cx - x
    }

    /** Underline or strikethrough from [x0] to [x1], using the run's own font metrics where the system gives them. */
    private fun drawLine(c: Canvas, p: TextPaint, baseline: Float, x0: Float, x1: Float, strike: Boolean) {
        if (x1 <= x0) return
        val sz = p.textSize
        val off: Float
        val thick: Float
        if (Build.VERSION.SDK_INT >= 29) {
            off = if (strike) p.strikeThruPosition else p.underlinePosition
            thick = if (strike) p.strikeThruThickness else p.underlineThickness
        } else {
            off = if (strike) -sz * 0.3f else sz * 0.12f
            thick = sz / 16f
        }
        // Card px: 1 px here is 2-3 px in the exported image, so allow a thin line.
        val t = thick.coerceAtLeast(0.4f)
        linePaint.color = p.color
        val top = baseline + off
        c.drawRect(x0, top, x1, top + t, linePaint)
    }

    /** Draws the laid-out text with its top at [top]. [h] is 0 left, 1 centre, 2 right. */
    fun draw(c: Canvas, left: Float, top: Float, h: Int) {
        var r = result ?: return
        if (h != placedAlign) {
            core.align = h
            r = core.place(r.size, r.scaleX)
            result = r
            placedAlign = h
        }
        matLayout?.let { drawMaterials(c, left, top, h, r.size, it) }
        val saved = c.save()
        if (hs != 1f) c.scale(hs, 1f, left, top)
        val bodyTop = top + matH
        val pm = PaintMetrics(paint, r.size, r.scaleX, parsed.extras, fontLookup)
        val styles = styled.styles
        var wi = 0
        for (line in r.lines) {
            val baseline = bodyTop + line.baseline - opt.baselineShift
            val em = opt.letterSpace + line.letterExtraEm
            val lineSaved = c.save()
            if (vs != 1f) c.scale(1f, vs, left, baseline)
            if (line.scaleX != 1f) c.scale(line.scaleX, 1f, left + line.originX, baseline)
            while (wi < r.words.size && r.words[wi].line == line.index) {
                val w = r.words[wi++]
                val next = r.words.getOrNull(wi)?.takeIf { it.line == line.index }
                var x = left + w.x
                var a = w.start
                while (a < w.end) {
                    val st = styles[a]
                    var b = a + 1
                    while (b < w.end && styles[b] == st) b++
                    val adv = drawRun(c, pm, a, b, x, baseline, st, em, r.size)
                    val ex = pm.extra(st)
                    if (ex != null && (ex.underline || ex.strike)) {
                        // Underline and strikethrough carry on across a space that has the same style, so a marked
                        // phrase reads as one line instead of one dash per word.
                        var endU = x + adv
                        var endS = x + adv
                        if (b >= w.end && next != null && w.end < text.length && text[w.end] == ' ') {
                            val sx = pm.extra(styles[w.end])
                            if (sx != null) {
                                if (sx.underline) endU = left + next.x
                                if (sx.strike) endS = left + next.x
                            }
                        }
                        val p = pm.paintFor(st, em)
                        val lineY = when (ex.script) {
                            1 -> baseline - r.size * SCRIPT_SHIFT
                            2 -> baseline + r.size * SCRIPT_SHIFT
                            else -> baseline
                        }
                        if (ex.underline) drawLine(c, p, lineY, x, endU, false)
                        if (ex.strike) drawLine(c, p, lineY, x, endS, true)
                    }
                    x += adv
                    a = b
                }
            }
            c.restoreToCount(lineSaved)
        }
        c.restoreToCount(saved)
    }
}
