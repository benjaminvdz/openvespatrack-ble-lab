package org.anthrobios.vespasim.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every supported interface must render the same data fields, including plural counts and errors. */
class MainResourcesTest {
    private fun strings(folder: String): Map<String, String> {
        val file = File("src/main/res/$folder/main_strings.xml")
            .takeIf { it.isFile } ?: File("app/src/main/res/$folder/main_strings.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    @Test fun dutchAndFrenchContainAllMainLabelsAndPreserveEveryFormatArgument() {
        val english = strings("values")
        val placeholders = Regex("%[0-9]+\\\$[-+.0-9]*[a-zA-Z]")
        for (folder in listOf("values-nl", "values-fr")) {
            val translated = strings(folder)
            assertEquals("Complete resource set for $folder", english.keys, translated.keys)
            english.forEach { (key, value) ->
                val localized = requireNotNull(translated[key])
                assertTrue("Nonempty translation $folder/$key", localized.isNotBlank())
                assertEquals("Format arguments in $folder/$key",
                    placeholders.findAll(value).map { it.value }.toList().sorted(),
                    placeholders.findAll(localized).map { it.value }.toList().sorted())
            }
        }
    }

    @Test fun everyPurePresentationLabelHasAResourceInAllLanguages() {
        for (folder in listOf("values", "values-nl", "values-fr")) {
            val resources = strings(folder)
            UiLogic.Label.entries.forEach { label ->
                assertTrue("$folder supports ${label.name}", resources.containsKey("main_logic_${label.name.lowercase(java.util.Locale.ROOT)}"))
            }
        }
    }
}
