package org.pubky.pulse.android

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Context-driven configuration tests. Where [PulseConfigurationTest] exercises the
 * internal explicit-bundleId factory in a plain JVM, these run under Robolectric
 * and go through the *public* `create(context, ...)` overload — the one apps
 * actually call — to prove the bundle ID is resolved from `context.packageName`
 * (the Android analog of Swift's `Bundle.main.bundleIdentifier`) and that the
 * Swift-mirrored validation still fires on that path.
 */
@RunWith(RobolectricTestRunner::class)
class PulseConfigurationContextTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun acceptsUnavailablePackageMetadataThroughPublicFactory() {
        for (packageName in listOf(null, "")) {
            val missingMetadata = object : ContextWrapper(context) {
                override fun getPackageName(): String? = packageName
            }
            val config = PulseConfiguration.create(
                context = missingMetadata,
                apiKey = "pulse_client_abc123",
            )
            assertEquals("", config.bundleId)
            assertEquals("ingest.pubkypulse.com", config.endpoint.host)
            assertThrows(PulseConfigurationError.InvalidApiKey::class.java) {
                PulseConfiguration.create(context = missingMetadata, apiKey = "pulse_agent_abc")
            }
            assertThrows(PulseConfigurationError.InvalidEndpoint::class.java) {
                PulseConfiguration.create(context = missingMetadata, endpoint = "", apiKey = "pulse_client_abc")
            }
        }
    }

    @Test
    fun resolvesBundleIdFromContextPackageName() {
        val config = PulseConfiguration.create(
            context = context,
            endpoint = "https://ingest.example.com",
            apiKey = "pulse_client_abc123",
        )
        // The bundle ID is whatever the host app's Context reports as its
        // packageName (Android analog of Bundle.main.bundleIdentifier). Under
        // Robolectric this is the test application id (e.g.
        // "org.pubky.pulse.android.test"), so assert the pass-through rather than a
        // hardcoded literal.
        assertEquals(context.packageName, config.bundleId)
        assertTrue(
            "bundleId should be a non-empty package name",
            config.bundleId.isNotEmpty(),
        )
        assertTrue(config.bundleId.startsWith("org.pubky.pulse.android"))
    }

    @Test
    fun acceptsValidConfigThroughContextFactory() {
        val config = PulseConfiguration.create(
            context = context,
            endpoint = "https://ingest.example.com/",
            apiKey = "pulse_client_live_xyz",
        )
        assertEquals("pulse_client_live_xyz", config.apiKey)
        assertEquals("ingest.example.com", config.endpoint.host)
        assertEquals("https", config.endpoint.scheme)
        // Defaults mirror the Swift initializer defaults (all true).
        assertTrue(config.flushOnBackground)
        assertTrue(config.compressionEnabled)
        assertTrue(config.networkTrackingEnabled)
        assertTrue(config.consoleLogging)
        assertTrue(config.attributionEnabled)
    }

    @Test
    fun honorsNonDefaultFlagsThroughContextFactory() {
        val config = PulseConfiguration.create(
            context = context,
            endpoint = "https://ingest.example.com",
            apiKey = "pulse_client_abc",
            flushOnBackground = false,
            compressionEnabled = false,
            networkTrackingEnabled = false,
            consoleLogging = false,
            attributionEnabled = false,
        )
        assertEquals(false, config.flushOnBackground)
        assertEquals(false, config.compressionEnabled)
        assertEquals(false, config.networkTrackingEnabled)
        assertEquals(false, config.consoleLogging)
        assertEquals(false, config.attributionEnabled)
    }

    @Test
    fun rejectsNonClientApiKeyThroughContextFactory() {
        val e = assertThrows(PulseConfigurationError.InvalidApiKey::class.java) {
            PulseConfiguration.create(
                context = context,
                endpoint = "https://ingest.example.com",
                apiKey = "pulse_agent_abc",
            )
        }
        assertEquals("API key must start with \"pulse_client_\"", e.message)
    }

    @Test
    fun rejectsApiKeyMissingPrefixEntirely() {
        assertThrows(PulseConfigurationError.InvalidApiKey::class.java) {
            PulseConfiguration.create(
                context = context,
                endpoint = "https://ingest.example.com",
                apiKey = "totally_wrong",
            )
        }
    }

    @Test
    fun rejectsMalformedEndpointThroughContextFactory() {
        assertThrows(PulseConfigurationError.InvalidEndpoint::class.java) {
            PulseConfiguration.create(
                context = context,
                endpoint = "not a url",
                apiKey = "pulse_client_abc",
            )
        }
    }

    @Test
    fun rejectsRelativeOrSchemelessEndpoint() {
        assertThrows(PulseConfigurationError.InvalidEndpoint::class.java) {
            PulseConfiguration.create(
                context = context,
                endpoint = "ingest.example.com/v1/ingest",
                apiKey = "pulse_client_abc",
            )
        }
    }

    @Test
    fun rejectsNonHttpSchemeThroughContextFactory() {
        assertThrows(PulseConfigurationError.InvalidEndpoint::class.java) {
            PulseConfiguration.create(
                context = context,
                endpoint = "ftp://example.com",
                apiKey = "pulse_client_abc",
            )
        }
    }

    @Test
    fun invalidEndpointErrorCarriesOriginalValue() {
        val e = assertThrows(PulseConfigurationError.InvalidEndpoint::class.java) {
            PulseConfiguration.create(
                context = context,
                endpoint = "ftp://example.com",
                apiKey = "pulse_client_abc",
            )
        }
        assertEquals("ftp://example.com", e.value)
        assertEquals("Invalid endpoint URL: ftp://example.com", e.message)
    }

    /**
     * Omitting `endpoint` on the public factory falls back to Pubky's hosted
     * ingest host. The fallback is silent, so this pins where an omitted
     * endpoint actually sends data.
     */
    @Test
    fun defaultsOmittedEndpointToPubkyHostedIngestThroughContextFactory() {
        val config = PulseConfiguration.create(
            context = context,
            apiKey = "pulse_client_abc123",
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
    fun rejectsExplicitlyEmptyEndpointThroughContextFactory() {
        assertThrows(PulseConfigurationError.InvalidEndpoint::class.java) {
            PulseConfiguration.create(
                context = context,
                endpoint = "",
                apiKey = "pulse_client_abc",
            )
        }
    }

    /** A supplied endpoint is used verbatim; the default never overrides it. */
    @Test
    fun explicitEndpointWinsOverTheDefaultThroughContextFactory() {
        val config = PulseConfiguration.create(
            context = context,
            endpoint = "https://ingest.example.com",
            apiKey = "pulse_client_abc",
        )
        assertEquals("https://ingest.example.com", config.endpoint.toString())
    }
}
