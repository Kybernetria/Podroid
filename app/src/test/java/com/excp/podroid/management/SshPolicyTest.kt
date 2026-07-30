package com.excp.podroid.management

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshPolicyTest {
    private val ca = "a".repeat(64)
    private val certFingerprint = "b".repeat(64)
    private val peer = TransportAuthenticatedIdentity("tailscale", "node-1", true)

    @Test
    fun `only valid bound Ed25519 user cert with exactly one role is accepted`() {
        ManagementRole.entries.forEach { role ->
            val decision = ManagementCertificatePolicy.authenticate(
                ManagementLimits.SSH_USERNAME,
                certificate(principals = listOf(role.principal)),
                peer,
                1_100,
                Trust(),
            )
            assertEquals(role, (decision as AuthenticationDecision.Allow).role)
        }
    }

    @Test
    fun `username type CA revocation clock options principals and binding fail closed`() {
        assertDenied(AuthenticationDenial.WRONG_USERNAME, username = "root")
        assertDenied(AuthenticationDenial.TRANSPORT_IDENTITY_UNAUTHENTICATED, identity = peer.copy(cryptographicallyAuthenticated = false))
        assertDenied(AuthenticationDenial.UNSUPPORTED_CERTIFICATE, cert = certificate(subjectKeyType = "ssh-rsa-cert-v01@openssh.com"))
        assertDenied(AuthenticationDenial.UNSUPPORTED_CERTIFICATE, cert = certificate(signatureKeyType = "ssh-rsa"))
        assertDenied(AuthenticationDenial.UNSUPPORTED_CERTIFICATE, cert = certificate(certificateType = "host"))
        assertDenied(AuthenticationDenial.UNTRUSTED_CA, trust = Trust(trusted = false))
        assertDenied(AuthenticationDenial.REVOKED, trust = Trust(revoked = true))
        assertDenied(AuthenticationDenial.INVALID_VALIDITY, now = 999)
        assertDenied(AuthenticationDenial.INVALID_VALIDITY, now = 1_200)
        assertDenied(AuthenticationDenial.INVALID_VALIDITY, cert = certificate(validBefore = 1_000 + ManagementLimits.MAX_CERT_VALIDITY_SECONDS + 1))
        assertDenied(
            AuthenticationDenial.INVALID_VALIDITY,
            cert = certificate(validAfter = Long.MAX_VALUE, validBefore = Long.MIN_VALUE),
            now = Long.MAX_VALUE,
        )
        assertDenied(AuthenticationDenial.INVALID_PRINCIPALS, cert = certificate(principals = emptyList()))
        assertDenied(AuthenticationDenial.INVALID_PRINCIPALS, cert = certificate(principals = listOf("read", "operate")))
        assertDenied(AuthenticationDenial.INVALID_PRINCIPALS, cert = certificate(principals = listOf("unknown")))
        assertDenied(AuthenticationDenial.OPTIONS_OR_EXTENSIONS_PRESENT, cert = certificate(options = setOf("force-command")))
        assertDenied(AuthenticationDenial.OPTIONS_OR_EXTENSIONS_PRESENT, cert = certificate(extensions = setOf("permit-pty")))
        assertDenied(AuthenticationDenial.TRANSPORT_IDENTITY_MISMATCH, trust = Trust(binding = TransportIdentityBinding("tailscale", "other")))
        assertDenied(AuthenticationDenial.TRANSPORT_IDENTITY_MISMATCH, trust = Trust(binding = null))
    }

    @Test
    fun `read and operate roles permit only exact management exec command`() {
        for (role in listOf(ManagementRole.READ, ManagementRole.OPERATE)) {
            assertEquals(
                ChannelDecision.AllowManagementExec,
                ManagementChannelPolicy.authorize(role, SshChannelRequest.Exec("podroid-management-v1")),
            )
            assertEquals(
                ChannelDecision.Deny(ChannelDenial.COMMAND_NOT_EXACT),
                ManagementChannelPolicy.authorize(role, SshChannelRequest.Exec("podroid-management-v1 ")),
            )
        }
        assertTrue(ManagementRole.READ.permits(ManagementOperation.VM_DEFAULT_STATUS))
        assertTrue(!ManagementRole.READ.permits(ManagementOperation.VM_DEFAULT_START))
        assertTrue(ManagementRole.OPERATE.permits(ManagementOperation.VM_DEFAULT_STOP))
    }

    @Test
    fun `nested guest SSH is one exact virtual target resolved internally`() {
        val allowed = ManagementChannelPolicy.authorize(
            ManagementRole.VM_DEFAULT_SSH,
            SshChannelRequest.DirectTcpip("vm/default/ssh", 22),
        ) as ChannelDecision.AllowGuestForward
        assertEquals(ResolvedGuestForward("127.0.0.1", 9922), allowed.target)
        assertEquals(
            ChannelDecision.Deny(ChannelDenial.FORWARD_TARGET_FORBIDDEN),
            ManagementChannelPolicy.authorize(
                ManagementRole.VM_DEFAULT_SSH,
                SshChannelRequest.DirectTcpip("127.0.0.1", 9922),
            ),
        )
        assertEquals(
            ChannelDecision.Deny(ChannelDenial.ROLE_FORBIDDEN),
            ManagementChannelPolicy.authorize(
                ManagementRole.OPERATE,
                SshChannelRequest.DirectTcpip("vm/default/ssh", 22),
            ),
        )
        assertEquals(
            ChannelDecision.Deny(ChannelDenial.ROLE_FORBIDDEN),
            ManagementChannelPolicy.authorize(
                ManagementRole.VM_DEFAULT_SSH,
                SshChannelRequest.Exec(ManagementLimits.EXEC_COMMAND),
            ),
        )
    }

    @Test
    fun `every shell PTY env subsystem X11 agent global reverse and unknown request is denied`() {
        val denied = listOf(
            SshChannelRequest.Shell,
            SshChannelRequest.Pty,
            SshChannelRequest.Environment,
            SshChannelRequest.Subsystem,
            SshChannelRequest.X11Forwarding,
            SshChannelRequest.AgentForwarding,
            SshChannelRequest.GlobalTcpipForward,
            SshChannelRequest.CancelGlobalTcpipForward,
            SshChannelRequest.Unknown("future@vendor"),
        )
        ManagementRole.entries.forEach { role ->
            denied.forEach { request ->
                assertEquals(
                    ChannelDecision.Deny(ChannelDenial.CHANNEL_TYPE_FORBIDDEN),
                    ManagementChannelPolicy.authorize(role, request),
                )
            }
        }
    }

    private fun assertDenied(
        expected: AuthenticationDenial,
        username: String = ManagementLimits.SSH_USERNAME,
        cert: SshUserCertificate = certificate(),
        identity: TransportAuthenticatedIdentity = peer,
        now: Long = 1_100,
        trust: Trust = Trust(),
    ) {
        assertEquals(
            AuthenticationDecision.Deny(expected),
            ManagementCertificatePolicy.authenticate(username, cert, identity, now, trust),
        )
    }

    private fun certificate(
        certificateType: String = "user",
        subjectKeyType: String = ManagementCertificatePolicy.ED25519_CERT,
        signatureKeyType: String = ManagementCertificatePolicy.ED25519_KEY,
        principals: List<String> = listOf("read"),
        validAfter: Long = 1_000,
        validBefore: Long = 1_200,
        options: Set<String> = emptySet(),
        extensions: Set<String> = emptySet(),
    ) = SshUserCertificate(
        certificateType = certificateType,
        subjectKeyType = subjectKeyType,
        signatureKeyType = signatureKeyType,
        serial = 7,
        keyId = "enrollment-7",
        principals = principals,
        validAfterEpochSeconds = validAfter,
        validBeforeEpochSeconds = validBefore,
        criticalOptions = options,
        extensions = extensions,
        caFingerprintSha256 = ca,
        certificateFingerprintSha256 = certFingerprint,
    )

    private inner class Trust(
        private val trusted: Boolean = true,
        private val revoked: Boolean = false,
        private val binding: TransportIdentityBinding? = TransportIdentityBinding("tailscale", "node-1"),
    ) : ManagementTrustStore {
        override fun isTrustedCa(caFingerprintSha256: String) = trusted && caFingerprintSha256 == ca
        override fun isRevoked(certificateFingerprintSha256: String, serial: Long) = revoked
        override fun transportBinding(certificateFingerprintSha256: String) = binding
    }
}
