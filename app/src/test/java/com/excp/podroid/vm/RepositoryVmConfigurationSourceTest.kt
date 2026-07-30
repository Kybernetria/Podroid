package com.excp.podroid.vm

import com.excp.podroid.data.repository.PortForwardRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RepositoryVmConfigurationSourceTest {
    private val implicitSsh = PortForwardRule(9922, 22, "tcp", loopbackOnly = true)

    @Test fun `implicit SSH is always loopback only and reports the fixed endpoint`() {
        val rules = RepositoryVmConfigurationSource.assembleRules(emptyList(), sshEnabled = true)
        assertEquals(listOf(implicitSsh), rules)
        assertEquals("127.0.0.1", DefaultVmManager.SSH_HOST)
        assertEquals(9922, DefaultVmManager.SSH_HOST_PORT)
    }

    @Test fun `every non-exact persisted TCP 9922 rule conflicts with SSH`() {
        val conflicts = listOf(
            PortForwardRule(9922, 22, "tcp", loopbackOnly = false),
            PortForwardRule(9922, 80, "tcp", loopbackOnly = false),
            PortForwardRule(9922, 80, "tcp", loopbackOnly = true),
        )
        for (rule in conflicts) {
            try {
                RepositoryVmConfigurationSource.assembleRules(listOf(rule), sshEnabled = true)
                fail("Expected conflict for $rule")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("127.0.0.1:9922"))
            }
        }
    }

    @Test fun `downloaded profile launch rejects nonblank QEMU extras before command construction`() {
        RepositoryVmConfigurationSource.requireSignedProfileQemuArgsAreClosed(true, "")
        RepositoryVmConfigurationSource.requireSignedProfileQemuArgsAreClosed(false, "-nodefaults")
        val failure = runCatching {
            RepositoryVmConfigurationSource.requireSignedProfileQemuArgsAreClosed(
                downloadedProfileActive = true,
                qemuExtraArgs = "  -kernel /tmp/override  ",
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test fun `exact loopback SSH rule is deduplicated`() {
        assertEquals(
            listOf(implicitSsh),
            RepositoryVmConfigurationSource.assembleRules(listOf(implicitSsh), sshEnabled = true),
        )
    }
}
