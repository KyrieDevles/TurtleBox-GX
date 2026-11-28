package com.kyriedevles.turtleboxgx.text

/**
 * Edits the inline tags of a text the way Photoshop's Character panel edits selected letters: set or clear one setting
 * (size, tracking, colour, bold...) on a range of the visible characters, and read what a range has. Pure Kotlin.
 *
 * The text is read into characters that each carry their open tags; an edit changes the characters' tags and the text is
 * written back with tidy tags (opened in a fixed order, neighbours merged, nothing left unclosed). Anything the engine
 * shows as plain text (unknown or invalid tags such as `<Monster Effect>`) stays plain text. `<br>` is one character.
 */
object TagEdit {
    /** Tags with a value, then flags. Their order decides nesting when the text is written back. */
    private val valued = listOf("font", "color", "size", "tracking", "kern", "leading", "scale", "vscale", "baseline", "fauxbold")
    private val flags = listOf("b", "i", "u", "s", "sc", "caps", "fauxitalic")
    private val order = valued + flags + "script"

    class Ch(val c: Char, val props: Map<String, String>)

    /** The visible characters (`<br>` as U+2028) with their tags. */
    fun parse(raw: String): List<Ch> {
        val out = ArrayList<Ch>(raw.length)
        val stacks = HashMap<String, ArrayList<String>>()
        val on = HashMap<String, String>()
        fun props(): Map<String, String> {
            val m = HashMap<String, String>(on)
            for ((k, s) in stacks) if (s.isNotEmpty()) m[k] = s.last()
            return m
        }
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '<') {
                val close = raw.indexOf('>', i + 1)
                if (close > i && close - i <= 200) {
                    val body = raw.substring(i + 1, close)
                    if (body.equals("br", true)) {
                        out.add(Ch(' ', props()))
                        i = close + 1
                        continue
                    }
                    // <para ...>: the paragraph's own settings, kept on every character of the paragraph.
                    val pa = paraAttrs(body)
                    if (pa != null && ParaSpec.parse(pa) != null) {
                        on[PARA] = tidyPara(pa)
                        i = close + 1
                        continue
                    }
                    if (apply(body, stacks, on)) {
                        i = close + 1
                        continue
                    }
                }
            }
            out.add(Ch(raw[i], props()))
            if (raw[i] == '\n') on.remove(PARA)
            i++
        }
        return out
    }

    private const val PARA = "para"

    /** "align=right left=10" in a fixed order, keys lower case; "" when nothing is set. */
    private fun tidyPara(attrs: String): String {
        val m = paraMap(attrs)
        return ParaSpec.KEYS.mapNotNull { k -> m[k]?.let { "$k=$it" } }.joinToString(" ")
    }

    private fun paraMap(attrs: String?): Map<String, String> {
        if (attrs.isNullOrBlank()) return emptyMap()
        val m = HashMap<String, String>()
        for (part in attrs.trim().split(Regex("\\s+"))) {
            val eq = part.indexOf('=')
            if (eq > 0) m[part.substring(0, eq).lowercase()] = part.substring(eq + 1).lowercase()
        }
        return m
    }

    /** The paragraphs (ranges of visible characters, each with its line break) that [from, to) touches. */
    private fun paragraphsTouched(chars: List<Ch>, from: Int, to: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var s = 0
        for (i in 0..chars.size) {
            if (i == chars.size || chars[i].c == '\n') {
                val e = if (i == chars.size) i - 1 else i
                val hit = from <= e && maxOf(to, from + 1) > s
                if (hit && e >= s) out.add(s..e)
                s = i + 1
            }
        }
        return out
    }

    /** Same rules as the engine's parser ([parseRich]): returns false for anything it would show as text. */
    private fun apply(body: String, stacks: HashMap<String, ArrayList<String>>, on: HashMap<String, String>): Boolean {
        val closing = body.startsWith("/")
        val b = if (closing) body.substring(1) else body
        val eq = b.indexOf('=')
        val name = (if (eq >= 0) b.substring(0, eq) else b).trim().lowercase()
        val value = if (eq >= 0) b.substring(eq + 1) else null
        if (closing && value != null) return false
        when (name) {
            in flags -> { if (value != null) return false; if (closing) on.remove(name) else on[name] = "" }
            "sup", "sub" -> { if (value != null) return false; if (closing) on.remove("script") else on["script"] = name }
            in valued -> {
                val s = stacks.getOrPut(name) { ArrayList() }
                if (closing) { if (s.isNotEmpty()) s.removeAt(s.size - 1); return true }
                val v = (value ?: return false).trim()
                if (!valid(name, v)) return false
                s.add(v)
            }
            else -> return false
        }
        return true
    }

    private fun valid(name: String, v: String): Boolean {
        fun num(s: String) = s.replace("⁠", "").trim().removeSuffix("px").trim().toFloatOrNull()
        return when (name) {
            "color" -> parseHexColor(v) != null
            "font" -> v.replace("⁠", "").isNotBlank()
            "size" -> (num(v.removeSuffix("%")) ?: 0f) > 0f
            "leading" -> (num(v) ?: 0f) > 0f
            "scale", "vscale" -> (num(v.removeSuffix("%")) ?: 0f) > 0f
            "fauxbold" -> (num(v) ?: -1f) >= 0f
            "tracking", "baseline", "kern" -> num(v) != null
            else -> false
        }
    }

    /** Writes characters back as text with tidy tags. */
    fun write(chars: List<Ch>): String {
        val sb = StringBuilder()
        val open = ArrayList<Pair<String, String>>()
        var paraStart = true
        fun openTag(k: String, v: String) = when {
            k == "script" -> "<$v>"
            k in flags -> "<$k>"
            else -> "<$k=$v>"
        }
        fun closeTag(k: String, v: String) = if (k == "script") "</$v>" else "</$k>"
        for (ch in chars) {
            val need = order.mapNotNull { k -> ch.props[k]?.let { k to it } }
            var same = 0
            while (same < open.size && same < need.size && open[same] == need[same]) same++
            while (open.size > same) { val (k, v) = open.removeAt(open.size - 1); sb.append(closeTag(k, v)) }
            if (paraStart) ch.props[PARA]?.takeIf { it.isNotEmpty() }?.let { sb.append("<para $it>") }
            paraStart = ch.c == '\n'
            for (p in need.drop(same)) { sb.append(openTag(p.first, p.second)); open.add(p) }
            sb.append(if (ch.c == ' ') "<br>" else ch.c.toString())
        }
        while (open.isNotEmpty()) { val (k, v) = open.removeAt(open.size - 1); sb.append(closeTag(k, v)) }
        return sb.toString()
    }

    /** The visible text (what the highlight counts in). */
    fun plain(raw: String): String = String(parse(raw).map { it.c }.toCharArray())

    /**
     * Sets [tag] to [value] on visible characters [from, to), or removes it when [value] is null. Flags (b, i, u, s, sc)
     * take "" to switch on; "script" takes "sup" or "sub". Returns the new text.
     */
    fun set(raw: String, from: Int, to: Int, tag: String, value: String?): String {
        if (tag.startsWith("$PARA.")) return setPara(raw, from, to, tag.removePrefix("$PARA."), value)
        val chars = parse(raw)
        val a = from.coerceIn(0, chars.size)
        val b = to.coerceIn(a, chars.size)
        val out = chars.mapIndexed { i, ch ->
            if (i < a || i >= b) ch else {
                val m = HashMap(ch.props)
                if (value == null) m.remove(tag) else m[tag] = value
                Ch(ch.c, m)
            }
        }
        return write(out)
    }

    /** What visible characters [from, to) have for [tag]. */
    sealed class Value {
        /** All of them have the same value; null = none of them has the tag (they use the box's setting). */
        class Same(val v: String?) : Value()
        object Mixed : Value()
    }

    fun valueOf(raw: String, from: Int, to: Int, tag: String): Value {
        if (tag.startsWith("$PARA.")) {
            val key = tag.removePrefix("$PARA.")
            val chars = parse(raw)
            val vals = paragraphsTouched(chars, from, to).map { r -> paraMap(chars[r.first].props[PARA])[key] }
            if (vals.isEmpty()) return Value.Same(null)
            return if (vals.all { it == vals.first() }) Value.Same(vals.first()) else Value.Mixed
        }
        val chars = parse(raw)
        val a = from.coerceIn(0, chars.size)
        val b = to.coerceIn(a, chars.size)
        if (a >= b) return Value.Same(null)
        // Spaces and breaks between words do not decide (Photoshop ignores them too when a selection starts on one).
        val seen = chars.subList(a, b).filter { !it.c.isWhitespace() && it.c != ' ' }.ifEmpty { chars.subList(a, b) }
        val first = seen.first().props[tag]
        return if (seen.all { it.props[tag] == first }) Value.Same(first) else Value.Mixed
    }

    /** For each visible character, where it is in the raw text. */
    private fun spans(raw: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '<') {
                val close = raw.indexOf('>', i + 1)
                if (close > i && close - i <= 200) {
                    val body = raw.substring(i + 1, close)
                    if (body.equals("br", true)) { out.add(i..close); i = close + 1; continue }
                    if (isKnownTag(body)) { i = close + 1; continue }
                }
            }
            out.add(i..i)
            i++
        }
        return out
    }

    /** Where visible characters [from, to) are in the raw text (for selecting them in a text field). */
    fun rawRange(raw: String, from: Int, to: Int): IntRange {
        val sp = spans(raw)
        if (sp.isEmpty()) return 0 until 0
        val a = from.coerceIn(0, sp.size)
        val b = to.coerceIn(a, sp.size)
        if (a == b) { val at = if (a < sp.size) sp[a].first else raw.length; return at until at }
        return sp[a].first until sp[b - 1].last + 1
    }

    /** The visible character index at raw offset [rawPos] (how many visible characters start before it). */
    fun visibleAt(raw: String, rawPos: Int): Int = spans(raw).count { it.first < rawPos }

    private fun isKnownTag(body: String): Boolean =
        paraAttrs(body)?.let { ParaSpec.parse(it) } != null || apply(body, HashMap(), HashMap())

    /**
     * Sets one Paragraph setting ([key]: align, left, right, first, before, after) on every paragraph that visible
     * characters [from, to) touch, or removes it when [value] is null, as Photoshop's Paragraph panel does.
     */
    fun setPara(raw: String, from: Int, to: Int, key: String, value: String?): String {
        val chars = parse(raw)
        val out = chars.toMutableList()
        for (r in paragraphsTouched(chars, from, to)) {
            val m = HashMap(paraMap(chars[r.first].props[PARA]))
            if (value == null) m.remove(key) else m[key] = value.trim().lowercase()
            val v = ParaSpec.KEYS.mapNotNull { k -> m[k]?.let { "$k=$it" } }.joinToString(" ")
            for (i in r) {
                val p = HashMap(chars[i].props)
                if (v.isEmpty()) p.remove(PARA) else p[PARA] = v
                out[i] = Ch(chars[i].c, p)
            }
        }
        return write(out)
    }

    /**
     * Matches the engine's text (after `<br>`, `[[ ]]`, word joiners and indents) to the visible characters. Returns, for
     * every engine index, the visible index it shows, or -1 for a character the engine added.
     */
    fun align(visible: String, engine: String): IntArray {
        val map = IntArray(engine.length) { -1 }
        var i = 0
        var j = 0
        while (j < engine.length) {
            val e = engine[j]
            if (i < visible.length && (visible[i] == e || visible[i].uppercaseChar() == e.uppercaseChar())) {
                map[j] = i
                i++
                j++
            } else if (e == '⁠' || e == ' ') {
                j++
            } else if (i < visible.length && (visible[i] == '[' || visible[i] == ']' || visible[i] == ' ')) {
                i++
            } else {
                j++
            }
        }
        return map
    }
}
