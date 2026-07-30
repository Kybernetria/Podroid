package com.excp.podroid.management

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementBackupRulesTest {
    @Test
    fun `host management state is excluded from backup cloud restore and device transfer`() {
        assertExcluded(parse("backup_rules.xml"), "full-backup-content")
        val extraction = parse("data_extraction_rules.xml")
        assertExcluded(extraction, "cloud-backup")
        assertExcluded(extraction, "device-transfer")
    }

    private fun assertExcluded(document: org.w3c.dom.Document, section: String) {
        val root = document.getElementsByTagName(section).item(0) ?: error("missing $section")
        val paths = (0 until root.childNodes.length).mapNotNull { index ->
            val node = root.childNodes.item(index)
            if (node.nodeName == "exclude" && node.attributes.getNamedItem("domain").nodeValue == "file") {
                node.attributes.getNamedItem("path").nodeValue
            } else null
        }
        assertTrue("files/host-management must be device-local", "host-management/" in paths)
    }

    private fun parse(fileName: String): org.w3c.dom.Document {
        val file = sequenceOf(
            File("src/main/res/xml/$fileName"),
            File("app/src/main/res/xml/$fileName"),
        ).firstOrNull(File::isFile) ?: error("Cannot locate $fileName")
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    }
}
