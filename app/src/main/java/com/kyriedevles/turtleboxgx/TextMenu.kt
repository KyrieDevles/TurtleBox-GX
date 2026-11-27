package com.kyriedevles.turtleboxgx

/**
 * The `<textmenu>` shortcut: one tag on a `<text>` element builds its whole edit menu, the same shared panel the
 * Effect text has (Fit / Characters / Paragraph), instead of about forty hand-written `<data>` lines.
 *
 * ```xml
 * <text click="flavormenu" ...>
 *     <textmenu>flavor</textmenu>
 *     <text>{{flavortext}}</text>
 * </text>
 * ```
 *
 * For the id `flavor` it adds, when they are not already there:
 * - the settings `sizepx_flavor`, `tracking_flavor`, `halign_flavor`, ... with Photoshop's defaults;
 * - the option tags on the element (`<sizepx>{{sizepx_flavor}}</sizepx>`, ...) and `<align>{{halign_flavor}}</align>`;
 * - the sections `fitmenu_flavor`, `advancedmenu_flavor` (Characters) and `paragraphmenu_flavor`;
 * - a movable box (`box_flavor`, unless the element has `boxdata`);
 * - the menu the element opens (its `click` name, or `textmenu_flavor` when it has none): text, box, the three sections.
 *
 * Anything written by hand wins: an existing `<data>` keeps its own default and items, and an option tag already on
 * the element is kept (if it is a plain `{{name}}` that setting is shown in the menu, otherwise the item is left out).
 * Fit steps rows (Steps / Smooth fit, Status, Squeeze limit) appear only when the element has `<fitstep1>`.
 */
object TextMenu {
    private class Spec(val key: String, val default: String, val desc: String, val items: List<Pair<String, String>> = emptyList())

    private val offOn = listOf("Off" to "off", "On" to "on")

    private val characters = listOf(
        Spec("allcaps", "off", "All caps", offOn),
        Spec("italic", "off", "Italic", offOn),
        Spec("sizepx", "0", "Size px"),
        Spec("tracking", "0", "Tracking"),
        Spec("leadingpx", "0", "Leading px"),
        Spec("hscale", "100", "Horizontal Scale %"),
        Spec("vscale", "100", "Vertical Scale %"),
        Spec("baseline", "0", "Baseline Shift (px, + raises)"),
        Spec("weight", "0", "Faux Bold (0 = off)"),
    )

    /** Paragraph rows; a key starting with # is a heading. "halign" is the element's `<align>`. */
    private val paragraph = listOf(
        Spec(
            "halign", "left", "Alignment",
            listOf(
                "Left (ragged)" to "ragleft", "Centre" to "hcenter", "Right" to "right", "Justify Last Left" to "left",
                "Justify Last Centre" to "justcenter", "Justify Last Right" to "justright", "Justify All" to "justall",
            ),
        ),
        Spec("composer", "0", "Line Composer", listOf("Single-line" to "0", "Every-line" to "1")),
        Spec("#Word spacing", "", ""),
        Spec("wordspace", "100", "Desired %"),
        Spec("wordspacemin", "80", "Minimum %"),
        Spec("wordspacemax", "133", "Maximum %"),
        Spec("#Leading", "", ""),
        Spec("leading", "100", "Auto leading %"),
        Spec("#Indents and spacing (px)", "", ""),
        Spec("indentleft", "0", "Left indent"),
        Spec("indentright", "0", "Right indent"),
        Spec("indentfirst", "0", "First-line indent (negative = hanging)"),
        Spec("spacebefore", "0", "Space before"),
        Spec("spaceafter", "0", "Space after"),
        Spec("valign", "0", "Vertical Align", listOf("top" to "0", "center" to "0.5", "bottom" to "1")),
        Spec("#Extras (not in Photoshop)", "", ""),
        Spec("justifylast", "0", "Last line stretch %"),
        Spec("justifyflow", "0", "Reflow %"),
    )

    private val stepsRows = listOf(
        Spec("fitsteps", "on", "Fit steps", listOf("Steps" to "on", "Smooth fit" to "off")),
        Spec("fitstatus", "", "Status"),
        Spec("fitsqueeze", "64.5 / 66.5 / 68.5", "Squeeze limit % (1 / 2 / 3+ paragraphs)"),
        Spec("#When Fit steps is off", "", ""),
    )

    private val fitRows = listOf(
        Spec("fitmode", "1", "Fit Priority", listOf("squeeze first (uses Condense %)" to "1", "shrink first" to "2", "balanced" to "3")),
        Spec("condense", "100", "Min Horizontal Scale %"),
        Spec("maxsize", "100", "Max Font %"),
        Spec("minsize", "0", "Min Font %"),
    )

    private val refRx = Regex("^\\s*\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}\\s*$")

    private fun ref(v: String?): String? = v?.let { refRx.find(it)?.groupValues?.get(1) }

    /** Adds what every `<textmenu>` element needs to [datas] and to the element itself. */
    fun expand(datas: MutableMap<String, DataDef>, elements: List<Element>) {
        for (e in elements) {
            if (e.kind != "text") continue
            val id = e.options["textmenu"]?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) } ?: continue
            val hasSteps = e.options.containsKey("fitstep1")

            /** The setting a row edits: the element's own `{{name}}`, a new `<key>_<id>`, or null (literal value). */
            fun setting(s: Spec): String? {
                if (s.key == "halign") {
                    if (e.aligns.isNotEmpty()) return e.aligns.singleOrNull()?.takeIf { it.whenExpr == null }?.let { ref(it.value) }
                    e.aligns.add(Cond(null, "{{halign_$id}}"))
                } else if (s.key == "fitstatus") {
                    val name = "fitstatus_$id"
                    val size = ref(e.options["sizepx"]) ?: "sizepx_$id"
                    if (name !in datas) datas[name] = DataDef(name, size, "fitstatus", s.desc)
                    return name
                } else {
                    val have = e.options[s.key]
                    if (have != null) return ref(have)
                    e.options[s.key] = "{{${s.key}_$id}}"
                }
                val name = "${s.key}_$id"
                if (name !in datas) {
                    val d = DataDef(name, s.default, if (s.items.isEmpty()) "text" else null, s.desc)
                    d.items.addAll(s.items)
                    datas[name] = d
                }
                return name
            }

            fun group(name: String, desc: String, rows: List<Spec>) {
                val g = DataDef(name, null, "multi_select", desc)
                var heading: String? = null
                for (s in rows) {
                    if (s.key.startsWith("#")) { heading = s.key.substring(1); continue }
                    val n = setting(s) ?: continue
                    heading?.let { g.items.add(it to "#"); heading = null }
                    g.items.add(n to n)
                }
                if (name !in datas) datas[name] = g
            }

            // Settings and option tags first (Characters before Fit, so Status finds the size setting).
            group("advancedmenu_$id", "Characters", characters)
            group("fitmenu_$id", if (hasSteps) "Fit steps" else "Fit", if (hasSteps) stepsRows + fitRows else fitRows)
            group("paragraphmenu_$id", "Paragraph", paragraph)

            val box = e.attrs["boxdata"] ?: "box_$id".also { b ->
                e.attrs["boxdata"] = b
                if (b !in datas) datas[b] = DataDef(b, "", "box", "Move / resize text box…")
            }
            val top = e.attrs["click"]?.takeIf { it.isNotBlank() } ?: "textmenu_$id".also { e.attrs["click"] = it }
            if (top !in datas) {
                val m = DataDef(top, null, "multi_select", e.label ?: id)
                ref(e.text)?.let { t ->
                    if (t !in datas) datas[t] = DataDef(t, "", "text", "Text")
                    m.items.add(t to t)
                }
                m.items.add(box to box)
                for (s in listOf("fitmenu_$id", "advancedmenu_$id", "paragraphmenu_$id")) m.items.add(s to s)
                datas[top] = m
            }
        }
    }
}
