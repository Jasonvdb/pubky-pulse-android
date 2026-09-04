package org.pubky.pulse.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Validates config parsing + the Swift-mirrored error cases. */
class PulseConfigurationTest {

    @Test
    fun acceptsValidHttpsEndpointAndClientKey() {
        val config = PulseConfiguration.create(
            endpoint = "https://ingest.owlmetry.com",
            apiKey = "owl_client_abc123",
            bundleId = "com.example.app",
        )
        assertEquals("com.example.app", config.bundleId)
        assertEquals("owl_client_abc123", config.apiKey)
        assertEquals("ingest.owlmetry.com", config.endpoint.host)
    }

    @Test
    fun rejectsNonClientApiKey() {
        val e = assertThrows(PulseConfigurationError.InvalidApiKey::class.java) {
            PulseConfiguration.create(
                endpoint = "https://ingest.owlmetry.com",
                apiKey = "owl_agent_abc",
                bundleId = "com.example.app",
            )
        }
        assertEquals("API key must start with \"owl_client_\"", e.message)
    }

    @Test
    fun rejectsMalformedEndpoint() {
        assertThrows(PulseConfigurationError.InvalidEndpoint::class.java) {
            PulseConfiguration.create(
                endpoint = "not a url",
                apiKey = "owl_client_abc",
                bundleId = "com.example.app",
            )
        }
    }

    @Test
    fun rejectsNonHttpScheme() {
        assertThrows(PulseConfigurationError.InvalidEndpoint::class.java) {
            PulseConfiguration.create(
                endpoint = "ftp://example.com",
                apiKey = "owl_client_abc",
                bundleId = "com.example.app",
            )
        }
    }

    @Test
    fun rejectsEmptyBundleId() {
        assertThrows(PulseConfigurationError.MissingBundleId::class.java) {
            PulseConfiguration.create(
                endpoint = "https://ingest.owlmetry.com",
                apiKey = "owl_client_abc",
                bundleId = "",
            )
        }
    }
}
