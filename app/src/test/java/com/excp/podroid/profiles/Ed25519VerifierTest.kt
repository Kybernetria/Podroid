package com.excp.podroid.profiles

import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ed25519VerifierTest {
    @Test
    fun `JCA verifier accepts matching Ed25519 signature and rejects changed message`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val message = "bounded message".toByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(message)
            sign()
        }
        val mutableEncoding = keyPair.public.encoded
        val publicKey = Ed25519PublicKey.fromX509(mutableEncoding)
        mutableEncoding.fill(0)

        assertTrue(JcaEd25519Verifier.verify(publicKey, message, signature))
        assertFalse(JcaEd25519Verifier.verify(publicKey, "changed".toByteArray(), signature))
    }

    @Test
    fun `JCA verifier rejects malformed key and signature bounds explicitly`() {
        val malformedKey = Ed25519PublicKey.fromX509(byteArrayOf(1, 2, 3))
        assertTrue(
            runCatching {
                JcaEd25519Verifier.verify(
                    malformedKey,
                    byteArrayOf(1),
                    ByteArray(ProfileLimits.ED25519_SIGNATURE_BYTES),
                )
            }.exceptionOrNull() is ProfileVerificationException,
        )

        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKey = Ed25519PublicKey.fromX509(keyPair.public.encoded)
        assertTrue(
            runCatching {
                JcaEd25519Verifier.verify(publicKey, byteArrayOf(1), ByteArray(63))
            }.isFailure,
        )
        assertTrue(
            runCatching { Ed25519PublicKey.fromX509(ByteArray(ProfileLimits.MAX_ED25519_PUBLIC_KEY_BYTES + 1)) }
                .isFailure,
        )
    }
}
