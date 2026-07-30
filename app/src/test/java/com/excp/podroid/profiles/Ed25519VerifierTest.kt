package com.excp.podroid.profiles

import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ed25519VerifierTest {
    @Test
    fun `bundled Tink verifier accepts matching Ed25519 signature and rejects changed message`() {
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

        assertTrue(TinkEd25519Verifier.verify(publicKey, message, signature))
        assertFalse(TinkEd25519Verifier.verify(publicKey, "changed".toByteArray(), signature))
        assertTrue(JcaEd25519Verifier.verify(publicKey, message, signature))
    }

    @Test
    fun `X509 admission is exact RFC8410 Ed25519 and signatures are bounded`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val encoded = keyPair.public.encoded
        val publicKey = Ed25519PublicKey.fromX509(encoded)
        assertEquals(44, encoded.size)

        listOf(
            encoded.copyOf(encoded.size - 1),
            encoded + byteArrayOf(0),
            encoded.copyOf().also { it[4] = 0x04 },
            ByteArray(ProfileLimits.ED25519_X509_PUBLIC_KEY_BYTES),
        ).forEach { malformed ->
            assertTrue(runCatching { Ed25519PublicKey.fromX509(malformed) }.isFailure)
        }
        assertTrue(runCatching {
            TinkEd25519Verifier.verify(publicKey, byteArrayOf(1), ByteArray(63))
        }.isFailure)
    }
}
