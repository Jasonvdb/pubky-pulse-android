package org.pubky.pulse.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/**
 * The two Auto Backup rule files the SDK ships are referenced only by *host*
 * apps, as `@xml/pubky_pulse_backup_rules` / `@xml/pubky_pulse_data_extraction_rules`
 * in their `<application>` tag. Nothing inside the SDK reads them, so renaming
 * or dropping a file compiles cleanly here and silently breaks every consumer's
 * manifest. Referencing the generated `R` ids is that compile-time pin.
 *
 * Their contents are just as invisible: each `<include>` names the SDK's
 * SharedPreferences file by its on-disk filename, which is
 * [IdentityStore.PREFS_NAME] plus `.xml`. Nothing links the two at build time,
 * so the rules are parsed back out of the compiled resources here — a prefs
 * rename that misses these files (or vice versa) would otherwise ship a backup
 * config that silently backs up nothing.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRulesResourceTest {

    @Test
    fun shipsAutoBackupRuleResourcesUnderTheirDocumentedNames() {
        assertNotEquals(0, R.xml.pubky_pulse_backup_rules)
        assertNotEquals(0, R.xml.pubky_pulse_data_extraction_rules)
    }

    @Test
    fun backupRulesIncludeExactlyTheSdkPrefsFile() {
        assertEquals(listOf(PREFS_FILE), includedSharedPrefFiles(R.xml.pubky_pulse_backup_rules))
    }

    @Test
    fun dataExtractionRulesIncludeTheSdkPrefsFileInBothChannels() {
        // One <include> under <cloud-backup>, one under <device-transfer>.
        assertEquals(
            listOf(PREFS_FILE, PREFS_FILE),
            includedSharedPrefFiles(R.xml.pubky_pulse_data_extraction_rules),
        )
    }

    @Test
    fun theIncludedFileIsTheSdkPrefsFile() {
        assertEquals("${IdentityStore.PREFS_NAME}.xml", PREFS_FILE)
    }

    /**
     * Every `<include>` element's `path`, in document order, asserting each one
     * targets the `sharedpref` domain (the only domain the SDK ever backs up).
     */
    private fun includedSharedPrefFiles(resId: Int): List<String> {
        val paths = mutableListOf<String>()
        val parser = ApplicationProvider.getApplicationContext<Context>().resources.getXml(resId)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "include") {
                    assertEquals("sharedpref", parser.getAttributeValue(null, "domain"))
                    paths += parser.getAttributeValue(null, "path")
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return paths
    }

    private companion object {
        /** The on-disk SharedPreferences filename, pinned as a literal. */
        const val PREFS_FILE = "org.pubky.pulse.sdk.xml"
    }
}
