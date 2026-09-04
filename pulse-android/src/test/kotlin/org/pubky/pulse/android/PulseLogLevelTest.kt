package org.pubky.pulse.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [PulseLogLevel]'s raw `wire` strings, which serialize directly into the
 * event `level` field and must stay byte-identical to the Swift SDK's
 * `PulseLogLevel: String` raw values (`info`/`debug`/`warn`/`error`).
 */
class PulseLogLevelTest {

    @Test
    fun wireValuesAreLowercaseSwiftRawValues() {
        assertEquals("info", PulseLogLevel.INFO.wire)
        assertEquals("debug", PulseLogLevel.DEBUG.wire)
        assertEquals("warn", PulseLogLevel.WARN.wire)
        assertEquals("error", PulseLogLevel.ERROR.wire)
    }

    @Test
    fun exactlyTheSwiftCasesExistInDeclarationOrder() {
        // Guards against an accidental added/removed/reordered case relative to
        // Swift's enum (info, debug, warn, error).
        assertEquals(
            listOf("INFO", "DEBUG", "WARN", "ERROR"),
            PulseLogLevel.entries.map { it.name },
        )
        assertEquals(
            listOf("info", "debug", "warn", "error"),
            PulseLogLevel.entries.map { it.wire },
        )
    }
}
