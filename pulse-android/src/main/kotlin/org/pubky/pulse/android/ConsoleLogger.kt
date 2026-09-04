package org.pubky.pulse.android

import android.util.Log

/**
 * Mirrors the Swift `Pulse.printToConsole` developer-console echo. Swift prints to
 * stdout via `print`; the Android analog is Logcat ([android.util.Log]) under
 * the [TAG] tag, with the level mapped to the matching Logcat priority. The
 * human-readable line is byte-identical to the other Pubky Pulse SDKs': a
 * `[pulse] ` prefix, a fixed-width level tag, the (possibly rewritten) message,
 * and a sorted `{k=v, …}` attribute suffix.
 *
 * Suppression + rewriting rules match Swift exactly:
 *  - `sdk:` internal events are never echoed.
 *  - `metric:<name>:start` is never echoed (the visible signal is the
 *    complete/fail/cancel terminal event, added in the metrics phase).
 *  - `step:` / legacy `track:` render as `step: <name>`.
 *  - `metric:<name>:<phase>` renders as `metric: <name> <phase>`.
 *
 * The level→tag mapping (`INFO `, `DEBUG`, `WARN `, `ERROR`) is padded to 5
 * chars so columns align, exactly as Swift's `switch`.
 *
 * Extracted into its own object (rather than a private `Pulse` method like Swift)
 * so it can be unit-tested for the message-rewriting logic without touching the
 * Logcat sink — [format] is pure, [print] is the side-effecting wrapper.
 */
internal object ConsoleLogger {
    const val TAG: String = "PubkyPulse"

    private const val STEP_PREFIX = "step:"
    private const val LEGACY_TRACK_PREFIX = "track:" // Legacy prefix from older SDK versions
    private const val METRIC_PREFIX = "metric:"
    private const val SDK_PREFIX = "sdk:"

    /**
     * Build the console line for [message]/[level]/[attributes], or `null` when
     * the event is suppressed (`sdk:` events and `metric:…:start`). Pure — no
     * I/O — so it's directly testable.
     */
    fun format(
        message: String,
        level: PulseLogLevel,
        attributes: Map<String, String>?,
    ): String? {
        if (message.startsWith(SDK_PREFIX)) return null
        if (message.startsWith(METRIC_PREFIX) && message.endsWith(":start")) return null

        val tag = when (level) {
            PulseLogLevel.INFO -> "INFO "
            PulseLogLevel.DEBUG -> "DEBUG"
            PulseLogLevel.WARN -> "WARN "
            PulseLogLevel.ERROR -> "ERROR"
        }

        val displayMessage = when {
            message.startsWith(STEP_PREFIX) ->
                "step: ${message.substring(STEP_PREFIX.length)}"
            message.startsWith(LEGACY_TRACK_PREFIX) ->
                "step: ${message.substring(LEGACY_TRACK_PREFIX.length)}"
            message.startsWith(METRIC_PREFIX) -> {
                val body = message.substring(METRIC_PREFIX.length)
                val colonIndex = body.indexOf(':')
                if (colonIndex >= 0) {
                    val metricName = body.substring(0, colonIndex)
                    val phase = body.substring(colonIndex + 1)
                    "metric: $metricName $phase"
                } else {
                    "metric: $body"
                }
            }
            else -> message
        }

        var line = "[pulse] $tag $displayMessage"
        if (!attributes.isNullOrEmpty()) {
            val pairs = attributes.entries
                .sortedBy { it.key }
                .joinToString(", ") { "${it.key}=${it.value}" }
            line += " {$pairs}"
        }
        return line
    }

    /**
     * Echo the event to Logcat at the level's matching priority, unless the
     * message is one of the suppressed internal events (see [format]).
     */
    fun print(
        message: String,
        level: PulseLogLevel,
        attributes: Map<String, String>?,
    ) {
        val line = format(message, level, attributes) ?: return
        when (level) {
            PulseLogLevel.INFO -> Log.i(TAG, line)
            PulseLogLevel.DEBUG -> Log.d(TAG, line)
            PulseLogLevel.WARN -> Log.w(TAG, line)
            PulseLogLevel.ERROR -> Log.e(TAG, line)
        }
    }
}
