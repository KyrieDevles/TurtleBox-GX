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
)

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

/**
 * Reads `<b> <i> <u> <s> <sup> <sub> <sc>` and `<color=#rrggbb> <font=name>` with their closing tags
 * (`</color>` and `</font>` close the innermost one). A tag that is never closed stays on to the end of the text,
 * across lines and paragraphs. Tag names are not case sensitive. Anything else that looks like a tag, a colour that is not
 * valid hex, or a tag without its `>` is left as ordinary text so a typo stays visible on the card.
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
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    var script = 0
    var smallCaps = false
    val colors = ArrayList<Int>()
    val fonts = ArrayList<String>()
    var cur = 0
    var dirty = true

    fun styleId(): Int {
        if (dirty) {
            val e = RunExtra(underline, strike, script, smallCaps, colors.lastOrNull(), fonts.lastOrNull())
            val k = index.getOrPut(e) { extras.add(e); extras.size - 1 }
            cur = (k shl 2) or (if (bold) 1 else 0) or (if (italic) 2 else 0)
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
                if (applyTag(body, { bold = it }, { italic = it }, { underline = it }, { strike = it }, { script = it },
                        { smallCaps = it }, colors, fonts)) {
                    dirty = true
                    i = close + 1
                    continue
                }
            }
        }
        out.append(raw[i])
        st[n] = styleId()
        n++
        i++
    }
    return RichText(out.toString(), st.copyOf(n), extras)
}

/** Applies one tag (the text between `<` and `>`). Returns false when it is not a tag this parser knows. */
private fun applyTag(
    body: String,
    setBold: (Boolean) -> Unit,
    setItalic: (Boolean) -> Unit,
    setUnderline: (Boolean) -> Unit,
    setStrike: (Boolean) -> Unit,
    setScript: (Int) -> Unit,
    setSmallCaps: (Boolean) -> Unit,
    colors: MutableList<Int>,
    fonts: MutableList<String>,
): Boolean {
    val closing = body.startsWith("/")
    val t = if (closing) body.substring(1) else body
    val eq = t.indexOf('=')
    val name = (if (eq >= 0) t.substring(0, eq) else t).trim().lowercase()
    val value = if (eq >= 0) t.substring(eq + 1) else null
    if (closing && value != null) return false
    when (name) {
        "b" -> { if (value != null) return false; setBold(!closing) }
        "i" -> { if (value != null) return false; setItalic(!closing) }
        "u" -> { if (value != null) return false; setUnderline(!closing) }
        "s" -> { if (value != null) return false; setStrike(!closing) }
        "sup" -> { if (value != null) return false; setScript(if (closing) 0 else 1) }
        "sub" -> { if (value != null) return false; setScript(if (closing) 0 else 2) }
        "sc" -> { if (value != null) return false; setSmallCaps(!closing) }
        "color" -> {
            if (closing) { if (colors.isNotEmpty()) colors.removeAt(colors.size - 1) }
            else colors.add(parseHexColor(value ?: return false) ?: return false)
        }
        "font" -> {
            if (closing) { if (fonts.isNotEmpty()) fonts.removeAt(fonts.size - 1) }
            else {
                // keepWord boxes glue "/" with a word joiner; a font file path must not carry it.
                val f = (value ?: return false).replace("\u2060", "").trim()
                if (f.isEmpty()) return false
                fonts.add(f)
            }
        }
        else -> return false
    }
    return true
}

private val knownTag = Regex("</?(b|i|u|s|sup|sub|sc|br|color|font)(=[^>]*)?>", RegexOption.IGNORE_CASE)

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
