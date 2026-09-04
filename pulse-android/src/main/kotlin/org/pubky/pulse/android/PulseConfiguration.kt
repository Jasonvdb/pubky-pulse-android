package org.pubky.pulse.android

import android.content.Context
import java.net.URI
import java.net.URISyntaxException

/**
 * Immutable SDK configuration. Mirrors the Swift `PulseConfiguration`:
 * validates the endpoint URL, the `pulse_client_` API-key prefix, and a non-empty
 * bundle ID, throwing [PulseConfigurationError] on any failure.
 *
 * Swift resolves `bundleId` from `Bundle.main.bundleIdentifier`; on Android the
 * analog is `context.packageName` (e.g. `com.example.app`). The validated values
 * are kept on the instance so the transport layer can read them later.
 */
public class PulseConfiguration private constructor(
    public val endpoint: URI,
    public val apiKey: String,
    public val bundleId: String,
    public val flushOnBackground: Boolean,
    public val compressionEnabled: Boolean,
    public val networkTrackingEnabled: Boolean,
    public val consoleLogging: Boolean,
    public val attributionEnabled: Boolean,
) {
    public companion object {
        private const val CLIENT_KEY_PREFIX = "pulse_client_"

        /**
         * Primary factory. Resolves the bundle ID from [context], validates the
         * endpoint and API key, and returns a configuration or throws an
         * [PulseConfigurationError]. Mirrors Swift's throwing `init`.
         */
        @Throws(PulseConfigurationError::class)
        public fun create(
            context: Context,
            endpoint: String,
            apiKey: String,
            flushOnBackground: Boolean = true,
            compressionEnabled: Boolean = true,
            networkTrackingEnabled: Boolean = true,
            consoleLogging: Boolean = true,
            attributionEnabled: Boolean = true,
        ): PulseConfiguration {
            val bundleId = context.packageName
            if (bundleId.isNullOrEmpty()) {
                throw PulseConfigurationError.MissingBundleId
            }
            return create(
                endpoint = endpoint,
                apiKey = apiKey,
                bundleId = bundleId,
                flushOnBackground = flushOnBackground,
                compressionEnabled = compressionEnabled,
                networkTrackingEnabled = networkTrackingEnabled,
                consoleLogging = consoleLogging,
                attributionEnabled = attributionEnabled,
            )
        }

        /**
         * Internal factory with an explicit bundle ID — used by tests (mirrors
         * the Swift `init(... bundleId: ...)` overload) and by [create] above.
         */
        @Throws(PulseConfigurationError::class)
        internal fun create(
            endpoint: String,
            apiKey: String,
            bundleId: String,
            flushOnBackground: Boolean = true,
            compressionEnabled: Boolean = true,
            networkTrackingEnabled: Boolean = true,
            consoleLogging: Boolean = true,
            attributionEnabled: Boolean = true,
        ): PulseConfiguration {
            val uri = parseEndpoint(endpoint)
            if (!apiKey.startsWith(CLIENT_KEY_PREFIX)) {
                throw PulseConfigurationError.InvalidApiKey(
                    "API key must start with \"$CLIENT_KEY_PREFIX\"",
                )
            }
            if (bundleId.isEmpty()) {
                throw PulseConfigurationError.MissingBundleId
            }
            return PulseConfiguration(
                endpoint = uri,
                apiKey = apiKey,
                bundleId = bundleId,
                flushOnBackground = flushOnBackground,
                compressionEnabled = compressionEnabled,
                networkTrackingEnabled = networkTrackingEnabled,
                consoleLogging = consoleLogging,
                attributionEnabled = attributionEnabled,
            )
        }

        /**
         * Parse + validate the endpoint. `URI` is used (not `android.net.Uri`)
         * so the check works in plain JVM unit tests without a Context, and it
         * still rejects malformed input via [URISyntaxException]. We require an
         * absolute http(s) URL with a host, matching Swift's `URL(string:)`
         * being usable as an ingest base.
         */
        private fun parseEndpoint(endpoint: String): URI {
            val uri = try {
                URI(endpoint)
            } catch (e: URISyntaxException) {
                throw PulseConfigurationError.InvalidEndpoint(endpoint)
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                throw PulseConfigurationError.InvalidEndpoint(endpoint)
            }
            if (uri.host.isNullOrEmpty()) {
                throw PulseConfigurationError.InvalidEndpoint(endpoint)
            }
            return uri
        }
    }
}

/**
 * Configuration validation failures. Sealed exception hierarchy mirroring the
 * Swift `PulseConfigurationError` cases (`invalidEndpoint` / `invalidApiKey` /
 * `missingBundleId`); each carries the same human-readable description.
 */
public sealed class PulseConfigurationError(message: String) : Exception(message) {
    public class InvalidEndpoint(public val value: String) :
        PulseConfigurationError("Invalid endpoint URL: $value")

    public class InvalidApiKey(message: String) :
        PulseConfigurationError(message)

    public object MissingBundleId :
        PulseConfigurationError(
            "Bundle ID could not be determined. Ensure the app has a valid bundle identifier.",
        )
}
