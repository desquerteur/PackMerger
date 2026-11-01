package sh.pcx.packmerger.upload;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects that players do not connect to this server directly. Behind Velocity or
 * BungeeCord the address a player typed is the proxy's, and with the PROXY protocol
 * (TCPShield, HAProxy, ...) it is the protection service's; neither forwards the
 * self-host port, so per-player pack URLs are not built in these setups.
 *
 * <p>Reads {@code spigot.yml} ({@code settings.bungeecord}) and
 * {@code config/paper-global.yml} ({@code proxies.velocity.enabled},
 * {@code proxies.proxy-protocol}) from the server root. Unreadable files count as "no
 * proxy".</p>
 */
public final class ProxySetup {

    private ProxySetup() {}

    /** @return the detected proxy settings (e.g. {@code "spigot.yml settings.bungeecord"}), empty if none */
    public static List<String> detect(File serverRoot) {
        List<String> found = new ArrayList<>();
        YamlConfiguration spigot = load(new File(serverRoot, "spigot.yml"));
        if (spigot != null && spigot.getBoolean("settings.bungeecord", false)) {
            found.add("spigot.yml settings.bungeecord");
        }
        YamlConfiguration paper = load(new File(new File(serverRoot, "config"), "paper-global.yml"));
        if (paper != null) {
            if (paper.getBoolean("proxies.velocity.enabled", false)) found.add("paper-global.yml proxies.velocity");
            if (paper.getBoolean("proxies.proxy-protocol", false)) found.add("paper-global.yml proxies.proxy-protocol");
        }
        return List.copyOf(found);
    }

    private static YamlConfiguration load(File file) {
        if (!file.isFile()) return null;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file);
            return yaml;
        } catch (Exception e) {
            return null;
        }
    }
}
