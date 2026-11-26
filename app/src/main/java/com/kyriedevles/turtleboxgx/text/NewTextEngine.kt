package com.kyriedevles.turtleboxgx.text

import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import com.kyriedevles.turtleboxgx.TextOptions
import com.kyriedevles.turtleboxgx.exactWidth
import com.kyriedevles.turtleboxgx.parseMarkup

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

/** [Metrics] read from a real paint. The same paints draw the text, so measuring and drawing cannot disagree. */
class PaintMetrics(base: TextPaint, size: Float, scaleX: Float) : Metrics {
    private val plain = TextPaint(base).also { it.textSize = size; it.textScaleX = scaleX }
    private val cache = arrayOfNulls<TextPaint>(4)
    private val fm = plain.fontMetrics

    fun paintFor(style: Int, letterEm: Float): TextPaint {
        val k = style and 3
        val p = cache[k] ?: TextPaint(plain).also {
            if (k != 0) StyleSpan(spanStyle(k)).updateDrawState(it)
            cache[k] = it
        }
        p.letterSpacing = letterEm
        return p
    }

    override fun advance(text: String, from: Int, to: Int, style: Int, letterEm: Float): Float =
        exactWidth(paintFor(style, letterEm), text.substring(from, to))

    override val top get() = fm.top
    override val ascent get() = fm.ascent
    override val descent get() = fm.descent
    override val bottom get() = fm.bottom
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
) {
    // <br> is a forced line break inside a paragraph (like Photoshop's Shift+Enter): the line before it is still justified.
    private val parsed = parseMarkup(rawText.replace("<br>", "\u2028", ignoreCase = true))
    // [[ ]] around a whole line: it never wraps; too long it squeezes, short it stretches to the full width.
    private val fixedLines = extractFixedLines(parsed.text, parsed.styles)
    private val styled = StyledTextView(fixedLines.text, fixedLines.styles)
    private val text = styled.text
    private val baseSize = paint.textSize
    private val hs = (opt.hScale.takeIf { it > 0f } ?: 100f) / 100f
    private val vs = (opt.vScale.takeIf { it > 0f } ?: 100f) / 100f
    private val core = LayoutCore(
        Source(text, styled.styles),
        { size, scaleX -> PaintMetrics(paint, size, scaleX) },
        boxWidth.toFloat(),
        boxHeight,
        LayoutOptions(
            condense = opt.condense, fitMode = opt.fitMode, minSizePct = opt.minSizePct, maxSizePct = opt.maxSizePct,
            minLines = opt.minLines, justify = opt.justify, letterSpace = opt.letterSpace, lastStretch = opt.lastStretch,
            hScale = opt.hScale, wordSpace = opt.wordSpace, leadingPx = opt.leadingPx, fontScale = opt.fontScale,
            flow = opt.flow, spacing = opt.spacing,
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
        val pm = PaintMetrics(paint, r.size, r.scaleX)
        val styles = styled.styles
        var wi = 0
        for (line in r.lines) {
            val baseline = bodyTop + line.baseline - opt.baselineShift
            val em = opt.letterSpace + line.letterExtraEm
            val lineSaved = c.save()
            if (vs != 1f) c.scale(1f, vs, left, baseline)
            if (line.scaleX != 1f) c.scale(line.scaleX, 1f, left, baseline)
            while (wi < r.words.size && r.words[wi].line == line.index) {
                val w = r.words[wi++]
                var x = left + w.x
                var a = w.start
                while (a < w.end) {
                    val st = styles[a]
                    var b = a + 1
                    while (b < w.end && styles[b] == st) b++
                    val p = pm.paintFor(st, em)
                    c.drawText(text, a, b, x, baseline, p)
                    x += exactWidth(p, text.substring(a, b))
                    a = b
                }
            }
            c.restoreToCount(lineSaved)
        }
        c.restoreToCount(saved)
    }
}
