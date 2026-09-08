package org.pubky.pulse.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Validates config parsing + the Swift-mirrored error cases. */
class PulseConfigurationTest {

    @Test
    fun acceptsValidHttpsEndpointAndClientKey() {
        val config = PulseConfiguration.create(
            endpoint = "https://ingest.example.com",
            apiKey = "pulse_client_abc123",
            bundleId = "com.example.app",
        )
        assertEquals("com.example.app", config.bundleId)
        assertEquals("pulse_client_abc123", config.apiKey)
        assertEquals("ingest.example.com", config.endpoint.host)
    }

    @Test
    fun rejectsNonClientApiKey() {
        val e = assertThrows(PulseConfigurationError.InvalidApiKey::class.java) {
            PulseConfiguration.create(
                endpoint = "https://ingest.example.com",
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
                endpoint = "https://ingest.example.com",
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
            endpoint = "https://ingest.example.com",
            apiKey = prefix + "abc",
            bundleId = "com.example.app",
        )
        assertEquals(prefix + "abc", config.apiKey)

        for (length in prefix.indices) {
            assertThrows(PulseConfigurationError.InvalidApiKey::class.java) {
                PulseConfiguration.create(
                    endpoint = "https://ingest.example.com",
                    apiKey = prefix.substring(0, length) + "abc",
                    bundleId = "com.example.app",
                )
            }
        }
    }

    /**
     * Omitting `endpoint` falls back to Pubky's hosted ingest host. The
     * fallback is silent, so this pins exactly where an omitted endpoint sends
     * data — a self-hoster must pass their own host to avoid it.
     */
    @Test
    fun defaultsOmittedEndpointToPubkyHostedIngest() {
        val config = PulseConfiguration.create(
            apiKey = "pulse_client_abc123",
            bundleId = "com.example.app",
        )
        assertEquals("ingest.pubkypulse.com", config.endpoint.host)
        assertEquals("https", config.endpoint.scheme)
    }

    /**
     * Only an *absent* endpoint falls back. An explicitly empty one is almost
     * always an environment variable that failed to load, and silently
     * redirecting it to Pubky's instance would send a self-hoster's data to the
     * wrong company — so it still throws.
     */
    @Test
    fun rejectsExplicitlyEmptyEndpoint() {
        assertThrows(PulseConfigurationError.InvalidEndpoint::class.java) {
            PulseConfiguration.create(
                endpoint = "",
                apiKey = "pulse_client_abc",
                bundleId = "com.example.app",
            )
        }
    }

    /** A supplied endpoint is used verbatim; the default never overrides it. */
    @Test
    fun explicitEndpointWinsOverTheDefault() {
        val config = PulseConfiguration.create(
            endpoint = "https://ingest.example.com",
            apiKey = "pulse_client_abc",
            bundleId = "com.example.app",
        )
        assertEquals("https://ingest.example.com", config.endpoint.toString())
    }
}
