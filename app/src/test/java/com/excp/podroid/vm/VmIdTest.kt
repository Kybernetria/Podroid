package com.excp.podroid.vm

import org.junit.Assert.assertEquals
import org.junit.Test

class VmIdTest {
    @Test
    fun `default serializes exactly`() {
        assertEquals("default", VmId.DEFAULT.serialized)
        assertEquals("default", VmId.DEFAULT.toString())
        assertEquals(VmId.DEFAULT, VmId.parse("default"))
    }

    @Test
    fun `rejects unsupported traversal separators dots and invalid tokens`() {
        listOf(
            "", ".", "..", "../default", "default/child", "default\\child",
            "/default", "DEFAULT", " default", "default ", "default.",
            "other", "default%2fchild", "default\u0000child",
        ).forEach { raw ->
            val result = runCatching { VmId.parse(raw) }
            check(result.isFailure) { "VmId unexpectedly accepted '$raw'" }
        }
    }
}
