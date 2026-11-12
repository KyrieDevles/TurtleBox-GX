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
class Hit(val click: String, val rect: RectF)

class Renderer(private val layout: Layout, private val ctx: Ctx, private val assets: Assets) {
    var drawn = 0
        private set

    /** Areas that react to a tap, in drawing order (the last one is on top). */
    val hits = ArrayList<Hit>()

    /** Debug notes shown under the card. */
    val notes = ArrayList<String>()

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
        val click = e.attrs["click"]
        e.attrs["background"]?.let { bg ->
            try {
                val p = Paint().apply { color = Color.parseColor(bg) }
                c.drawRect(rect, p)
            } catch (ex: IllegalArgumentException) {
            }
        }
        val src = pick(e.srcs)
        if (src == null) {
            click?.let { hits.add(Hit(it, rect)) }
            return
        }
        val bmp = assets.bitmap(ctx.sub(src))
        if (bmp == null) {
            click?.let { hits.add(Hit(it, rect)) }
            return
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        var dest = rect
        if (e.attrs["scaleType"].equals("FIT_CENTER", ignoreCase = true) && bmp.width > 0 && bmp.height > 0) {
            val k = minOf(rect.width() / bmp.width, rect.height() / bmp.height)
            val w = bmp.width * k
            val h = bmp.height * k
            dest = RectF(rect.centerX() - w / 2f, rect.centerY() - h / 2f, rect.centerX() + w / 2f, rect.centerY() + h / 2f)
        }
        c.drawBitmap(bmp, null, dest, paint)
        click?.let { hits.add(Hit(it, dest)) }
        drawn++
    }

    private fun drawText(c: Canvas, e: Element) {
        val size = pick(e.sizes) ?: return
        e.attrs["click"]?.let { hits.add(Hit(it, RectF(size.left, size.top, size.left + size.width, size.top + size.height))) }
        val text = ctx.sub(e.text)
        if (text.isBlank()) return
        val font = pick(e.fonts)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
        if (android.os.Build.VERSION.SDK_INT >= 33) paint.hinting = Paint.HINTING_OFF
        font?.let { f ->
            val tf = assets.font(f.name)
            if (tf != null) paint.typeface = tf
            if (f.name.contains("LINK", ignoreCase = true)) notes.add("LINK font ${f.name}: ${if (tf != null) "loaded" else "NOT loaded"}, text \"$text\"")
        }
        if (font?.style?.contains("bold", ignoreCase = true) == true) paint.isFakeBoldText = true
        if (font?.style?.contains("italic", ignoreCase = true) == true) paint.textSkewX = -0.25f
        paint.color = try {
            Color.parseColor(ctx.sub(pick(e.colors) ?: "#000000").trim())
        } catch (ex: IllegalArgumentException) {
            Color.BLACK
        }
        val fontSize = font?.size ?: 12f
        paint.textSize = fontSize

        // Same rules as the old app: align tokens become Android gravity flags. Without a vertical token the
        // text sits at the top (TextView adds TOP), and "top"/"bottom" are not recognised, so they count as top.
        val flags = (pick(e.aligns) ?: "left").lowercase().split('|').map { it.trim() }
        val h = when {
            "right" in flags -> 2
            "left" in flags -> 0
            "center" in flags || "hcenter" in flags || "center_horizontal" in flags -> 1
            else -> 0
        }
        val singleLine = e.attrs["singleline"] == "true"
        var v = when {
            "vcenter" in flags || "center" in flags || "center_vertical" in flags -> 0.5f
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
        if (singleLine) {
            val measured = exactWidth(paint, text)
            if (measured > size.width && measured > 0f) paint.textScaleX = (size.width / measured).coerceAtLeast(0.5f)
            val drawW = exactWidth(paint, text)
            val fm = paint.fontMetrics
            val lineH = fm.bottom - fm.top
            val x = when (h) {
                2 -> size.left + size.width - drawW
                1 -> size.left + (size.width - drawW) / 2f
                else -> size.left
            }
            val y = size.top + (size.height - lineH) * v - fm.top
            c.drawText(text, x, y, paint)
            drawn++
            return
        }

        fun num(key: String): Float = ctx.sub(e.options[key] ?: "").trim().toFloatOrNull() ?: 0f
        val baseSpacing = e.attrs["line-space-scale"]?.toFloatOrNull() ?: 1f
        val spacing = baseSpacing * (num("linespace").takeIf { it > 0f } ?: 1f)
        val opt = TextOptions(
            condense = num("condense"),
            fitMode = num("fitmode").let { if (it <= 0f) 1 else (it + 0.5f).toInt() },
            minSizePct = num("minsize"),
            maxSizePct = num("maxsize"),
            minLines = num("minline"),
            justify = num("justify"),
            letterSpace = num("letterspace"),
            spacing = spacing,
        )
        var body = indentText(text, num("indent"))
        // keepWord: do not break inside a word. The line breaker allows a break after "/", so glue it with a word joiner.
        // Not after the "/" of a closing tag (</i>, </b>): the joiner would break the tag before the engine reads it.
        if (e.attrs["keepWord"] == "true") body = body.replace(Regex("(?<!<)/"), "/\u2060")
        // Optional <materials> child: its own first line in this box (Fusion / Synchro / Xyz / Link materials).
        val mats = ctx.sub(e.options["materials"] ?: "").replace("\n", " ").trim()
        val matPct = num("materialssize").takeIf { it > 0f } ?: 100f
        val matWidthPct = num("materialswidth").takeIf { it > 0f } ?: 100f
        val matLinePct = num("materialsline").takeIf { it > 0f } ?: 100f
        val engine = TextEngine(body, paint, width, size.height, opt, mats, matPct, matWidthPct, matLinePct)
        val used = engine.fit()
        engine.draw(c, size.left, size.top + (size.height - used).coerceAtLeast(0f) * v, h)
        drawn++
    }

    /** First-line indent: non-breaking spaces at the start of every paragraph, like the old app (1 to 8). */
    private fun indentText(s: String, amount: Float): String {
        val n = (amount + 0.5f).toInt().coerceAtMost(8)
        if (amount <= 0f || n < 1) return s
        val pad = "\u00a0".repeat(n)
        return pad + s.replace("\n", "\n" + pad)
    }
}
