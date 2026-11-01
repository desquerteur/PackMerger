package sh.pcx.packmerger.config;

import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.update.UpdateChecker;

import static org.junit.jupiter.api.Assertions.*;

/** Zero-config defaults for new installs, and how existing configs are treated. */
class ZeroConfigMigrationTest {

    @Test
    void missingKeys_takeZeroConfigDefaults_onlyFromVersion8() {
        assertTrue(ConfigManager.usesZeroConfigDefaults(8));
        assertTrue(ConfigManager.usesZeroConfigDefaults(9));
        // Older configs keep the defaults they were written for (nobody gets kicked by surprise).
        assertFalse(ConfigManager.usesZeroConfigDefaults(7));
        assertFalse(ConfigManager.usesZeroConfigDefaults(0));
    }

    @Test
    void hint_whenThePackIsNotForced() {
        String both = ConfigManager.forcedDownloadHint(true, false, false);
        assertNotNull(both);
        assertTrue(both.contains("distribution.required and distribution.enforce-required.enabled are false"), both);
        assertTrue(both.contains("/pm reload"), both);
        String one = ConfigManager.forcedDownloadHint(true, true, false);
        assertTrue(one.contains("distribution.enforce-required.enabled is false"), one);
        assertTrue(ConfigManager.forcedDownloadHint(true, false, true).contains("distribution.required is false"));
    }

    @Test
    void noHint_whenForcedOrNotDistributed() {
        assertNull(ConfigManager.forcedDownloadHint(true, true, true));
        assertNull(ConfigManager.forcedDownloadHint(false, false, false));
    }

    @Test
    void upstreamUpdateUrl_isReplacedByTheFork_customUrlKept() {
        assertEquals(UpdateChecker.DEFAULT_URL, ConfigManager.migrateUpdateCheckUrl(ConfigManager.UPSTREAM_UPDATE_URL));
        assertEquals(UpdateChecker.DEFAULT_URL, ConfigManager.migrateUpdateCheckUrl(""));
        assertEquals(UpdateChecker.DEFAULT_URL, ConfigManager.migrateUpdateCheckUrl(null));
        assertEquals("https://example.com/v.json", ConfigManager.migrateUpdateCheckUrl(" https://example.com/v.json "));
        assertTrue(UpdateChecker.DEFAULT_URL.contains("desquerteur/PackMerger"));
    }
}
