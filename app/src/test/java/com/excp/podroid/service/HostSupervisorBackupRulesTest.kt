package com.excp.podroid.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class HostSupervisorBackupRulesTest {
    @Test
    fun `supervisor datastore is excluded from legacy cloud and device transfer`() {
        val legacy = exclusions("backup_rules.xml", "full-backup-content")
        val extraction = parse("data_extraction_rules.xml")
        val cloud = exclusions(extraction, "cloud-backup")
        val transfer = exclusions(extraction, "device-transfer")

        assertEquals(setOf(EXCLUSION), legacy)
        assertEquals(setOf(EXCLUSION), cloud)
        assertEquals(setOf(EXCLUSION), transfer)
    }

    private fun exclusions(fileName: String, section: String): Set<Pair<String, String>> =
        exclusions(parse(fileName), section)

    private fun exclusions(
        document: org.w3c.dom.Document,
        section: String,
    ): Set<Pair<String, String>> {
        val root = document.getElementsByTagName(section).item(0)
            ?: error("Missing backup section $section")
        val children = root.childNodes
        return (0 until children.length).mapNotNull { index ->
            val node = children.item(index)
            if (node.nodeName != "exclude") null else {
                node.attributes.getNamedItem("domain").nodeValue to
                    node.attributes.getNamedItem("path").nodeValue
            }
        }.toSet()
    }

    private fun parse(fileName: String): org.w3c.dom.Document {
        val file = sequenceOf(
            File("src/main/res/xml/$fileName"),
            File("app/src/main/res/xml/$fileName"),
        ).firstOrNull(File::isFile) ?: error("Cannot locate $fileName")
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    }

    companion object {
        private val EXCLUSION =
            "file" to "datastore/host_supervisor_state.preferences_pb"
    }
}
