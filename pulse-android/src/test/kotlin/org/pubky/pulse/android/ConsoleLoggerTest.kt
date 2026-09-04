package org.pubky.pulse.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConsoleLogger.format] message-rewriting + suppression rules, mirroring the
 * Swift `Pulse.printToConsole` logic. Pure — [format] does no I/O — so this runs
 * as a plain JVM test (no Robolectric; `android.util.Log` is never touched).
 */
class ConsoleLoggerTest {

    @Test
    fun sdkEventsAreSuppressed() {
        assertNull(ConsoleLogger.format("sdk:session_started", PulseLogLevel.INFO, null))
    }

    @Test
    fun metricStartIsSuppressed() {
        assertNull(ConsoleLogger.format("metric:photo-conversion:start", PulseLogLevel.INFO, null))
    }

    @Test
    fun plainInfoIsFormatted() {
        val line = ConsoleLogger.format("hello world", PulseLogLevel.INFO, null)
        assertEquals("[pulse] INFO  hello world", line)
    }

    @Test
    fun levelTagsArePaddedToFiveChars() {
        assertTrue(ConsoleLogger.format("m", PulseLogLevel.INFO, null)!!.contains("INFO "))
        assertTrue(ConsoleLogger.format("m", PulseLogLevel.DEBUG, null)!!.contains("DEBUG"))
        assertTrue(ConsoleLogger.format("m", PulseLogLevel.WARN, null)!!.contains("WARN "))
        assertTrue(ConsoleLogger.format("m", PulseLogLevel.ERROR, null)!!.contains("ERROR"))
    }

    @Test
    fun stepPrefixIsRewritten() {
        assertEquals("[pulse] INFO  step: checkout", ConsoleLogger.format("step:checkout", PulseLogLevel.INFO, null))
    }

    @Test
    fun legacyTrackPrefixIsRewrittenToStep() {
        assertEquals("[pulse] INFO  step: signup", ConsoleLogger.format("track:signup", PulseLogLevel.INFO, null))
    }

    @Test
    fun metricPhaseIsRewritten() {
        assertEquals(
            "[pulse] INFO  metric: api-request complete",
            ConsoleLogger.format("metric:api-request:complete", PulseLogLevel.INFO, null),
        )
    }

    @Test
    fun metricWithoutPhaseIsRewritten() {
        assertEquals(
            "[pulse] INFO  metric: onboarding",
            ConsoleLogger.format("metric:onboarding", PulseLogLevel.INFO, null),
        )
    }

    @Test
    fun attributesAreSortedAndAppended() {
        val line = ConsoleLogger.format("hi", PulseLogLevel.WARN, mapOf("z" to "1", "a" to "2"))
        assertEquals("[pulse] WARN  hi {a=2, z=1}", line)
    }

    @Test
    fun emptyAttributesAreOmitted() {
        assertEquals("[pulse] ERROR boom", ConsoleLogger.format("boom", PulseLogLevel.ERROR, emptyMap()))
    }

    /**
     * Exact pins on the SDK's console identity: the Logcat tag every echoed
     * line is filed under, and the prefix the line itself carries (shared with
     * the other Pubky Pulse SDKs). Both are only ever read by a human staring
     * at Logcat, so nothing else fails when one is renamed and the other is not.
     */
    @Test
    fun consoleIdentityIsPinned() {
        assertEquals("PubkyPulse", ConsoleLogger.TAG)
        assertTrue(ConsoleLogger.format("m", PulseLogLevel.INFO, null)!!.startsWith("[pulse] "))
    }
}
