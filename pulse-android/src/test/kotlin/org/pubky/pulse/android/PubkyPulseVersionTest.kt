package org.pubky.pulse.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1 scaffold smoke test — proves the module compiles, resolves, and runs JVM unit tests. */
class PubkyPulseVersionTest {

    @Test
    fun sdkNameIsAndroid() {
        assertEquals("pubky-pulse-android", PubkyPulseVersion.NAME)
    }

    @Test
    fun versionIsSemver() {
        assertTrue(
            "version must be semver X.Y.Z, was ${PubkyPulseVersion.CURRENT}",
            Regex("""^\d+\.\d+\.\d+$""").matches(PubkyPulseVersion.CURRENT),
        )
    }
}
