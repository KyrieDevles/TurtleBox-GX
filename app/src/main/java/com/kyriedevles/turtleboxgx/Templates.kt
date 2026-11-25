package com.kyriedevles.turtleboxgx

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Template patches. A template is a small XML file (`templates/<name>.xml`) whose root is `<template>`; it is merged
 * into the base layout before the layout is read, so the rest of the app never knows. See the style-patches spec.
 *
 *  - `<patch target="id">`: each child tag present replaces that tag's entries on the element (all `<size>`, all `<src>`, `<visible>`, …).
 *    Other attributes on `<patch>` (e.g. `index`) are set on the element.
 *  - `<hide target="id"/>`: the element is never visible.
 *  - `<add>`: new `<image>`/`<text>` elements (added to the layout) and `<data>`/`<bool>` entries (added to the base).
 *  - `<hide-data name="x"/>`: removes the field from every settings menu that lists it; `<default name="x" value="y"/>`: changes a data's default.
 *  - `<add-item menu="m" value="#" name="Heading"/>` adds a section heading (can repeat); `<add-item menu="m" value="x" before="y"/>` adds option x to menu m (at the end, or before y); `name="Label"` sets the text shown.
 *  - `<patch>` children may carry mode="prepend" / "append" to add entries above/below the existing ones instead of replacing them.
 *  - `<set>` holds `<data>`/`<bool>` that replace a same-named one in the base (or add it).
 *  - `<add-tile menu label icon status before/>` / `<hide-tile menu/>` change the Card page's edit dock tiles.
 *  - root attributes `width` / `height` change the card size; `description`, `author`, `version` show in the picker; `requires="a b"` warns if the layout lacks those names.
 *  - `is_<name>` is forced to true while the template is active.
 * An element is found by its `id` attribute, or by the XML comment written just above it (lower-cased, spaces become `-`).
 * Several elements under the same comment are `name`, `name#2`, `name#3`, …; any element can also be addressed as `#N`
 * (its position among the layout's images and texts, starting at 0), which is fragile if the base is reordered.
 */
object Templates {
    class Info(val name: String, val label: String, val xml: String, val description: String = "", val author: String = "", val version: String = "")
    class Result(val xml: String, val notes: List<String>)

    private fun parse(xml: String): Document {
        val f = DocumentBuilderFactory.newInstance()
        return f.newDocumentBuilder().parse(InputSource(StringReader(xml.trimStart('﻿'))))
    }

    fun info(name: String, xml: String): Info {
        return try {
            val r = parse(xml).documentElement
            Info(name, r.getAttribute("label").ifBlank { name }, xml, r.getAttribute("description"), r.getAttribute("author"), r.getAttribute("version"))
        } catch (e: Exception) {
            Info(name, name, xml)
        }
    }

    fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun kids(e: Element): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = e.firstChild
        while (n != null) {
            if (n is Element) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    fun apply(base: String, t: Info): Result {
        val notes = ArrayList<String>()
        val doc = parse(base)
        val root = doc.documentElement
        val troot = parse(t.xml).documentElement
        val tdoc = troot.ownerDocument
        for (a in listOf("width", "height")) if (troot.hasAttribute(a)) root.setAttribute(a, troot.getAttribute(a))
        val layoutEl = kids(root).firstOrNull { it.tagName == "layout" } ?: return Result(base, listOf("The base layout has no <layout>."))

        // Targets: id attribute, or the comment above the element.
        val targets = LinkedHashMap<String, Element>()
        var comment: String? = null
        var elementNo = 0
        val labelCount = HashMap<String, Int>()
        var n: Node? = layoutEl.firstChild
        while (n != null) {
            when {
                n.nodeType == Node.COMMENT_NODE -> comment = slug(n.nodeValue ?: "").ifBlank { null }
                n is Element && (n.tagName == "image" || n.tagName == "text") -> {
                    val id = n.getAttribute("id").ifBlank { comment }
                    if (id != null) {
                        val k = (labelCount[id] ?: 0) + 1
                        labelCount[id] = k
                        // The first element with a label is `label`, the next ones `label#2`, `label#3`, …
                        targets[if (k == 1) id else "$id#$k"] = n
                    }
                    targets["#$elementNo"] = n
                    elementNo++
                    comment = null
                }
                n is Element -> comment = null
            }
            n = n.nextSibling
        }

        // The auto flag.
        val flag = "is_" + t.name.lowercase().replace(Regex("[^a-z0-9_]+"), "_")
        val existing = kids(root).firstOrNull { it.tagName == "bool" && it.getAttribute("name") == flag }
        if (existing != null) {
            existing.setAttribute("value", "true")
        } else {
            val b = doc.createElement("bool")
            b.setAttribute("name", flag)
            b.setAttribute("value", "true")
            root.insertBefore(b, layoutEl)
        }

        for (c in kids(troot)) {
            when (c.tagName) {
                "patch" -> {
                    val el = targets[c.getAttribute("target")]
                    if (el == null) {
                        notes.add("template ${t.name}: no element '${c.getAttribute("target")}'")
                        continue
                    }
                    val attrs = c.attributes
                    for (i in 0 until attrs.length) {
                        val a = attrs.item(i)
                        if (a.nodeName != "target") el.setAttribute(a.nodeName, a.nodeValue)
                    }
                    val parts = kids(c)
                    val replacing = parts.filter { !it.hasAttribute("mode") }
                    val adding = parts.filter { it.hasAttribute("mode") }
                    // Entries without mode replace all of that tag's entries; entries with mode="prepend" / "append"
                    // are put above / below the entries the element already has.
                    for (tag in replacing.map { it.tagName }.distinct()) {
                        kids(el).filter { it.tagName == tag }.forEach { el.removeChild(it) }
                    }
                    for (p in replacing) el.appendChild(doc.importNode(p, true))
                    for (p in adding) {
                        val copy = doc.importNode(p, true) as Element
                        val mode = copy.getAttribute("mode")
                        copy.removeAttribute("mode")
                        val same = kids(el).filter { it.tagName == copy.tagName }
                        when {
                            same.isEmpty() -> el.appendChild(copy)
                            mode == "prepend" -> el.insertBefore(copy, same.first())
                            else -> el.insertBefore(copy, same.last().nextSibling)
                        }
                    }
                }
                "hide" -> {
                    val el = targets[c.getAttribute("target")]
                    if (el == null) {
                        notes.add("template ${t.name}: no element '${c.getAttribute("target")}'")
                        continue
                    }
                    kids(el).filter { it.tagName == "visible" }.forEach { el.removeChild(it) }
                    val v = doc.createElement("visible")
                    v.appendChild(doc.createTextNode("false"))
                    el.appendChild(v)
                }
                "add" -> for (a in kids(c)) {
                    val copy = doc.importNode(a, true)
                    if (a.tagName == "data" || a.tagName == "bool") {
                        val dup = kids(root).any { it.tagName == a.tagName && it.getAttribute("name") == a.getAttribute("name") }
                        if (dup) notes.add("template ${t.name}: '${a.getAttribute("name")}' already exists, skipped") else root.insertBefore(copy, layoutEl)
                    } else {
                        layoutEl.appendChild(copy)
                    }
                }
                "set" -> for (a in kids(c)) {
                    if (a.tagName != "data" && a.tagName != "bool") continue
                    val copy = doc.importNode(a, true)
                    val old = kids(root).firstOrNull { it.tagName == a.tagName && it.getAttribute("name") == a.getAttribute("name") }
                    if (old != null) {
                        root.replaceChild(copy, old)
                        notes.add("template ${t.name}: replaced '${a.getAttribute("name")}'")
                    } else {
                        root.insertBefore(copy, layoutEl)
                    }
                }
                "hide-data" -> {
                    // Removes the field from every settings menu (multi_select) that lists it. The data itself stays defined.
                    val name = c.getAttribute("name")
                    var found = false
                    for (d in kids(root).filter { it.tagName == "data" && it.getAttribute("type") == "multi_select" }) {
                        for (item in kids(d).filter { it.tagName == "item" && (it.getAttribute("value") == name || it.getAttribute("name") == name) }) {
                            d.removeChild(item)
                            found = true
                        }
                    }
                    if (!found) notes.add("template ${t.name}: no menu field '$name' to hide")
                }
                "add-tile", "hide-tile" -> {
                    // The Card page's edit dock. <tiles> is created with the five default tiles if the base has none.
                    val tiles: Element = kids(root).firstOrNull { it.tagName == "tiles" } ?: run {
                        val tiles = doc.createElement("tiles")
                        for ((m, l, i) in listOf(
                            Triple("cardtextmenu", "Effect text", "text"), Triple("cardname", "Name", "name"),
                            Triple("atkdef", "Stats", "stats"), Triple("cardbg", "Frame", "frame"),
                        )) {
                            val e = doc.createElement("tile")
                            e.setAttribute("menu", m); e.setAttribute("label", l); e.setAttribute("icon", i)
                            tiles.appendChild(e)
                        }
                        root.insertBefore(tiles, layoutEl)
                        tiles
                    }
                    val menu = c.getAttribute("menu")
                    if (c.tagName == "hide-tile") {
                        val old = kids(tiles).firstOrNull { it.getAttribute("menu") == menu }
                        if (old == null) notes.add("template ${t.name}: no tile '$menu' to hide") else tiles.removeChild(old)
                    } else if (kids(tiles).none { it.getAttribute("menu") == menu }) {
                        val e = doc.createElement("tile")
                        e.setAttribute("menu", menu)
                        e.setAttribute("label", c.getAttribute("label").ifBlank { menu })
                        e.setAttribute("icon", c.getAttribute("icon").ifBlank { "dot" })
                        if (c.hasAttribute("status")) e.setAttribute("status", c.getAttribute("status"))
                        e.setAttribute("tpl", "1")
                        val before = c.getAttribute("before")
                        val ref = if (before.isBlank()) null else kids(tiles).firstOrNull { it.getAttribute("menu") == before }
                        if (ref != null) tiles.insertBefore(e, ref) else tiles.appendChild(e)
                    }
                }
                "add-item" -> {
                    // Adds an option to an existing menu (multi_select) while the template is active.
                    val menuName = c.getAttribute("menu")
                    val v = c.getAttribute("value")
                    val m = kids(root).firstOrNull { it.tagName == "data" && it.getAttribute("name") == menuName }
                    if (m == null || v.isBlank()) {
                        notes.add("template ${t.name}: no menu '$menuName' for add-item")
                    } else if (v.startsWith("#") || kids(m).none { it.tagName == "item" && it.getAttribute("value") == v }) {
                        val item = doc.createElement("item")
                        item.setAttribute("name", c.getAttribute("name").ifBlank { v })
                        item.setAttribute("value", v)
                        val before = c.getAttribute("before")
                        val ref = if (before.isBlank()) null else kids(m).firstOrNull { it.tagName == "item" && it.getAttribute("value") == before }
                        if (ref != null) m.insertBefore(item, ref) else m.appendChild(item)
                    }
                }
                "default" -> {
                    val d = kids(root).firstOrNull { it.tagName == "data" && it.getAttribute("name") == c.getAttribute("name") }
                    if (d == null) notes.add("template ${t.name}: no data '${c.getAttribute("name")}' for default") else d.setAttribute("default", c.getAttribute("value"))
                }
                else -> notes.add("template ${t.name}: unknown <${c.tagName}> ignored")
            }
        }
        val tf = TransformerFactory.newInstance().newTransformer()
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        tf.setOutputProperty(OutputKeys.INDENT, "no")
        val sw = StringWriter()
        // requires="a b c": names the template relies on in the base (or adds itself).
        troot.getAttribute("requires").split(Regex("[,\\s]+")).filter { it.isNotBlank() }.forEach { need ->
            val has = kids(root).any { (it.tagName == "data" || it.tagName == "bool") && it.getAttribute("name") == need }
            if (!has) notes.add("template ${t.name}: needs '$need' but the layout does not define it")
        }
        tf.transform(DOMSource(doc), StreamResult(sw))
        return Result(sw.toString(), notes)
    }
}


/** Names a layout uses (in {{name}} or click="name") that nothing defines. */
object NameCheck {
    // Names the app itself provides at run time.
    private val builtin = setOf("template")

    fun undefined(xml: String, layout: Layout): List<String> {
        val body = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val out = LinkedHashSet<String>()
        for (m in Regex("\\{\\{([^{}]+)\\}\\}").findAll(body)) {
            val n = m.groupValues[1].trim()
            if (n.isEmpty() || n in builtin) continue
            if (!layout.datas.containsKey(n) && !layout.bools.containsKey(n)) out.add(n)
        }
        for (m in Regex("click=\"([^\"]+)\"").findAll(body)) {
            val n = m.groupValues[1].trim()
            if (n.isNotEmpty() && !layout.datas.containsKey(n)) out.add(n)
        }
        return out.toList()
    }
}
