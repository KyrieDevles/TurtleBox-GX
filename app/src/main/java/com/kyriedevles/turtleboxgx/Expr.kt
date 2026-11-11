package com.kyriedevles.turtleboxgx

/**
 * Variable lookup for a layout: card values from a set, falling back to the layout's `<data default>`,
 * with `<bool name value>` entries evaluated on demand. `{{name}}` is replaced by [sub].
 */
class Ctx(private val layout: Layout, private val values: Map<String, String>) {
    private val cache = HashMap<String, String>()
    private var depth = 0

    // Layouts mix upper and lower case in names ({{ATK}} for the data called "atk"), so names match ignoring case.
    private val keys = HashMap<String, String>().also { m ->
        layout.datas.keys.forEach { m[it.lowercase()] = it }
        layout.bools.keys.forEach { m[it.lowercase()] = it }
        values.keys.forEach { m.putIfAbsent(it.lowercase(), it) }
    }

    fun value(rawName: String): String {
        val name = keys[rawName.lowercase()] ?: rawName
        cache[name]?.let { return it }
        if (depth > 12) return ""
        depth++
        try {
            val boolExpr = layout.bools[name]
            val result = if (boolExpr != null) {
                if (Expr.eval(boolExpr, this)) "true" else "false"
            } else {
                val raw = values[name] ?: layout.datas[name]?.default ?: ""
                sub(raw)
            }
            cache[name] = result
            return result
        } finally {
            depth--
        }
    }

    fun sub(s: String): String {
        if (!s.contains("{{")) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val a = s.indexOf("{{", i)
            if (a < 0) {
                sb.append(s, i, s.length)
                break
            }
            val b = s.indexOf("}}", a + 2)
            if (b < 0) {
                sb.append(s, i, s.length)
                break
            }
            sb.append(s, i, a)
            sb.append(value(s.substring(a + 2, b).trim()))
            i = b + 2
        }
        return sb.toString()
    }
}

/**
 * Tiny evaluator for the layout's conditions, e.g.
 * `{{is_rush}} == false and '{{cardtype}}' != 'normal'`.
 * Supports ==, !=, and, or, not, parentheses, quoted strings, {{variables}} and bare words.
 */
object Expr {
    private class Tok(val isOp: Boolean, val text: String)

    fun eval(expr: String, ctx: Ctx): Boolean {
        return try {
            Parser(tokenize(expr, ctx)).parseOr()
        } catch (e: Exception) {
            false
        }
    }

    private fun tokenize(s: String, ctx: Ctx): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '\'' || c == '"' -> {
                    val end = s.indexOf(c, i + 1).let { if (it < 0) s.length else it }
                    out.add(Tok(false, ctx.sub(s.substring(i + 1, end))))
                    i = end + 1
                }
                s.startsWith("{{", i) -> {
                    val end = s.indexOf("}}", i + 2).let { if (it < 0) s.length else it }
                    out.add(Tok(false, ctx.value(s.substring(i + 2, end).trim())))
                    i = end + 2
                }
                s.startsWith("==", i) -> {
                    out.add(Tok(true, "=="))
                    i += 2
                }
                s.startsWith("!=", i) -> {
                    out.add(Tok(true, "!="))
                    i += 2
                }
                c == '(' || c == ')' -> {
                    out.add(Tok(true, c.toString()))
                    i++
                }
                else -> {
                    var j = i
                    while (j < s.length && !s[j].isWhitespace() && s[j] !in "()=!'\"") j++
                    if (j == i) j = i + 1
                    val word = s.substring(i, j)
                    when (word.lowercase()) {
                        "and", "or", "not" -> out.add(Tok(true, word.lowercase()))
                        else -> out.add(Tok(false, word))
                    }
                    i = j
                }
            }
        }
        return out
    }

    private class Parser(private val t: List<Tok>) {
        private var p = 0

        private fun isOp(op: String) = p < t.size && t[p].isOp && t[p].text == op

        fun parseOr(): Boolean {
            var v = parseAnd()
            while (isOp("or")) {
                p++
                val r = parseAnd()
                v = v || r
            }
            return v
        }

        private fun parseAnd(): Boolean {
            var v = parseNot()
            while (isOp("and")) {
                p++
                val r = parseNot()
                v = v && r
            }
            return v
        }

        private fun parseNot(): Boolean {
            if (isOp("not")) {
                p++
                return !parseNot()
            }
            return parseCmp()
        }

        private fun parseCmp(): Boolean {
            if (isOp("(")) {
                p++
                val v = parseOr()
                if (isOp(")")) p++
                return v
            }
            val left = operand()
            if (isOp("==")) {
                p++
                return left.equals(operand(), ignoreCase = true)
            }
            if (isOp("!=")) {
                p++
                return !left.equals(operand(), ignoreCase = true)
            }
            return left.equals("true", ignoreCase = true)
        }

        private fun operand(): String {
            if (p >= t.size || t[p].isOp) return ""
            return t[p++].text
        }
    }
}
