package org.pubky.pulse.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Validates config parsing + the Swift-mirrored error cases. */
class PulseConfigurationTest {

    @Test
    fun acceptsValidHttpsEndpointAndClientKey() {
        val config = PulseConfiguration.create(
            endpoint = "https://ingest.pulse.pubky.org",
            apiKey = "pulse_client_abc123",
            bundleId = "com.example.app",
        )
        assertEquals("com.example.app", config.bundleId)
        assertEquals("pulse_client_abc123", config.apiKey)
        assertEquals("ingest.pulse.pubky.org", config.endpoint.host)
    }

    @Test
    fun rejectsNonClientApiKey() {
        val e = assertThrows(PulseConfigurationError.InvalidApiKey::class.java) {
            PulseConfiguration.create(
                endpoint = "https://ingest.pulse.pubky.org",
                apiKey = "pulse_agent_abc",
                bundleId = "com.example.app",
            )
        }
        assertEquals("API key must start with \"pulse_client_\"", e.message)
    }

    @Test
    fun rejectsMalformedEndpoint() {
        assertThrows(PulseConfigurationError.InvalidEndpoint::class.java) {
            PulseConfiguration.create(
                endpoint = "not a url",
                apiKey = "pulse_client_abc",
                bundleId = "com.example.app",
            )
        }
    }

    @Test
    fun rejectsNonHttpScheme() {
        assertThrows(PulseConfigurationError.InvalidEndpoint::class.java) {
            PulseConfiguration.create(
                endpoint = "ftp://example.com",
                apiKey = "pulse_client_abc",
                bundleId = "com.example.app",
            )
        }
    }

    @Test
    fun rejectsEmptyBundleId() {
        assertThrows(PulseConfigurationError.MissingBundleId::class.java) {
            PulseConfiguration.create(
                endpoint = "https://ingest.pulse.pubky.org",
                apiKey = "pulse_client_abc",
                bundleId = "",
            )
        }
    }

    /**
     * Exact pin on the accepted API-key prefix. The full prefix is accepted and
     * every proper truncation of it is rejected, so the prefix cannot be
     * shortened, lengthened, or re-spelled without this failing — the server
     * mints keys under this exact prefix.
     */
    @Test
    fun clientKeyPrefixIsExactlyPulseClient() {
        val prefix = "pulse_client_"
        val config = PulseConfiguration.create(
            endpoint = "https://ingest.pulse.pubky.org",
            apiKey = prefix + "abc",
            bundleId = "com.example.app",
        )
        assertEquals(prefix + "abc", config.apiKey)

        for (length in prefix.indices) {
            assertThrows(PulseConfigurationError.InvalidApiKey::class.java) {
                PulseConfiguration.create(
                    endpoint = "https://ingest.pulse.pubky.org",
                    apiKey = prefix.substring(0, length) + "abc",
                    bundleId = "com.example.app",
                )
            }
        }
    }
}
