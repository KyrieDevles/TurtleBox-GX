package com.kyriedevles.turtleboxgx

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

class Rect4(val left: Float, val top: Float, val width: Float, val height: Float)
class FontSpec(val name: String, val style: String, val size: Float)
class Cond<T>(val whenExpr: String?, val value: T)
class DataDef(val name: String, val default: String?, val type: String?, val desc: String?) {
    /** Dropdown choices as (label, value). */
    val items = ArrayList<Pair<String, String>>()
}

/** One `<image>` or `<text>` element of the layout. Children with a `when` attribute are tried in order. */
class Element(val kind: String, val attrs: Map<String, String>, val order: Int) {
    val sizes = ArrayList<Cond<Rect4>>()
    val srcs = ArrayList<Cond<String>>()
    val colors = ArrayList<Cond<String>>()
    val aligns = ArrayList<Cond<String>>()
    val fonts = ArrayList<Cond<FontSpec>>()
    val options = HashMap<String, String>()
    var visible: String? = null
    var text: String = ""
    val index: Float get() = attrs["index"]?.toFloatOrNull() ?: 0f
}

class Layout(
    val width: Float,
    val height: Float,
    val datas: Map<String, DataDef>,
    val bools: Map<String, String>,
    val elements: List<Element>,
    val styleInfo: Map<String, String>,
)

object LayoutParser {
    class ParseError(message: String, val line: Int, val column: Int) : Exception(message)

    fun parse(xml: String): Layout {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(StringReader(xml.trimStart('﻿')))
        var width = 421f
        var height = 613f
        val datas = LinkedHashMap<String, DataDef>()
        val bools = LinkedHashMap<String, String>()
        val elements = ArrayList<Element>()
        val styleInfo = LinkedHashMap<String, String>()
        var curData: DataDef? = null
        var inStyleInfo = false
        var inLayout = false
        var cur: Element? = null
        var curDepth = 0
        try {
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val n = p.name
                    val c = cur
                    if (c != null) {
                        child(c, p)
                    } else when {
                        n == "style" && p.depth == 1 -> {
                            width = p.getAttributeValue(null, "width")?.toFloatOrNull() ?: width
                            height = p.getAttributeValue(null, "height")?.toFloatOrNull() ?: height
                        }
                        n == "styleinfo" -> inStyleInfo = true
                        inStyleInfo -> styleInfo[n] = p.nextText().trim()
                        n == "data" && !inLayout -> {
                            val name = p.getAttributeValue(null, "name")
                            if (name != null) {
                                val d = DataDef(
                                    name,
                                    p.getAttributeValue(null, "default"),
                                    p.getAttributeValue(null, "type"),
                                    p.getAttributeValue(null, "desc"),
                                )
                                datas[name] = d
                                curData = if (p.isEmptyElementTag) null else d
                            }
                        }
                        n == "item" && curData != null -> {
                            val label = p.getAttributeValue(null, "name") ?: ""
                            curData?.items?.add(Pair(label, p.getAttributeValue(null, "value") ?: label))
                        }
                        n == "bool" && !inLayout -> {
                            val name = p.getAttributeValue(null, "name")
                            val value = p.getAttributeValue(null, "value")
                            if (name != null && value != null) bools[name] = value
                        }
                        n == "layout" -> inLayout = true
                        inLayout && (n == "image" || n == "text") -> {
                            cur = Element(n, attrsOf(p), elements.size)
                            curDepth = p.depth
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    val c = cur
                    if (c != null && p.depth == curDepth && p.name == c.kind) {
                        elements.add(c)
                        cur = null
                    } else if (c == null && p.name == "layout") {
                        inLayout = false
                    } else if (c == null && p.name == "data") {
                        curData = null
                    } else if (c == null && p.name == "styleinfo") {
                        inStyleInfo = false
                    }
                }
                event = p.next()
            }
        } catch (e: org.xmlpull.v1.XmlPullParserException) {
            throw ParseError(e.message ?: "XML error", p.lineNumber, p.columnNumber)
        }
        return Layout(width, height, datas, bools, elements, styleInfo)
    }

    private fun attrsOf(p: XmlPullParser): Map<String, String> {
        val m = HashMap<String, String>()
        for (i in 0 until p.attributeCount) m[p.getAttributeName(i)] = p.getAttributeValue(i)
        return m
    }

    private fun child(e: Element, p: XmlPullParser) {
        val whenExpr = p.getAttributeValue(null, "when")
        when (val n = p.name) {
            "size" -> e.sizes.add(
                Cond(
                    whenExpr,
                    Rect4(
                        p.getAttributeValue(null, "left")?.toFloatOrNull() ?: 0f,
                        p.getAttributeValue(null, "top")?.toFloatOrNull() ?: 0f,
                        p.getAttributeValue(null, "width")?.toFloatOrNull() ?: 0f,
                        p.getAttributeValue(null, "height")?.toFloatOrNull() ?: 0f,
                    ),
                ),
            )
            "font" -> e.fonts.add(
                Cond(
                    whenExpr,
                    FontSpec(
                        p.getAttributeValue(null, "name") ?: "",
                        p.getAttributeValue(null, "style") ?: "",
                        p.getAttributeValue(null, "size")?.toFloatOrNull() ?: 12f,
                    ),
                ),
            )
            "src" -> e.srcs.add(Cond(whenExpr, p.nextText().trim()))
            "color" -> e.colors.add(Cond(whenExpr, p.nextText().trim()))
            "align" -> e.aligns.add(Cond(whenExpr, p.nextText().trim()))
            "visible" -> e.visible = p.nextText().trim()
            "text" -> e.text = p.nextText()
            else -> if (p.isEmptyElementTag.not()) e.options[n] = p.nextText().trim()
        }
    }
}
