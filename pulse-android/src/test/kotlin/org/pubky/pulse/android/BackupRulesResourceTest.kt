package org.pubky.pulse.android

import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The two Auto Backup rule files the SDK ships are referenced only by *host*
 * apps, as `@xml/pubky_pulse_backup_rules` / `@xml/pubky_pulse_data_extraction_rules`
 * in their `<application>` tag. Nothing inside the SDK reads them, so renaming
 * or dropping a file compiles cleanly here and silently breaks every consumer's
 * manifest. Referencing the generated `R` ids is that compile-time pin.
 */
class BackupRulesResourceTest {

    @Test
    fun shipsAutoBackupRuleResourcesUnderTheirDocumentedNames() {
        assertNotEquals(0, R.xml.pubky_pulse_backup_rules)
        assertNotEquals(0, R.xml.pubky_pulse_data_extraction_rules)
    }
}
