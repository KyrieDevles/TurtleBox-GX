package com.kyriedevles.turtleboxgx

/**
 * Text layers: a [Layer] with kind "text". Its geometry (centre, size, rotation, order, opacity) is the layer's, like an
 * image layer. Its text and settings live in the card's values under names ending in `_tl<layer id>`:
 * `text_`, `mode_` (point / box), `font_`, `color_`, `style_` (Name Style), plus everything the `<textmenu>` panel makes
 * (`sizepx_`, `tracking_`, ...). Nothing is needed in the layout XML, so text layers work with any layout.
 *
 * - Point text: one line, drawn at Size px around the layer centre; scaling it on the card changes Size px.
 * - Box text: wraps inside the layer's box with the New text engine, like the Effect text (Fit, Characters, Paragraph).
 */
object TextLayers {
    fun tm(id: String) = "tl$id"

    const val DEFAULT_SIZE = 24f

    private val ownKeys = listOf("text", "mode", "font", "color", "style")

    /** Values for a new text layer. */
    fun newValues(values: MutableMap<String, String>, layout: Layout, id: String, text: String) {
        val t = tm(id)
        values["text_$t"] = text
        values["mode_$t"] = "point"
        values["font_$t"] = defaultFont(layout)
        values["color_$t"] = "#000000"
        values["style_$t"] = "auto"
        values["sizepx_$t"] = "24"
    }

    fun defaultFont(layout: Layout): String {
        val items = layout.datas["namefonts"]?.items ?: emptyList()
        return items.firstOrNull { it.second.contains("MatrixBook", ignoreCase = true) }?.second ?: items.firstOrNull()?.second ?: ""
    }

    fun isPoint(values: Map<String, String>, id: String) = (values["mode_${tm(id)}"] ?: "point") != "box"

    fun fontFile(layout: Layout, values: Map<String, String>, id: String): String {
        val f = values["font_${tm(id)}"]?.takeIf { it.isNotBlank() } ?: defaultFont(layout)
        // A namefonts label also works (as in <font=..> tags).
        return layout.datas["namefonts"]?.items?.firstOrNull { it.first == f }?.second ?: f
    }

    /** The `<text>` element a box text layer draws as, centred on (0, 0) and unrotated (the renderer moves the canvas). */
    fun boxElement(l: Layer, layout: Layout, values: Map<String, String>, datas: MutableMap<String, DataDef> = HashMap()): Element {
        val t = tm(l.id)
        val e = Element("text", hashMapOf("click" to "layermenu_$t", "boxdata" to "-", "keepWord" to "true"), 0)
        e.sizes.add(Cond(null, Rect4(-l.w / 2f, -l.h / 2f, l.w, l.h)))
        e.fonts.add(Cond(null, FontSpec(fontFile(layout, values, l.id), "", DEFAULT_SIZE)))
        e.colors.add(Cond(null, "{{color_$t}}"))
        e.text = "{{text_$t}}"
        e.options["textmenu"] = t
        e.options["firstbaseline"] = "photoshop"
        TextMenu.expand(datas, listOf(e))
        // The layer handles taps and moving itself.
        e.attrs.remove("click")
        e.attrs.remove("boxdata")
        return e
    }

    /**
     * Adds the settings and menus of every text layer to the layout (they are what the edit menu and the defaults read).
     * Call on the main thread before drawing or opening a menu; it only adds what is missing and refreshes each menu.
     */
    fun sync(layout: Layout, values: Map<String, String>) {
        @Suppress("UNCHECKED_CAST")
        val datas = layout.datas as? MutableMap<String, DataDef> ?: return
        for (l in ImageLayers.read(values).filter { it.isText }) {
            val t = tm(l.id)
            boxElement(l, layout, values, datas)
            fun def(name: String, default: String, type: String?, desc: String, items: List<Pair<String, String>> = emptyList()) {
                if (name in datas) return
                val d = DataDef(name, default, type, desc)
                d.items.addAll(items)
                datas[name] = d
            }
            def("text_$t", "Text", "text", "Text")
            def("mode_$t", "point", null, "Type", listOf("Point text" to "point", "Box text" to "box"))
            val fonts = layout.datas["namefonts"]?.items ?: emptyList()
            if (fonts.isNotEmpty()) def("font_$t", defaultFont(layout), null, "Font", fonts) else def("font_$t", "", "text", "Font file")
            def("color_$t", "#000000", "text", "Colour (hex, e.g. #ffffff)")
            def("style_$t", "auto", "namestyle", "Style (Name Style)")
            def("box_$t", "", "box", "Move / resize text box…")
            // Point text: one line, so only the character settings that apply to it.
            val pc = DataDef("pointchars_$t", null, "multi_select", "Characters")
            for (k in listOf("allcaps", "italic", "sizepx", "tracking", "hscale", "vscale", "baseline", "weight")) pc.items.add("${k}_$t" to "${k}_$t")
            datas[pc.name] = pc
            val m = DataDef("layermenu_$t", null, "multi_select", l.name)
            for (k in ownKeys) m.items.add("${k}_$t" to "${k}_$t")
            val sections = if (isPoint(values, l.id)) listOf("pointchars_$t") else listOf("box_$t", "fitmenu_$t", "advancedmenu_$t", "paragraphmenu_$t")
            for (s in sections) m.items.add(s to s)
            datas[m.name] = m
        }
    }

    private val ownerRx = Regex("_tl([0-9a-z]+)$")

    /** True for a setting of a text layer that no longer exists (left out when saving). */
    fun isOrphan(name: String, values: Map<String, String>): Boolean {
        val id = ownerRx.find(name)?.groupValues?.get(1) ?: return false
        return ImageLayers.read(values).none { it.id == id && it.isText }
    }

    fun removeValues(values: MutableMap<String, String>, id: String) {
        val suffix = "_" + tm(id)
        values.keys.filter { it.endsWith(suffix) }.forEach { values.remove(it) }
    }

    fun copyValues(values: MutableMap<String, String>, from: String, to: String) {
        val a = "_" + tm(from)
        val b = "_" + tm(to)
        for ((k, v) in values.entries.filter { it.key.endsWith(a) }.map { it.key to it.value }) values[k.removeSuffix(a) + b] = v
    }
}
