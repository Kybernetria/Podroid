package com.excp.podroid.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadableProfileConfigurationTest {
    @Test fun `all absent BuildConfig values are explicitly unavailable`() {
        val result = DownloadableProfileConfigurationParser.parse(raw())

        assertEquals(
            DownloadableProfileConfigurationResult.Unavailable(
                DownloadableProfileUnavailableReason.NOT_CONFIGURED,
            ),
            result,
        )
    }

    @Test fun `one canonical trust root and canonical origins configure the feature`() {
        val result = DownloadableProfileConfigurationParser.parse(
            raw(
                keyId = "release-1",
                key = ZERO_ED25519_X509_BASE64,
                epoch = "1",
                origins = "https://profiles.example:443,https://cdn.example:443",
            ),
        )

        assertTrue(result is DownloadableProfileConfigurationResult.Configured)
        val configured = (result as DownloadableProfileConfigurationResult.Configured).value
        assertNotNull(configured.trustPolicy.resolve(SigningKeyId("release-1")))
        assertEquals(
            "https://profiles.example:443/release/envelope.json",
            configured.approvedOrigins.parseUrl(
                "https://profiles.example:443/release/envelope.json",
            ).value,
        )
    }

    @Test fun `partial malformed and noncanonical values fail closed without throwing`() {
        val invalid = listOf(
            raw(keyId = "release-1"),
            raw("release-1", "not-base64", "1", "https://profiles.example:443"),
            raw("release-1", ZERO_ED25519_X509_BASE64, "01", "https://profiles.example:443"),
            raw("release-1", ZERO_ED25519_X509_BASE64, "1", "https://profiles.example"),
            raw("release-1", ZERO_ED25519_X509_BASE64, "1", "https://profiles.example:443,"),
            raw("release-1", ZERO_ED25519_X509_BASE64, "1", "https://profiles.example:443,https://profiles.example:443"),
        )

        invalid.forEach { value ->
            assertEquals(
                DownloadableProfileConfigurationResult.Unavailable(
                    DownloadableProfileUnavailableReason.INVALID_CONFIGURATION,
                ),
                DownloadableProfileConfigurationParser.parse(value),
            )
        }
    }

    private fun raw(
        keyId: String = "",
        key: String = "",
        epoch: String = "",
        origins: String = "",
    ) = RawDownloadableProfileConfiguration(keyId, key, epoch, origins)

    private companion object {
        const val ZERO_ED25519_X509_BASE64 =
            "MCowBQYDK2VwAyEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
