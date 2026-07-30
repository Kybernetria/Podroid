/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.management

enum class ManagementRole(val principal: String) {
    READ("read"),
    OPERATE("operate"),
    VM_DEFAULT_SSH("vm-default-ssh");

    fun permits(operation: ManagementOperation): Boolean = when (this) {
        READ -> !operation.mutation
        OPERATE -> true
        VM_DEFAULT_SSH -> false
    }

    companion object {
        fun fromPrincipal(value: String): ManagementRole? = entries.singleOrNull { it.principal == value }
    }
}

data class TransportAuthenticatedIdentity(
    val providerId: String,
    val nodeId: String,
    val cryptographicallyAuthenticated: Boolean,
) {
    init {
        require(providerId.matches(Regex("[a-z0-9-]{1,64}")))
        require(nodeId.length in 1..ManagementLimits.MAX_TRANSPORT_IDENTITY_CHARS)
        require(nodeId.none(Char::isISOControl))
    }
}

data class SshUserCertificate(
    val certificateType: String,
    val subjectKeyType: String,
    val signatureKeyType: String,
    val serial: Long,
    val keyId: String,
    val principals: List<String>,
    val validAfterEpochSeconds: Long,
    val validBeforeEpochSeconds: Long,
    val criticalOptions: Set<String>,
    val extensions: Set<String>,
    val caFingerprintSha256: String,
    val certificateFingerprintSha256: String,
) {
    init {
        require(serial > 0)
        require(keyId.length in 1..ManagementLimits.MAX_KEY_ID_CHARS && keyId.none(Char::isISOControl))
        require(principals.size <= 4 && principals.all { it.length in 1..64 && it.none(Char::isISOControl) })
        require(criticalOptions.size <= 16 && extensions.size <= 16)
        require(caFingerprintSha256.isSha256())
        require(certificateFingerprintSha256.isSha256())
    }
}

data class TransportIdentityBinding(val providerId: String, val nodeId: String) {
    init {
        require(providerId.matches(Regex("[a-z0-9-]{1,64}")))
        require(nodeId.length in 1..ManagementLimits.MAX_TRANSPORT_IDENTITY_CHARS)
        require(nodeId.none(Char::isISOControl))
    }
}

/** Authoritative local enrollment state; certificate input cannot assert its own transport binding. */
interface ManagementTrustStore {
    fun isTrustedCa(caFingerprintSha256: String): Boolean
    fun isRevoked(certificateFingerprintSha256: String, serial: Long): Boolean
    fun transportBinding(certificateFingerprintSha256: String): TransportIdentityBinding?
}

enum class AuthenticationDenial {
    WRONG_USERNAME,
    TRANSPORT_IDENTITY_UNAUTHENTICATED,
    UNSUPPORTED_CERTIFICATE,
    UNTRUSTED_CA,
    REVOKED,
    INVALID_VALIDITY,
    INVALID_PRINCIPALS,
    OPTIONS_OR_EXTENSIONS_PRESENT,
    TRANSPORT_IDENTITY_MISMATCH,
}

sealed interface AuthenticationDecision {
    data class Allow(
        val role: ManagementRole,
        val certificateFingerprintSha256: String,
        val transportIdentity: TransportAuthenticatedIdentity,
    ) : AuthenticationDecision
    data class Deny(val reason: AuthenticationDenial) : AuthenticationDecision
}

/** Pure OpenSSH-certificate policy. Cryptographic signature verification remains an SSH-provider obligation. */
object ManagementCertificatePolicy {
    const val ED25519_CERT = "ssh-ed25519-cert-v01@openssh.com"
    const val ED25519_KEY = "ssh-ed25519"
    const val USER_CERTIFICATE = "user"

    fun authenticate(
        username: String,
        certificate: SshUserCertificate,
        transportIdentity: TransportAuthenticatedIdentity,
        nowEpochSeconds: Long,
        trustStore: ManagementTrustStore,
    ): AuthenticationDecision {
        if (username != ManagementLimits.SSH_USERNAME) return deny(AuthenticationDenial.WRONG_USERNAME)
        if (!transportIdentity.cryptographicallyAuthenticated) {
            return deny(AuthenticationDenial.TRANSPORT_IDENTITY_UNAUTHENTICATED)
        }
        if (certificate.certificateType != USER_CERTIFICATE ||
            certificate.subjectKeyType != ED25519_CERT ||
            certificate.signatureKeyType != ED25519_KEY
        ) return deny(AuthenticationDenial.UNSUPPORTED_CERTIFICATE)
        if (!trustStore.isTrustedCa(certificate.caFingerprintSha256)) {
            return deny(AuthenticationDenial.UNTRUSTED_CA)
        }
        if (trustStore.isRevoked(certificate.certificateFingerprintSha256, certificate.serial)) {
            return deny(AuthenticationDenial.REVOKED)
        }
        if (certificate.validAfterEpochSeconds < 0 ||
            certificate.validBeforeEpochSeconds <= certificate.validAfterEpochSeconds ||
            certificate.validBeforeEpochSeconds - certificate.validAfterEpochSeconds >
                ManagementLimits.MAX_CERT_VALIDITY_SECONDS ||
            nowEpochSeconds < certificate.validAfterEpochSeconds ||
            nowEpochSeconds >= certificate.validBeforeEpochSeconds
        ) return deny(AuthenticationDenial.INVALID_VALIDITY)
        val role = certificate.principals.singleOrNull()?.let(ManagementRole::fromPrincipal)
            ?: return deny(AuthenticationDenial.INVALID_PRINCIPALS)
        if (certificate.criticalOptions.isNotEmpty() || certificate.extensions.isNotEmpty()) {
            return deny(AuthenticationDenial.OPTIONS_OR_EXTENSIONS_PRESENT)
        }
        val binding = trustStore.transportBinding(certificate.certificateFingerprintSha256)
        if (binding == null || binding.providerId != transportIdentity.providerId ||
            binding.nodeId != transportIdentity.nodeId
        ) return deny(AuthenticationDenial.TRANSPORT_IDENTITY_MISMATCH)
        return AuthenticationDecision.Allow(
            role,
            certificate.certificateFingerprintSha256,
            transportIdentity,
        )
    }

    private fun deny(reason: AuthenticationDenial) = AuthenticationDecision.Deny(reason)
}

sealed interface SshChannelRequest {
    data class Exec(val command: String) : SshChannelRequest
    data object Shell : SshChannelRequest
    data object Pty : SshChannelRequest
    data object Environment : SshChannelRequest
    data object Subsystem : SshChannelRequest
    data object X11Forwarding : SshChannelRequest
    data object AgentForwarding : SshChannelRequest
    data object GlobalTcpipForward : SshChannelRequest
    data object CancelGlobalTcpipForward : SshChannelRequest
    data class DirectTcpip(val requestedHost: String, val requestedPort: Int) : SshChannelRequest
    data class Unknown(val requestName: String) : SshChannelRequest
}

data class ResolvedGuestForward(val host: String, val port: Int)

sealed interface ChannelDecision {
    data object AllowManagementExec : ChannelDecision
    data class AllowGuestForward(val target: ResolvedGuestForward) : ChannelDecision
    data class Deny(val reason: ChannelDenial) : ChannelDecision
}

enum class ChannelDenial {
    ROLE_FORBIDDEN,
    COMMAND_NOT_EXACT,
    CHANNEL_TYPE_FORBIDDEN,
    FORWARD_TARGET_FORBIDDEN,
}

object ManagementChannelPolicy {
    fun authorize(role: ManagementRole, request: SshChannelRequest): ChannelDecision = when (request) {
        is SshChannelRequest.Exec -> when {
            role == ManagementRole.VM_DEFAULT_SSH -> ChannelDecision.Deny(ChannelDenial.ROLE_FORBIDDEN)
            request.command != ManagementLimits.EXEC_COMMAND -> ChannelDecision.Deny(ChannelDenial.COMMAND_NOT_EXACT)
            else -> ChannelDecision.AllowManagementExec
        }
        is SshChannelRequest.DirectTcpip -> when {
            role != ManagementRole.VM_DEFAULT_SSH -> ChannelDecision.Deny(ChannelDenial.ROLE_FORBIDDEN)
            request.requestedHost != ManagementLimits.GUEST_VIRTUAL_HOST ||
                request.requestedPort != ManagementLimits.GUEST_VIRTUAL_PORT ->
                ChannelDecision.Deny(ChannelDenial.FORWARD_TARGET_FORBIDDEN)
            else -> ChannelDecision.AllowGuestForward(
                ResolvedGuestForward(
                    ManagementLimits.GUEST_LOOPBACK_HOST,
                    ManagementLimits.GUEST_LOOPBACK_PORT,
                ),
            )
        }
        else -> ChannelDecision.Deny(ChannelDenial.CHANNEL_TYPE_FORBIDDEN)
    }
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
