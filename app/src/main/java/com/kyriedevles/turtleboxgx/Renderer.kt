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

class Renderer(private val layout: Layout, private val ctx: Ctx, private val assets: Assets, private val values: Map<String, String> = emptyMap()) {
    /** A layer that is being edited on screen is left out here; the screen draws it live instead. */
    var hideLayerId: String? = null

    /** While a layer is edited: part 1 draws only what is behind index [splitZ], part 2 (on a transparent background) only what is in front of it. */
    var splitZ: Float = 0f
    var part: Int = 0

    var drawn = 0
        private set

    /** Areas that react to a tap, in drawing order (the last one is on top). */
    val hits = ArrayList<Hit>()

    /** The natural (layout) rectangle of every text box that can be moved, by its boxdata name. */
    val boxes = HashMap<String, RectF>()

    /** Fit steps of the text boxes drawn, for the edit menu (see [AutoFit]). */
    val fitGroups = ArrayList<AutoFit.Group>()

    /** Text field to report letter positions for (see [TextProbe]); the results are below after [render]. */
    var probeName: String? = null
    var probeRects: Array<RectF?>? = null
        private set
    var probeText: String = ""
        private set
    /** The text box drawn, in card px (or the text layer's own unrotated coordinates, see [probeLayer]). */
    var probeBox: RectF? = null
        private set
    var probeLayer: Layer? = null
        private set
    /** The probed box has hidden lines. */
    var probeOverset = false
        private set
    private var drawingLayer: Layer? = null

    /** Text boxes whose last lines did not fit and were hidden (Photoshop's overset text), for the status under the card. */
    val overset = ArrayList<String>()

    /** Natural size (card px) of each text layer drawn: the line for point text, the box for box text. */
    val textSizes = HashMap<String, Pair<Float, Float>>()

    /** Debug notes shown under the card. */
    val notes = ArrayList<String>()

    private var curScale = 1f

    fun render(scale: Float): Bitmap {
        curScale = scale
        val bmp = Bitmap.createBitmap(
            (layout.width * scale).toInt(),
            (layout.height * scale).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(bmp)
        if (part != 2) c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        val sorted = layout.elements.filter { part == 0 || (part == 1) == (it.index <= splitZ) }.sortedWith(compareBy<Element> { it.index }.thenBy { it.order })
        val layers = ImageLayers.read(values).filter { it.vis && it.id != hideLayerId && (part == 0 || (part == 1) == (it.z <= splitZ)) }.sortedBy { it.z }
        var li = 0
        for (e in sorted) {
            while (li < layers.size && layers[li].z < e.index) drawLayer(c, layers[li++])
            val vis = e.visible
            if (vis != null && !Expr.eval(vis, ctx)) continue
            try {
                if (e.kind == "image") drawImage(c, e) else drawText(c, e)
            } catch (ex: Exception) {
                assets.missing.add("${e.kind} element failed: ${ex.javaClass.simpleName}")
            }
        }
        while (li < layers.size) drawLayer(c, layers[li++])
        return bmp
    }

    private fun drawLayer(c: Canvas, l: Layer) {
        if (l.isText) return drawTextLayer(c, l)
        val bmp = assets.bitmap(l.file) ?: return
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = (l.opacity.coerceIn(0f, 1f) * 255).toInt() }
        c.save()
        c.translate(l.cx, l.cy)
        c.rotate(l.rot)
        c.scale(if (l.mx) -1f else 1f, if (l.fy) -1f else 1f)
        c.drawBitmap(bmp, null, RectF(-l.w / 2f, -l.h / 2f, l.w / 2f, l.h / 2f), paint)
        c.restore()
        drawn++
    }

    // ---- text layers (see TextLayers) ----

    private fun layerValue(key: String, l: Layer): String {
        val v = ctx.value("${key}_${TextLayers.tm(l.id)}")
        return if (v.startsWith("{{")) "" else v.trim()
    }

    private fun layerText(l: Layer): String {
        var t = ctx.value("text_${TextLayers.tm(l.id)}").let { if (it.startsWith("{{")) "" else it }
        if (layerValue("allcaps", l).equals("on", true)) t = com.kyriedevles.turtleboxgx.text.uppercaseOutsideTags(t)
        return t
    }

    /** The paint of a point text layer: font, size, colour and the Characters settings that apply to one line. */
    private fun pointPaint(l: Layer): TextPaint {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
        if (android.os.Build.VERSION.SDK_INT >= 33) p.hinting = Paint.HINTING_OFF
        assets.font(TextLayers.fontFile(layout, values, l.id))?.let { p.typeface = it }
        p.textSize = layerValue("sizepx", l).toFloatOrNull()?.takeIf { it > 0f } ?: TextLayers.DEFAULT_SIZE
        p.color = try { Color.parseColor(layerValue("color", l)) } catch (e: Exception) { Color.BLACK }
        if (layerValue("italic", l).equals("on", true)) p.textSkewX = -0.25f
        layerValue("tracking", l).toFloatOrNull()?.let { p.letterSpacing = it / 1000f }
        layerValue("hscale", l).toFloatOrNull()?.takeIf { it > 0f }?.let { p.textScaleX = it / 100f }
        layerValue("weight", l).toFloatOrNull()?.takeIf { it > 0f }?.let { w ->
            p.style = Paint.Style.FILL_AND_STROKE
            p.strokeJoin = Paint.Join.ROUND
            p.strokeWidth = p.textSize * w / 1000f
        }
        return p
    }

    private fun pointLines(l: Layer): List<String> = layerText(l).replace(Regex("<br>", RegexOption.IGNORE_CASE), "\n").split('\n')

    /** Line pitch of point text: Leading px, else Photoshop's automatic 120% of the size. */
    private fun pointLeading(l: Layer, p: Paint): Float = layerValue("leadingpx", l).toFloatOrNull()?.takeIf { it > 0f } ?: p.textSize * 1.2f

    /**
     * Which side of point text stays put when the text changes, from its Alignment: 0 left, 1 centre, 2 right (Photoshop
     * anchors point text at its click point the same way). Lines are aligned to each other the same way.
     */
    fun pointAnchor(l: Layer): Int = when (layerValue("halign", l).lowercase()) {
        "hcenter", "center", "justcenter" -> 1
        "right", "justright" -> 2
        else -> 0
    }

    /** Natural size of a point text layer: its widest line, and its lines' height (vertical scale included). */
    fun pointSize(l: Layer): Pair<Float, Float> {
        val p = pointPaint(l)
        val fm = p.fontMetrics
        val vs = layerValue("vscale", l).toFloatOrNull()?.takeIf { it > 0f }?.div(100f) ?: 1f
        val lines = pointLines(l)
        val w = lines.maxOf { exactWidth(p, it) }.coerceAtLeast(p.textSize * 0.5f)
        val h = (fm.descent - fm.ascent) + (lines.size - 1) * pointLeading(l, p)
        return w to h * vs
    }

    /** Draws a text layer centred on (0, 0), unrotated. Returns its natural size. */
    private fun drawTextLayerContent(c: Canvas, l: Layer): Pair<Float, Float> {
        if (!TextLayers.isPoint(values, l.id)) {
            val e = TextLayers.boxElement(l, layout, values)
            drawText(c, e)
            return l.w to l.h
        }
        val p = pointPaint(l)
        val (tw, th) = pointSize(l)
        val lines = pointLines(l)
        if (lines.all { it.isBlank() }) return tw to th
        val fm = p.fontMetrics
        val vs = layerValue("vscale", l).toFloatOrNull()?.takeIf { it > 0f }?.div(100f) ?: 1f
        val shift = layerValue("baseline", l).toFloatOrNull() ?: 0f
        val lead = pointLeading(l, p)
        val anchor = pointAnchor(l)
        val fx = NameStyle.resolve(layerValue("style", l), layout, ctx.value("namepresets_user").takeIf { u -> !u.startsWith("{{") } ?: "")
        c.save()
        c.translate(0f, -shift)
        c.scale(1f, vs, 0f, 0f)
        val top = -th / vs / 2f
        for ((i, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val lw = exactWidth(p, line)
            val x = -tw / 2f + when (anchor) { 1 -> (tw - lw) / 2f; 2 -> tw - lw; else -> 0f }
            val y = top - fm.ascent + i * lead
            if (fx != null) {
                NameFx.draw(c, line, x, y, p, RectF(x, y + fm.ascent, x + lw, y + fm.descent), lw, fx, curScale, layout.width, layout, assets)
            } else {
                c.drawText(line, x, y, p)
            }
        }
        c.restore()
        return tw to th
    }

    /**
     * Where a point text layer's text sits in its box: the side its Alignment anchors stays at the box's side, and the
     * first line at the box's top, so editing the text grows it from there instead of from the middle.
     */
    private fun pointOffset(l: Layer, size: Pair<Float, Float>): Pair<Float, Float> {
        val dx = when (pointAnchor(l)) {
            1 -> 0f
            2 -> l.w / 2f - size.first / 2f
            else -> -l.w / 2f + size.first / 2f
        }
        return dx to (-l.h / 2f + size.second / 2f)
    }

    private fun drawTextLayer(c: Canvas, l: Layer) {
        val point = TextLayers.isPoint(values, l.id)
        val off = if (point) pointOffset(l, pointSize(l)) else 0f to 0f
        c.save()
        c.translate(l.cx, l.cy)
        c.rotate(l.rot)
        c.scale(if (l.mx) -1f else 1f, if (l.fy) -1f else 1f)
        c.translate(off.first, off.second)
        val alpha = (l.opacity.coerceIn(0f, 1f) * 255).toInt()
        if (alpha < 255) c.saveLayerAlpha(null, alpha)
        drawingLayer = l
        val size = try { drawTextLayerContent(c, l) } catch (e: Exception) { assets.missing.add("text layer failed: ${e.javaClass.simpleName}"); l.w to l.h }
        drawingLayer = null
        c.restore()
        textSizes[l.id] = size
        // Tap area: the rotated box's bounds.
        val m = android.graphics.Matrix()
        m.postTranslate(off.first, off.second)
        m.postRotate(l.rot)
        m.postTranslate(l.cx, l.cy)
        val r = RectF(-size.first / 2f, -size.second / 2f, size.first / 2f, size.second / 2f)
        m.mapRect(r)
        hits.add(Hit("layermenu_${TextLayers.tm(l.id)}", r))
        drawn++
    }

    /** A text layer alone on a transparent picture at its natural size (the preview moved while editing it on the card). */
    fun renderTextLayer(l: Layer, scale: Float): Pair<Bitmap, Pair<Float, Float>> {
        curScale = scale
        val size = if (TextLayers.isPoint(values, l.id)) pointSize(l) else l.w to l.h
        val bmp = Bitmap.createBitmap(Math.ceil((size.first * scale).toDouble()).toInt().coerceAtLeast(1), Math.ceil((size.second * scale).toDouble()).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        c.translate(size.first / 2f, size.second / 2f)
        drawingLayer = l
        drawTextLayerContent(c, l)
        drawingLayer = null
        return bmp to size
    }

    /** Small "Card Name" samples of name styles (for the Name Style panel), drawn with the layout's name font and colour. */
    fun previewName(maps: List<Map<String, String>?>, w: Int, h: Int): List<Bitmap> {
        val e = layout.elements.firstOrNull { it.options.containsKey("namestyle") }
        val out = ArrayList<Bitmap>()
        val font = e?.let { pick(it.fonts) }
        val sample = "Card Name"
        for (m in maps) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
            font?.let { f -> assets.font(f.name)?.let { paint.typeface = it } }
            paint.color = try {
                Color.parseColor(ctx.sub(e?.let { pick(it.colors) } ?: "#000000").trim())
            } catch (ex: Exception) {
                Color.BLACK
            }
            paint.textSize = font?.size ?: 50f
            m?.get("font")?.takeIf { it.isNotBlank() }?.let { fn ->
                val file = layout.datas["namefonts"]?.items?.firstOrNull { it.first == fn }?.second ?: fn
                assets.font(file)?.let { paint.typeface = it }
            }
            val tw = exactWidth(paint, sample)
            val k = ((w - 16f) / (tw + 8f)).coerceAtMost(h / (paint.textSize * 1.5f))
            c.scale(k, k)
            val wu = w / k
            val hu = h / k
            val fm = paint.fontMetrics
            val x = (wu - tw) / 2f
            val tb = android.graphics.Rect()
            paint.getTextBounds(sample, 0, sample.length, tb)
            val y = hu / 2f - (tb.top + tb.bottom) / 2f
            if (m != null) {
                NameFx.draw(c, sample, x, y, paint, RectF(0f, 0f, wu, hu), tw, m, k, layout.width, layout, assets)
            } else {
                c.drawText(sample, x, y, paint)
            }
            out.add(bmp)
        }
        return out
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

    /** "left,top,width,height" as typed into a box option, or null. */
    private fun parseBox(v: String?): Rect4? {
        val p = v?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: return null
        return if (p.size == 4 && p[2] > 0f && p[3] > 0f) Rect4(p[0], p[1], p[2], p[3]) else null
    }

    private fun drawText(c: Canvas, e: Element) {
        var size = pick(e.sizes) ?: return
        // A text element with boxdata="name" can be moved and resized by the user; the card keeps the new box.
        e.attrs["boxdata"]?.let { bd ->
            boxes[bd] = RectF(size.left, size.top, size.left + size.width, size.top + size.height)
            parseBox(values[bd])?.let { size = it }
        }
        e.attrs["click"]?.let { hits.add(Hit(it, RectF(size.left, size.top, size.left + size.width, size.top + size.height))) }
        var text = ctx.sub(e.text)
        if (text.isBlank()) return
        // Whole-box character styles from <allcaps> and <italic> children (values "on" or "off").
        fun flag(key: String) = ctx.sub(e.options[key] ?: "").trim().equals("on", ignoreCase = true)
        if (flag("allcaps")) text = com.kyriedevles.turtleboxgx.text.uppercaseOutsideTags(text)
        val font = pick(e.fonts)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
        if (android.os.Build.VERSION.SDK_INT >= 33) paint.hinting = Paint.HINTING_OFF
        font?.let { f ->
            val tf = assets.font(f.name)
            if (tf != null) paint.typeface = tf
            if (f.name.contains("LINK", ignoreCase = true)) notes.add("LINK font ${f.name}: ${if (tf != null) "loaded" else "NOT loaded"}, text \"$text\"")
        }
        if (font?.style?.contains("bold", ignoreCase = true) == true) paint.isFakeBoldText = true
        if (font?.style?.contains("italic", ignoreCase = true) == true || flag("italic")) paint.textSkewX = -0.25f
        paint.color = try {
            Color.parseColor(ctx.sub(pick(e.colors) ?: "#000000").trim())
        } catch (ex: IllegalArgumentException) {
            Color.BLACK
        }
        val fontSize = font?.size ?: 12f
        paint.textSize = fontSize

        // Same rules as the old app: align tokens become Android gravity flags. Without a vertical token the
        // text sits at the top (TextView adds TOP), and "top"/"bottom" are not recognised, so they count as top.
        val flags = ctx.sub(pick(e.aligns) ?: "left").lowercase().split('|').map { it.trim() }
        // Photoshop's paragraph alignments (New engine; Classic treats them as the nearest plain alignment):
        // ragleft = Left (ragged), justcenter / justright = Justify Last Centred / Right, justall = Justify All.
        // Plain "left" keeps its old meaning, which is Justify Last Left.
        val h = when {
            "right" in flags || "justright" in flags -> 2
            "left" in flags || "ragleft" in flags || "justall" in flags -> 0
            "center" in flags || "hcenter" in flags || "center_horizontal" in flags || "justcenter" in flags -> 1
            else -> 0
        }
        val justifyMode = when {
            "justall" in flags -> 2
            "justcenter" in flags || "justright" in flags -> 1
            "ragleft" in flags -> 0
            else -> -1
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
        val nameFx: Map<String, String>? = e.options["namestyle"]?.let { NameStyle.resolve(ctx.sub(it), layout, ctx.value("namepresets_user").takeIf { u -> !u.startsWith("{{") } ?: "") }
        if (singleLine && nameFx != null) {
            nameFx["font"]?.takeIf { it.isNotBlank() }?.let { fn ->
                val file = layout.datas["namefonts"]?.items?.firstOrNull { it.first == fn }?.second ?: fn
                assets.font(file)?.let { paint.typeface = it }
            }
        }
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
            if (nameFx != null) {
                NameFx.draw(c, text, x, y, paint, RectF(size.left, size.top, size.left + size.width, size.top + size.height), drawW, nameFx, curScale, layout.width, layout, assets)
            } else {
                c.drawText(text, x, y, paint)
            }
            drawn++
            return
        }

        fun num(key: String): Float = ctx.sub(e.options[key] ?: "").trim().toFloatOrNull() ?: 0f
        val baseSpacing = e.attrs["line-space-scale"]?.toFloatOrNull() ?: 1f
        val spacing = baseSpacing * (num("linespace").takeIf { it > 0f } ?: 1f) * ((num("leading").takeIf { it > 0f } ?: 100f) / 100f)
        val opt = TextOptions(
            condense = num("condense"),
            fitMode = num("fitmode").let { if (it <= 0f) 1 else (it + 0.5f).toInt() },
            minSizePct = num("minsize"),
            maxSizePct = num("maxsize"),
            minLines = num("minline"),
            justify = num("justify"),
            lastStretch = num("justifylast"),
            flow = num("justifyflow"),
            // Size in px (as in Photopea) turns into the same percent of the layout's font size that Size % uses.
            fontScale = num("sizepx").takeIf { it > 0f }?.let { it * 100f / fontSize } ?: num("fontscale"),
            letterSpace = num("letterspace") + num("tracking") / 1000f,
            hScale = num("hscale").takeIf { it > 0f } ?: 100f,
            vScale = num("vscale").takeIf { it > 0f } ?: 100f,
            baselineShift = num("baseline"),
            wordSpace = num("wordspace").takeIf { it > 0f } ?: 100f,
            leadingPx = num("leadingpx"),
            spacing = spacing,
            indentLeft = num("indentleft"),
            indentRight = num("indentright"),
            indentFirst = num("indentfirst"),
            spaceBefore = num("spacebefore"),
            spaceAfter = num("spaceafter"),
            wordSpaceMin = num("wordspacemin"),
            wordSpaceMax = num("wordspacemax"),
            composer = if (ctx.sub(e.options["composer"] ?: "").trim().equals("everyline", ignoreCase = true) || num("composer") >= 1f) 1 else 0,
            photoshopFirstBaseline = ctx.sub(e.options["firstbaseline"] ?: "").trim().equals("photoshop", ignoreCase = true),
            justifyMode = justifyMode,
            hideOverflow = ctx.sub(e.options["overflow"] ?: "").trim().equals("hide", ignoreCase = true),
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
        if (com.kyriedevles.turtleboxgx.text.TextSwitch.useNew) {
            // <font=name> in the card text: a name from the layout's `namefonts` list, else a font file name.
            val fontLookup: (String) -> android.graphics.Typeface? = { fn ->
                assets.font(layout.datas["namefonts"]?.items?.firstOrNull { it.first == fn }?.second ?: fn)
            }
            // Fit steps: <fitsteps>on</fitsteps> with <fitstep1>..<fitstep6> ("size px / leading px / tracking") and
            // <fitsqueeze> (squeeze limits in % for 1 / 2 / 3+ paragraphs).
            val steps = if (ctx.sub(e.options["fitsteps"] ?: "").trim().equals("on", ignoreCase = true))
                (1..6).mapNotNull { com.kyriedevles.turtleboxgx.text.parseFitStep(ctx.sub(e.options["fitstep$it"] ?: "")) }
            else emptyList()
            val squeeze = ctx.sub(e.options["fitsqueeze"] ?: "").split('/', ',', ' ', ';').mapNotNull { it.trim().toFloatOrNull() }.filter { it > 0f }
            val engine = com.kyriedevles.turtleboxgx.text.NewTextEngine(body, paint, size.width, size.height, opt, mats, matPct, matWidthPct, matLinePct, fontLookup, steps, squeeze)
            val used = engine.fit()
            publishFit(e, opt, engine, steps.size)
            val top = size.top + (size.height - used).coerceAtLeast(0f) * v
            engine.draw(c, size.left, top, h)
            if (engine.hiddenLines > 0) {
                val what = drawingLayer?.name ?: e.label ?: e.attrs["click"] ?: "text"
                overset.add("$what: ${engine.hiddenLines} line${if (engine.hiddenLines == 1) "" else "s"} hidden")
            }
            probeName?.let { pn ->
                if (e.text.contains("{{$pn}}")) {
                    probeRects = engine.glyphRects(size.left, top)
                    probeText = engine.engineText
                    probeOverset = engine.hiddenLines > 0
                    probeBox = RectF(size.left, size.top, size.left + size.width, size.top + size.height)
                    probeLayer = drawingLayer
                }
            }
        } else {
            val engine = TextEngine(body, paint, width, size.height, opt, mats, matPct, matWidthPct, matLinePct)
            val used = engine.fit()
            engine.draw(c, size.left, size.top + (size.height - used).coerceAtLeast(0f) * v, h)
        }
        drawn++
    }

    /**
     * Tell the edit menu what fit steps chose, through the menu fields named in this box's option tags
     * (`<sizepx>{{sizepx_effect}}</sizepx>` and so on). Only boxes that have fit steps written in take part.
     */
    private fun publishFit(e: Element, opt: TextOptions, engine: com.kyriedevles.turtleboxgx.text.NewTextEngine, stepCount: Int) {
        val hasSteps = (1..6).any { com.kyriedevles.turtleboxgx.text.parseFitStep(ctx.sub(e.options["fitstep$it"] ?: "")) != null }
        if (!hasSteps) return
        val refRx = Regex("^\\s*\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}\\s*$")
        fun ref(key: String): String? = e.options[key]?.let { refRx.find(it)?.groupValues?.get(1) }
        val g = AutoFit.Group(ref("sizepx"), ref("leadingpx"), ref("tracking"), ref("hscale"))
        if (g.names().isEmpty()) return
        g.on = stepCount > 0
        g.auto = g.on && opt.fontScale <= 0f
        g.step = engine.usedStep
        g.steps = stepCount
        engine.usedValues()?.let { u ->
            g.squeeze = if (opt.hScale > 0f) u[3] / opt.hScale else 1f
            g.size?.let { g.values[it] = u[0] }
            g.leading?.let { g.values[it] = u[1] }
            g.tracking?.let { g.values[it] = u[2] }
            g.hscale?.let { g.values[it] = u[3] }
        }
        fitGroups.add(g)
    }

    /** First-line indent: non-breaking spaces at the start of every paragraph, like the old app (1 to 8). */
    private fun indentText(s: String, amount: Float): String {
        val n = (amount + 0.5f).toInt().coerceAtMost(8)
        if (amount <= 0f || n < 1) return s
        val pad = "\u00a0".repeat(n)
        return pad + s.replace("\n", "\n" + pad)
    }
}
