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
 *  - root attributes `width` / `height` change the card size.
 *  - `is_<name>` is forced to true while the template is active.
 * An element is found by its `id` attribute, or by the XML comment written just above it (lower-cased, spaces become `-`).
 */
object Templates {
    class Info(val name: String, val label: String, val xml: String)
    class Result(val xml: String, val notes: List<String>)

    private fun parse(xml: String): Document {
        val f = DocumentBuilderFactory.newInstance()
        return f.newDocumentBuilder().parse(InputSource(StringReader(xml.trimStart('﻿'))))
    }

    fun info(name: String, xml: String): Info {
        val label = try {
            parse(xml).documentElement.getAttribute("label").ifBlank { name }
        } catch (e: Exception) {
            name
        }
        return Info(name, label, xml)
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
        var n: Node? = layoutEl.firstChild
        while (n != null) {
            when {
                n.nodeType == Node.COMMENT_NODE -> comment = slug(n.nodeValue ?: "").ifBlank { null }
                n is Element && (n.tagName == "image" || n.tagName == "text") -> {
                    val id = n.getAttribute("id").ifBlank { comment }
                    if (id != null && !targets.containsKey(id)) targets[id] = n
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
                    for (tag in parts.map { it.tagName }.distinct()) {
                        kids(el).filter { it.tagName == tag }.forEach { el.removeChild(it) }
                    }
                    for (p in parts) el.appendChild(doc.importNode(p, true))
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
                else -> notes.add("template ${t.name}: unknown <${c.tagName}> ignored")
            }
        }
        tdoc.hashCode()
        val tf = TransformerFactory.newInstance().newTransformer()
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        tf.setOutputProperty(OutputKeys.INDENT, "no")
        val sw = StringWriter()
        tf.transform(DOMSource(doc), StreamResult(sw))
        return Result(sw.toString(), notes)
    }
}
