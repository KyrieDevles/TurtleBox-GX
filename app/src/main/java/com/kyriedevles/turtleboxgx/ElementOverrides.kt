package com.kyriedevles.turtleboxgx

/**
 * The card's own changes to the layout's built-in parts (the dashed rows in Layers): a new index (to move a part in
 * front of or behind others and the layers) and an opacity. Kept in the card's values under `elemovr`, one line per
 * part: `name|index|opacity` (empty = the layout's). A part is named as the Layers list shows it: its XML comment,
 * else its id, else its kind; all elements with that name change together.
 */
object ElementOverrides {
    const val KEY = "elemovr"

    class Ovr(var index: Float? = null, var opacity: Float? = null)

    fun nameOf(e: Element): String = e.label ?: e.attrs["id"]?.takeIf { it.isNotBlank() } ?: e.kind

    fun read(values: Map<String, String>): LinkedHashMap<String, Ovr> {
        val out = LinkedHashMap<String, Ovr>()
        for (line in (values[KEY] ?: "").split('\n')) {
            val p = line.split('|')
            if (p.size < 3 || p[0].isBlank()) continue
            out[p[0]] = Ovr(p[1].toFloatOrNull(), p[2].toFloatOrNull())
        }
        return out
    }

    fun write(values: MutableMap<String, String>, m: Map<String, Ovr>) {
        val s = m.filter { it.value.index != null || it.value.opacity != null }.entries.joinToString("\n") { (n, o) ->
            n.replace("|", "/").replace("\n", " ") + "|" + (o.index?.toString() ?: "") + "|" + (o.opacity?.toString() ?: "")
        }
        if (s.isEmpty()) values.remove(KEY) else values[KEY] = s
    }
}
