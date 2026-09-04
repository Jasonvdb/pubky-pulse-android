package org.pubky.pulse.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Pulse.cleanAttributes] null-stripping, mirroring Swift's `cleanAttributes`
 * (`compactMapValues { $0 }`, returning nil when nothing remains). Pure function
 * — no configure, no org.json — so it runs as a plain JVM test. The end-to-end
 * "null attr dropped on the wire" path is covered separately in [PulseLoggingTest];
 * this pins the unit contract directly.
 */
class PulseCleanAttributesTest {

    @Test
    fun emptyMapReturnsNull() {
        assertNull(Pulse.cleanAttributes(emptyMap()))
    }

    @Test
    fun allNullValuesReturnNull() {
        assertNull(Pulse.cleanAttributes(mapOf("a" to null, "b" to null)))
    }

    @Test
    fun nullValuesAreStrippedKeepingTheRest() {
        val result = Pulse.cleanAttributes(mapOf("keep" to "yes", "drop" to null, "also" to "ok"))
        assertEquals(mapOf("keep" to "yes", "also" to "ok"), result)
    }

    @Test
    fun emptyStringValueIsKept() {
        // Only null is stripped; an empty-string value is a real value and stays
        // (Swift's compactMapValues drops only nil).
        val result = Pulse.cleanAttributes(mapOf("empty" to ""))
        assertEquals(mapOf("empty" to ""), result)
    }

    @Test
    fun allPresentValuesPassThrough() {
        val input = mapOf("x" to "1", "y" to "2")
        assertEquals(input, Pulse.cleanAttributes(input))
    }
}
