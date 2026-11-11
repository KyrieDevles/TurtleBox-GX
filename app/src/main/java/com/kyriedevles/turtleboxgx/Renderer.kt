package com.kyriedevles.turtleboxgx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout as TextLayout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Phase 1b renderer: draws every visible element of the layout in z-order.
 * Text uses plain wrapping with a simple shrink-to-fit. The real text engine (condense, justify, kerning) is phase 3.
 */
class Renderer(private val layout: Layout, private val ctx: Ctx, private val assets: Assets) {
    var drawn = 0
        private set

    fun render(scale: Float): Bitmap {
        val bmp = Bitmap.createBitmap(
            (layout.width * scale).toInt(),
            (layout.height * scale).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        val sorted = layout.elements.sortedWith(compareBy<Element> { it.index }.thenBy { it.order })
        for (e in sorted) {
            val vis = e.visible
            if (vis != null && !Expr.eval(vis, ctx)) continue
            try {
                if (e.kind == "image") drawImage(c, e) else drawText(c, e)
            } catch (ex: Exception) {
                assets.missing.add("${e.kind} element failed: ${ex.javaClass.simpleName}")
            }
        }
        return bmp
    }

    private fun <T> pick(list: List<Cond<T>>): T? {
        for (cnd in list) {
            val w = cnd.whenExpr
            if (w == null || Expr.eval(w, ctx)) return cnd.value
        }
        return null
    }

    private fun drawImage(c: Canvas, e: Element) {
        val size = pick(e.sizes) ?: return
        val rect = RectF(size.left, size.top, size.left + size.width, size.top + size.height)
        e.attrs["background"]?.let { bg ->
            try {
                val p = Paint().apply { color = Color.parseColor(bg) }
                c.drawRect(rect, p)
            } catch (ex: IllegalArgumentException) {
            }
        }
        val src = pick(e.srcs) ?: return
        val bmp = assets.bitmap(ctx.sub(src)) ?: return
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        c.drawBitmap(bmp, null, rect, paint)
        drawn++
    }

    private fun drawText(c: Canvas, e: Element) {
        val size = pick(e.sizes) ?: return
        val text = ctx.sub(e.text)
        if (text.isBlank()) return
        val font = pick(e.fonts)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        font?.let { f -> assets.font(f.name)?.let { paint.typeface = it } }
        if (font?.style?.contains("bold", ignoreCase = true) == true) paint.isFakeBoldText = true
        paint.color = try {
            Color.parseColor(pick(e.colors) ?: "#000000")
        } catch (ex: IllegalArgumentException) {
            Color.BLACK
        }
        val fontSize = font?.size ?: 12f
        paint.textSize = fontSize

        val flags = (pick(e.aligns) ?: "left").lowercase().split('|')
        val h = when {
            "right" in flags -> 2
            "center" in flags || "hcenter" in flags -> 1
            else -> 0
        }
        var v = when {
            "vcenter" in flags || "center" in flags -> 0.5f
            "bottom" in flags -> 1f
            else -> 0f
        }
        e.options["valign"]?.let { raw ->
            ctx.sub(raw).toFloatOrNull()?.let { v = it }
        }
        ctx.sub(e.options["weight"] ?: "").toFloatOrNull()?.let { w ->
            if (w > 0f) {
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = fontSize * w / 1000f
            }
        }

        val width = size.width.toInt().coerceAtLeast(1)
        if (e.attrs["singleline"] == "true") {
            val measured = paint.measureText(text)
            if (measured > size.width && measured > 0f) paint.textScaleX = (size.width / measured).coerceAtLeast(0.5f)
            val drawW = paint.measureText(text)
            val fm = paint.fontMetrics
            val x = when (h) {
                2 -> size.left + size.width - drawW
                1 -> size.left + (size.width - drawW) / 2f
                else -> size.left
            }
            val y = size.top + (size.height - (fm.descent - fm.ascent)) * v - fm.ascent
            c.drawText(text, x, y, paint)
            drawn++
            return
        }

        val align = when (h) {
            2 -> TextLayout.Alignment.ALIGN_OPPOSITE
            1 -> TextLayout.Alignment.ALIGN_CENTER
            else -> TextLayout.Alignment.ALIGN_NORMAL
        }
        val spacing = ctx.sub(e.options["linespace"] ?: "").toFloatOrNull()?.takeIf { it > 0f } ?: 1f
        var s = fontSize
        var sl = build(text, paint, width, align, spacing)
        while (sl.height > size.height && s > fontSize * 0.5f) {
            s -= 0.25f
            paint.textSize = s
            sl = build(text, paint, width, align, spacing)
        }
        c.save()
        c.translate(size.left, size.top + (size.height - sl.height).coerceAtLeast(0f) * v)
        sl.draw(c)
        c.restore()
        drawn++
    }

    private fun build(text: String, paint: TextPaint, width: Int, align: TextLayout.Alignment, spacing: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()
}
