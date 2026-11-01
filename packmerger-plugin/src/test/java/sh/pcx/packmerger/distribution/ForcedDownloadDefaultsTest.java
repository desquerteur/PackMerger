package sh.pcx.packmerger.distribution;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.distribution.DeliveryTracker.Action;
import sh.pcx.packmerger.distribution.DeliveryTracker.Status;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zero configuration: a fresh install with the shipped config.yml forces every player
 * to download the pack. Declining kicks, a download that still fails after the
 * resends kicks; INVALID_URL and FAILED_RELOAD never do.
 */
class ForcedDownloadDefaultsTest {

    private static YamlConfiguration shippedConfig() throws Exception {
        try (InputStream in = ForcedDownloadDefaultsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void shippedConfig_forcesThePack() throws Exception {
        YamlConfiguration c = shippedConfig();
        assertEquals(8, c.getInt("config-version"));
        assertTrue(c.getBoolean("distribution.enabled"));
        assertTrue(c.getBoolean("distribution.required"));
        assertTrue(c.getBoolean("distribution.enforce-required.enabled"));
        assertTrue(c.getInt("distribution.retry.max-attempts", 3) > 0, "failed downloads are retried before a kick");
    }

    @Test
    void shippedConfig_zeroConfigDatapacksAndModrinth() throws Exception {
        YamlConfiguration c = shippedConfig();
        assertTrue(c.getBoolean("datapacks.enabled"));
        assertTrue(c.getBoolean("datapacks.external.modrinth.enabled"));
        assertTrue(c.getBoolean("datapacks.external.modrinth.auto-approve"));
        assertTrue(c.getStringList("datapacks.external.modrinth.trusted-projects").isEmpty());
        assertEquals(java.util.List.of("cdn.modrinth.com"), c.getStringList("datapacks.external.modrinth.allowed-hosts"));
        assertTrue(c.getString("datapacks.external.modrinth.api-url").startsWith("https://"));
        assertEquals("https://api.github.com/repos/desquerteur/PackMerger/releases/latest", c.getString("update-check.url"));
    }

    @Test
    void declined_kicksWithDefaults() {
        DeliveryTracker t = new DeliveryTracker(3);
        Action a = t.onStatus(UUID.randomUUID(), Status.DECLINED).action();
        assertEquals(Action.DECLINED, a);
        assertTrue(DeliveryTracker.kicks(a, true, true));
    }

    @Test
    void failedDownload_kicksOnlyAfterTheResends() {
        DeliveryTracker t = new DeliveryTracker(3);
        UUID p = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            Action a = t.onStatus(p, Status.FAILED_DOWNLOAD).action();
            assertEquals(Action.RESEND, a);
            assertFalse(DeliveryTracker.kicks(a, true, true), "no kick while resends are left");
            t.resendFinished(p);
        }
        Action gaveUp = t.onStatus(p, Status.FAILED_DOWNLOAD).action();
        assertEquals(Action.GAVE_UP, gaveUp);
        assertTrue(DeliveryTracker.kicks(gaveUp, true, true));
    }

    @Test
    void invalidUrlAndFailedReload_neverKick() {
        DeliveryTracker t = new DeliveryTracker(3);
        Action invalid = t.onStatus(UUID.randomUUID(), Status.INVALID_URL).action();
        Action reload = t.onStatus(UUID.randomUUID(), Status.FAILED_RELOAD).action();
        assertEquals(Action.INVALID_URL, invalid);
        assertEquals(Action.FAILED_RELOAD, reload);
        assertFalse(DeliveryTracker.kicks(invalid, true, true));
        assertFalse(DeliveryTracker.kicks(reload, true, true));
        assertFalse(DeliveryTracker.kicks(Action.LOADED, true, true));
    }

    @Test
    void noKick_whenRequiredOrEnforceIsOff() {
        assertFalse(DeliveryTracker.kicks(Action.DECLINED, false, true));
        assertFalse(DeliveryTracker.kicks(Action.DECLINED, true, false));
        assertFalse(DeliveryTracker.kicks(Action.GAVE_UP, false, false));
    }
}
