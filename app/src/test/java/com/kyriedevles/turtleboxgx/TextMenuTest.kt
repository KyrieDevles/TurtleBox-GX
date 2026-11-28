package com.kyriedevles.turtleboxgx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMenuTest {
    private fun textEl(vararg opts: Pair<String, String>, click: String? = null): Element {
        val attrs = HashMap<String, String>()
        if (click != null) attrs["click"] = click
        val e = Element("text", attrs, 0)
        for ((k, v) in opts) e.options[k] = v
        e.text = "{{flavortext}}"
        return e
    }

    @Test
    fun buildsWholeMenu() {
        val datas = LinkedHashMap<String, DataDef>()
        val e = textEl("textmenu" to "flavor")
        TextMenu.expand(datas, listOf(e))
        assertEquals("textmenu_flavor", e.attrs["click"])
        assertEquals("box_flavor", e.attrs["boxdata"])
        assertEquals("{{sizepx_flavor}}", e.options["sizepx"])
        assertEquals("{{wordspacemin_flavor}}", e.options["wordspacemin"])
        assertEquals("{{halign_flavor}}", e.aligns.single().value)
        val top = datas["textmenu_flavor"]!!
        assertEquals(listOf("flavortext", "box_flavor", "fitmenu_flavor", "advancedmenu_flavor", "paragraphmenu_flavor"), top.items.map { it.second })
        assertEquals("80", datas["wordspacemin_flavor"]!!.default)
        assertEquals(7, datas["halign_flavor"]!!.items.size)
        // No fit steps written: no Steps / Status rows.
        val fit = datas["fitmenu_flavor"]!!.items.map { it.second }
        assertFalse("fitsteps_flavor" in fit)
        assertEquals(listOf("fitmode_flavor", "condense_flavor", "maxsize_flavor", "minsize_flavor"), fit)
        // Headings in Paragraph.
        val para = datas["paragraphmenu_flavor"]!!.items
        assertTrue(para.contains("Word Spacing" to "#"))
        assertEquals("#", para[para.indexOfFirst { it.second == "wordspace_flavor" } - 1].second)
    }

    @Test
    fun handWrittenWins() {
        val datas = LinkedHashMap<String, DataDef>()
        datas["sizepx_flavor"] = DataDef("sizepx_flavor", "11", "text", "Mine")
        val e = textEl("textmenu" to "flavor", "condense" to "{{condense}}", "tracking" to "20", "fitstep1" to "12 / 12 / 0", click = "flavormenu")
        e.attrs["boxdata"] = "flavorbox"
        TextMenu.expand(datas, listOf(e))
        assertEquals("11", datas["sizepx_flavor"]!!.default)
        assertEquals("{{condense}}", e.options["condense"])
        assertEquals("20", e.options["tracking"])
        assertNull(datas["tracking_flavor"])
        assertFalse(datas["advancedmenu_flavor"]!!.items.any { it.second == "tracking_flavor" })
        val fit = datas["fitmenu_flavor"]!!.items.map { it.second }
        assertEquals(listOf("fitsteps_flavor", "fitstatus_flavor", "fitsqueeze_flavor", "#", "fitmode_flavor", "condense", "maxsize_flavor", "minsize_flavor"), fit)
        assertEquals("sizepx_flavor", datas["fitstatus_flavor"]!!.default)
        assertEquals("fitstatus", datas["fitstatus_flavor"]!!.type)
        assertEquals(listOf("flavortext", "flavorbox", "fitmenu_flavor", "advancedmenu_flavor", "paragraphmenu_flavor"), datas["flavormenu"]!!.items.map { it.second })
        assertNull(datas["box_flavor"])
    }

    @Test
    fun ignoresElementsWithoutTag() {
        val datas = LinkedHashMap<String, DataDef>()
        val e = textEl()
        TextMenu.expand(datas, listOf(e))
        assertTrue(datas.isEmpty())
        assertTrue(e.options.isEmpty())
    }

    @Test
    fun definesSettingsTheElementNamesButLayoutLacks() {
        val datas = LinkedHashMap<String, DataDef>()
        val e = textEl("textmenu" to "xros", "tracking" to "{{tracking_xros}}", "condense" to "{{condense_xros}}")
        TextMenu.expand(datas, listOf(e))
        assertEquals("text", datas["tracking_xros"]!!.type)
        assertEquals("100", datas["condense_xros"]!!.default)
        for (g in listOf("fitmenu_xros", "advancedmenu_xros", "paragraphmenu_xros"))
            for ((_, v) in datas[g]!!.items) if (v != "#") assertTrue("$g lists $v", datas.containsKey(v))
    }

    @Test
    fun oneLineAddsCharacterToOwnMenu() {
        val datas = LinkedHashMap<String, DataDef>()
        val menu = DataDef("namemenu", null, "multi_select", "Name")
        menu.items.add("cardname" to "cardname")
        datas["namemenu"] = menu
        val e = textEl("textmenu" to "name", click = "namemenu")
        e.attrs["singleline"] = "true"
        e.text = "{{cardname}}"
        e.aligns.add(Cond(null, "left"))
        TextMenu.expand(datas, listOf(e))
        // No box, no Fit; a literal alignment stays out, so only Character is added.
        assertNull(e.attrs["boxdata"])
        assertFalse("fitmenu_name" in datas)
        assertEquals(listOf("cardname", "advancedmenu_name"), datas["namemenu"]!!.items.map { it.second })
        assertEquals("{{tracking_name}}", e.options["tracking"])
        assertEquals("Character", datas["advancedmenu_name"]!!.desc)
    }
}
