package com.kyriedevles.turtleboxgx.text

/**
 * Inline tags of the New text engine. Pure Kotlin (no Android classes) so it runs in plain JVM tests.
 *
 * Every character carries one style id. The low two bits are the old bold (bit 0) and italic (bit 1) flags, so the
 * layout core and its test metrics keep reading them as before. The bits above are an index into [RichText.extras],
 * which holds everything else a run can have: underline, strikethrough, super/subscript, small caps, colour, font.
 * Index 0 is always "nothing extra".
 */
data class RunExtra(
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** 0 normal, 1 superscript, 2 subscript. */
    val script: Int = 0,
    val smallCaps: Boolean = false,
    /** ARGB, or null for the box's own colour. */
    val color: Int? = null,
    /** A font name as the layout knows it (a name from `namefonts`, or a font file), or null for the box's own font. */
    val font: String? = null,
    /** `<size=10.65>`: font size in card px (0 = the box's). Stays that size when the box shrinks to fit, as in Photoshop. */
    val sizePx: Float = 0f,
    /** `<size=85%>`: font size as a % of the box's (0 = not set). Shrinks with the box. */
    val sizePct: Float = 0f,
    /** `<tracking=-20>`: tracking in 1/1000 em, replacing the box's for these letters (null = the box's). */
    val tracking: Float? = null,
    /** `<leading=11.14>`: leading in card px (0 = the box's). A line uses the largest leading on it, as in Photoshop. */
    val leading: Float = 0f,
    /** `<scale=90>`: horizontal scale % on top of the box's (0 = not set). */
    val scale: Float = 0f,
    /** `<baseline=1.5>`: baseline shift in card px, + raises (0 = none). */
    val baseline: Float = 0f,
    /** `<vscale=110>`: vertical scale % of these letters (0 = the box's), about the baseline. */
    val vscale: Float = 0f,
    /** `<fauxbold=30>`: faux bold in 1/1000 em of stroke, like the box's Faux Bold (0 = the box's). */
    val weight: Float = 0f,
    /** `<fauxitalic>`: slanted letters (Photoshop's Faux Italic). */
    val fauxItalic: Boolean = false,
    /** `<kern=-20>`: kerning in 1/1000 em after each of these letters, added to the tracking (0 = none). */
    val kern: Float = 0f,
) {
    /** The font size of this run when the box's size is [boxSize]. */
    fun sizeAt(boxSize: Float): Float = when {
        sizePx > 0f -> sizePx
        sizePct > 0f -> boxSize * sizePct / 100f
        else -> boxSize
    }

    /** True when the run changes the line's height (its size or leading). */
    val changesLines: Boolean get() = sizePx > 0f || sizePct > 0f || leading > 0f
}

class RichText(val text: String, val styles: IntArray, val extras: List<RunExtra>)

fun extraIndexOf(style: Int): Int = style ushr 2

/** The extras of a style id, or null when it has none. */
fun extraOf(style: Int, extras: List<RunExtra>): RunExtra? =
    extraIndexOf(style).let { if (it <= 0) null else extras.getOrNull(it) }

/** `#rgb`, `#rrggbb` or `#aarrggbb`. Returns ARGB, or null when the text is not one of those. */
fun parseHexColor(s: String): Int? {
    val t = s.trim()
    if (t.length < 2 || t[0] != '#') return null
    val h = t.substring(1)
    if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return when (h.length) {
        3 -> {
            val r = h.substring(0, 1).repeat(2).toInt(16)
            val g = h.substring(1, 2).repeat(2).toInt(16)
            val b = h.substring(2, 3).repeat(2).toInt(16)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        6 -> (0xFF shl 24) or h.toInt(16)
        8 -> h.toLong(16).toInt()
        else -> null
    }
}

/** The open tags while reading the text: flags, and a stack per valued tag (closing removes the innermost one). */
private class TagState {
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    var script = 0
    var smallCaps = false
    var caps = false
    var fauxItalic = false
    val vscales = ArrayList<Float>()
    val weights = ArrayList<Float>()
    val kerns = ArrayList<Float>()
    val colors = ArrayList<Int>()
    val fonts = ArrayList<String>()
    val sizes = ArrayList<Pair<Float, Boolean>>() // value, is %
    val trackings = ArrayList<Float>()
    val leadings = ArrayList<Float>()
    val scales = ArrayList<Float>()
    val baselines = ArrayList<Float>()

    fun extra(): RunExtra {
        val sz = sizes.lastOrNull()
        return RunExtra(
            underline, strike, script, smallCaps, colors.lastOrNull(), fonts.lastOrNull(),
            sizePx = if (sz != null && !sz.second) sz.first else 0f,
            sizePct = if (sz != null && sz.second) sz.first else 0f,
            tracking = trackings.lastOrNull(),
            leading = leadings.lastOrNull() ?: 0f,
            scale = scales.lastOrNull() ?: 0f,
            baseline = baselines.lastOrNull() ?: 0f,
            vscale = vscales.lastOrNull() ?: 0f,
            weight = weights.lastOrNull() ?: 0f,
            fauxItalic = fauxItalic,
            kern = kerns.lastOrNull() ?: 0f,
        )
    }
}

/**
 * Reads `<b> <i> <u> <s> <sup> <sub> <sc> <caps> <fauxitalic>`, `<color=#rrggbb> <font=name>` and the Character tags
 * `<size=10.65>` (card px) or `<size=85%>`, `<tracking=-20>`, `<leading=11.14>`, `<scale=90>`, `<vscale=110>`,
 * `<baseline=1.5>`, `<fauxbold=30>`, `<kern=-20>`, with their
 * closing tags (a closing tag ends the innermost open one of its kind). A tag that is never closed stays on to the end of
 * the text, across lines and paragraphs. Tag names are not case sensitive. Anything else that looks like a tag, a value
 * that is not valid, or a tag without its `>` is left as ordinary text so a typo stays visible on the card.
 * `<br>` is not handled here: the engine turns it into a line break before calling this.
 */
fun parseRich(raw: String): RichText {
    val plain = listOf(RunExtra())
    if (raw.indexOf('<') < 0) return RichText(raw, IntArray(raw.length), plain)

    val extras = ArrayList<RunExtra>()
    extras.add(RunExtra())
    val index = HashMap<RunExtra, Int>()
    index[RunExtra()] = 0

    val out = StringBuilder(raw.length)
    val st = IntArray(raw.length)
    var n = 0
    val tags = TagState()
    var cur = 0
    var dirty = true

    fun styleId(): Int {
        if (dirty) {
            val e = tags.extra()
            val k = index.getOrPut(e) { extras.add(e); extras.size - 1 }
            cur = (k shl 2) or (if (tags.bold) 1 else 0) or (if (tags.italic) 2 else 0)
            dirty = false
        }
        return cur
    }

    var i = 0
    while (i < raw.length) {
        if (raw[i] == '<') {
            val close = raw.indexOf('>', i + 1)
            if (close > i && close - i <= 200) {
                val body = raw.substring(i + 1, close)
                if (applyTag(body, tags)) {
                    dirty = true
                    i = close + 1
                    continue
                }
            }
        }
        // <caps>: All Caps for these letters (one letter for one, so the highlight still lines up).
        out.append(if (tags.caps) raw[i].uppercaseChar() else raw[i])
        st[n] = styleId()
        n++
        i++
    }
    return RichText(out.toString(), st.copyOf(n), extras)
}

private fun num(v: String?): Float? = v?.replace("\u2060", "")?.trim()?.removeSuffix("px")?.trim()?.toFloatOrNull()

private fun <T> pushPop(list: MutableList<T>, closing: Boolean, value: T?): Boolean {
    if (closing) { if (list.isNotEmpty()) list.removeAt(list.size - 1); return true }
    list.add(value ?: return false)
    return true
}

/** Applies one tag (the text between `<` and `>`). Returns false when it is not a tag this parser knows. */
private fun applyTag(body: String, t: TagState): Boolean {
    val closing = body.startsWith("/")
    val b = if (closing) body.substring(1) else body
    val eq = b.indexOf('=')
    val name = (if (eq >= 0) b.substring(0, eq) else b).trim().lowercase()
    val value = if (eq >= 0) b.substring(eq + 1) else null
    if (closing && value != null) return false
    when (name) {
        "b" -> { if (value != null) return false; t.bold = !closing }
        "i" -> { if (value != null) return false; t.italic = !closing }
        "u" -> { if (value != null) return false; t.underline = !closing }
        "s" -> { if (value != null) return false; t.strike = !closing }
        "sup" -> { if (value != null) return false; t.script = if (closing) 0 else 1 }
        "sub" -> { if (value != null) return false; t.script = if (closing) 0 else 2 }
        "sc" -> { if (value != null) return false; t.smallCaps = !closing }
        "caps" -> { if (value != null) return false; t.caps = !closing }
        "fauxitalic" -> { if (value != null) return false; t.fauxItalic = !closing }
        "vscale" -> return pushPop(t.vscales, closing, if (closing) null else num(value?.removeSuffix("%"))?.takeIf { it > 0f } ?: return false)
        "fauxbold" -> return pushPop(t.weights, closing, if (closing) null else num(value)?.takeIf { it >= 0f } ?: return false)
        "kern" -> return pushPop(t.kerns, closing, if (closing) null else num(value) ?: return false)
        "color" -> return pushPop(t.colors, closing, if (closing) null else parseHexColor(value ?: return false) ?: return false)
        "font" -> {
            if (closing) return pushPop(t.fonts, true, null)
            // keepWord boxes glue "/" with a word joiner; a font file path must not carry it.
            val f = (value ?: return false).replace("\u2060", "").trim()
            if (f.isEmpty()) return false
            t.fonts.add(f)
        }
        "size" -> {
            if (closing) return pushPop(t.sizes, true, null)
            val v = (value ?: return false).replace("\u2060", "").trim()
            val pct = v.endsWith("%")
            val x = num(v.removeSuffix("%")) ?: return false
            if (x <= 0f) return false
            t.sizes.add(x to pct)
        }
        "tracking" -> return pushPop(t.trackings, closing, if (closing) null else num(value) ?: return false)
        "leading" -> return pushPop(t.leadings, closing, if (closing) null else num(value)?.takeIf { it > 0f } ?: return false)
        "scale" -> return pushPop(t.scales, closing, if (closing) null else num(value?.removeSuffix("%"))?.takeIf { it > 0f } ?: return false)
        "baseline" -> return pushPop(t.baselines, closing, if (closing) null else num(value) ?: return false)
        else -> return false
    }
    return true
}

private val knownTag = Regex("</?(b|i|u|s|sup|sub|sc|caps|fauxitalic|br|color|font|size|tracking|leading|scale|baseline|vscale|fauxbold|kern)(=[^>]*)?>", RegexOption.IGNORE_CASE)

/** Upper-cases the text but not the inline tags in it, so `<b>` and `<font=name>` keep working under All caps. */
fun uppercaseOutsideTags(s: String): String {
    if (s.indexOf('<') < 0) return s.uppercase()
    val sb = StringBuilder(s.length)
    var pos = 0
    for (m in knownTag.findAll(s)) {
        sb.append(s.substring(pos, m.range.first).uppercase())
        sb.append(m.value)
        pos = m.range.last + 1
    }
    sb.append(s.substring(pos).uppercase())
    return sb.toString()
}
