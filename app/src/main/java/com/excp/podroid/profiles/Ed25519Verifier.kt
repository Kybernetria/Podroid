package com.excp.podroid.profiles

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Collections

class ProfileVerificationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Immutable Ed25519 key admitted only from the exact RFC 8410 SubjectPublicKeyInfo encoding. */
class Ed25519PublicKey private constructor(rawBytes: ByteArray) {
    private val raw = rawBytes.copyOf()

    internal fun rawCopy(): ByteArray = raw.copyOf()
    internal fun x509Copy(): ByteArray = X509_PREFIX + raw

    val fingerprint: Sha256Digest
        get() = Sha256Digest(MessageDigest.getInstance("SHA-256").digest(raw).toLowerHex())

    companion object {
        private val X509_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )

        fun fromX509(encoded: ByteArray): Ed25519PublicKey {
            require(encoded.size == ProfileLimits.ED25519_X509_PUBLIC_KEY_BYTES) {
                "Ed25519 X.509 public key must be exactly ${ProfileLimits.ED25519_X509_PUBLIC_KEY_BYTES} bytes"
            }
            require(encoded.copyOfRange(0, X509_PREFIX.size).contentEquals(X509_PREFIX)) {
                "Ed25519 X.509 public key has a non-canonical algorithm identifier"
            }
            return Ed25519PublicKey(encoded.copyOfRange(X509_PREFIX.size, encoded.size))
        }
    }
}

data class TrustedProfileSigningKey(val publicKey: Ed25519PublicKey)

/**
 * One closed APK-owned trust snapshot. Raising [trustEpoch] explicitly resets generation floors.
 * The constructor defensively copies the key map so one repository process cannot observe a
 * partial or later trust-policy update; an APK update/process restart must construct a new policy.
 */
class ProfileTrustPolicy(
    val trustEpoch: TrustEpoch,
    trustedSigningKeys: Map<SigningKeyId, TrustedProfileSigningKey>,
) {
    private val trustedSigningKeys = Collections.unmodifiableMap(LinkedHashMap(trustedSigningKeys))

    init {
        require(this.trustedSigningKeys.size <= MAX_TRUSTED_SIGNING_KEYS) {
            "trusted profile signing key count exceeds the supported bound"
        }
    }

    fun resolve(keyId: SigningKeyId): TrustedProfileSigningKey? = trustedSigningKeys[keyId]

    private companion object {
        const val MAX_TRUSTED_SIGNING_KEYS = 32
    }
}

fun interface Ed25519Verifier {
    /** Returns false only for a well-formed but non-matching signature. */
    fun verify(publicKey: Ed25519PublicKey, message: ByteArray, signature: ByteArray): Boolean
}

/** Android-compatible verifier bundled by the pinned Tink Android dependency. */
object TinkEd25519Verifier : Ed25519Verifier {
    override fun verify(
        publicKey: Ed25519PublicKey,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        require(signature.size == ProfileLimits.ED25519_SIGNATURE_BYTES) {
            "Ed25519 signature must be exactly ${ProfileLimits.ED25519_SIGNATURE_BYTES} bytes"
        }
        return try {
            Ed25519Verify(publicKey.rawCopy()).verify(signature, message)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }
}

/** JVM/JCA verifier retained as a test seam; Android production does not depend on JCA Ed25519. */
object JcaEd25519Verifier : Ed25519Verifier {
    override fun verify(
        publicKey: Ed25519PublicKey,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        require(signature.size == ProfileLimits.ED25519_SIGNATURE_BYTES) {
            "Ed25519 signature must be exactly ${ProfileLimits.ED25519_SIGNATURE_BYTES} bytes"
        }
        return try {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKey.x509Copy()))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (failure: GeneralSecurityException) {
            throw ProfileVerificationException("JCA Ed25519 verification is unavailable", failure)
        }
    }
}

private fun ByteArray.toLowerHex(): String = joinToString("") {
    (it.toInt() and 0xff).toString(16).padStart(2, '0')
}
