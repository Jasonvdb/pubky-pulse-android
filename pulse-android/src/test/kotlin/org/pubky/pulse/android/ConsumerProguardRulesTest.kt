package org.pubky.pulse.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consumer ProGuard rules are shipped to every app that depends on the SDK,
 * and R8 matches them as plain strings — a rule naming a package the classes no
 * longer live in silently keeps nothing, so a minified consumer loses the public
 * API with no build error anywhere. Nothing else pins the file to the real
 * package, so a package move leaves it stale and silent. This test is that pin.
 */
class ConsumerProguardRulesTest {

    private val rules: List<String> by lazy {
        // Gradle runs unit tests with the module directory as the working
        // directory; tolerate the repo root too so the test is not coupled to it.
        val file = listOf("consumer-rules.pro", "pulse-android/consumer-rules.pro")
            .map(::File)
            .firstOrNull(File::isFile)
        assertTrue("consumer-rules.pro not found from ${File("").absolutePath}", file != null)
        file!!.readLines().map(String::trim).filter { it.startsWith("-keep") }
    }

    @Test
    fun keepRulesNameTheRuntimeSdkPackage() {
        val sdkPackage = Owl::class.java.`package`!!.name
        assertEquals("org.pubky.pulse.android", sdkPackage)
        assertTrue("expected -keep rules in consumer-rules.pro", rules.isNotEmpty())
        for (rule in rules) {
            val className = rule.substringAfter("class ").trim().substringBefore(' ')
            assertTrue(
                "consumer rule does not target the SDK package ($sdkPackage): $rule",
                className.startsWith("$sdkPackage."),
            )
        }
    }

    @Test
    fun keepRulesCoverTheEntrypointsAndTheWholePackage() {
        val targets = rules.map { it.substringAfter("class ").trim().substringBefore(' ') }
        assertTrue("no wildcard rule covering the whole SDK package", targets.contains("org.pubky.pulse.android.**"))
        assertTrue("Owl entrypoint is not kept", targets.contains(Owl::class.java.name))
        assertTrue("OwlConfiguration is not kept", targets.contains(OwlConfiguration::class.java.name))
    }
}
