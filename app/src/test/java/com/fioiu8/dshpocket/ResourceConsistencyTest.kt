package com.fioiu8.dshpocket

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class ResourceConsistencyTest {

    @Test
    fun englishAndSimplifiedChineseResourcesHaveMatchingNamesAndArguments() {
        val english = readResources("values/strings.xml")
        val chinese = readResources("values-zh-rCN/strings.xml")
        assertEquals("resource names", english.keys, chinese.keys)
        english.forEach { (name, value) ->
            assertEquals("resource type for $name", value.kind, chinese.getValue(name).kind)
            assertEquals("format arguments for $name", placeholders(value.text), placeholders(chinese.getValue(name).text))
        }
    }

    private data class ResourceValue(val kind: String, val text: String)

    private fun readResources(relative: String): Map<String, ResourceValue> {
        val candidates = listOf(
            File("src/main/res/$relative"),
            File("app/src/main/res/$relative"),
            File("../app/src/main/res/$relative"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = linkedMapOf<String, ResourceValue>()
        val nodes = document.documentElement.childNodes
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            when (element.tagName) {
                "string" -> result[element.getAttribute("name")] = ResourceValue("string", element.textContent)
                "plurals" -> {
                    val values = element.getElementsByTagName("item")
                    result[element.getAttribute("name")] = ResourceValue("plurals", buildString {
                        for (itemIndex in 0 until values.length) append(values.item(itemIndex).textContent).append('\u0000')
                    })
                }
            }
        }
        return result
    }

    private fun placeholders(value: String): List<String> =
        Regex("%\\d+\\$[a-zA-Z]").findAll(value).map { it.value }.distinct().toList()
}
