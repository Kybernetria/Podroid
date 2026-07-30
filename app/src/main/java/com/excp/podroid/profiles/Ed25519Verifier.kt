package com.excp.podroid.profiles

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class ProfileVerificationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Immutable, bounded X.509 SubjectPublicKeyInfo encoding of an Ed25519 public key. */
class Ed25519PublicKey private constructor(encoded: ByteArray) {
    private val encoded = encoded.copyOf()

    internal fun encodedCopy(): ByteArray = encoded.copyOf()

    companion object {
        fun fromX509(encoded: ByteArray): Ed25519PublicKey {
            require(encoded.isNotEmpty() && encoded.size <= ProfileLimits.MAX_ED25519_PUBLIC_KEY_BYTES) {
                "Ed25519 public key encoding is outside the supported bound"
            }
            return Ed25519PublicKey(encoded)
        }
    }
}

fun interface Ed25519Verifier {
    /** Returns false only for a well-formed but non-matching signature. */
    fun verify(publicKey: Ed25519PublicKey, message: ByteArray, signature: ByteArray): Boolean
}

/** JCA implementation using an X.509-encoded Ed25519 public key. */
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
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKey.encodedCopy()))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (failure: GeneralSecurityException) {
            throw ProfileVerificationException("Ed25519 verification is unavailable or the public key is invalid", failure)
        }
    }
}
