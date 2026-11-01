package sh.pcx.packmerger.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Behind a proxy the player's address is the proxy's, so per-player pack URLs stay off. */
class ProxySetupTest {

    @TempDir Path root;

    @Test
    void plainServer_hasNoProxy() throws Exception {
        assertTrue(ProxySetup.detect(root.toFile()).isEmpty());
        Files.writeString(root.resolve("spigot.yml"), "settings:\n  bungeecord: false\n");
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/paper-global.yml"),
                "proxies:\n  proxy-protocol: false\n  velocity:\n    enabled: false\n");
        assertTrue(ProxySetup.detect(root.toFile()).isEmpty());
    }

    @Test
    void bungeeCord_isDetected() throws Exception {
        Files.writeString(root.resolve("spigot.yml"), "settings:\n  bungeecord: true\n");
        assertEquals(List.of("spigot.yml settings.bungeecord"), ProxySetup.detect(root.toFile()));
    }

    @Test
    void velocityAndProxyProtocol_areDetected() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/paper-global.yml"),
                "proxies:\n  proxy-protocol: true\n  velocity:\n    enabled: true\n    secret: x\n");
        assertEquals(List.of("paper-global.yml proxies.velocity", "paper-global.yml proxies.proxy-protocol"),
                ProxySetup.detect(root.toFile()));
    }

    @Test
    void brokenYaml_countsAsNoProxy() throws Exception {
        Files.writeString(root.resolve("spigot.yml"), "settings: [unclosed\n  bungeecord: true");
        assertTrue(ProxySetup.detect(root.toFile()).isEmpty());
    }
}
