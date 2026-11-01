package sh.pcx.packmerger.upload;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Picks the pack URL one player is sent.
 *
 * <p><b>Per-player mode</b> (self-host with neither {@code public-url} nor
 * {@code public-host} set, not behind a proxy): the URL is built from the address the
 * player typed to join ({@code Player#getVirtualHost()}: a LAN IP, the public IP or a
 * domain) and the self-host port. That address demonstrably reaches this machine from
 * the player's network, so a LAN player gets the LAN IP and an internet player the public
 * address, without any configuration. The pack bytes, SHA-1 and pack UUID are the same for
 * everyone; only the host in the URL differs, and the client caches by SHA-1.</p>
 *
 * <p>Otherwise, or when the virtual host is missing or not a valid host, the published
 * (fixed) URL is used. A URL pointing at this machine itself (loopback, {@code localhost}
 * or the wildcard address) is never sent to a player who is not connecting from this
 * machine: their client could only fail, and with forced download that is a kick.</p>
 *
 * <p>The virtual host comes from the client's handshake, so it is validated strictly
 * (host name or IP literal only); a client sending garbage just gets the fixed URL.
 * Pure logic, no Bukkit types.</p>
 */
public final class PlayerPackUrl {

    private PlayerPackUrl() {}

    /** How the URL for a player was chosen. */
    public enum Mode {
        /** Built from the address the player connected with. */
        PER_PLAYER,
        /** The published URL, the same for everyone. */
        FIXED,
        /** The published URL only works on this machine; nothing should be sent. */
        UNUSABLE
    }

    /**
     * @param mode how the URL was chosen
     * @param url  the URL to send (for {@link Mode#UNUSABLE} the URL that was not sent)
     */
    public record Choice(Mode mode, String url) {}

    private static final Pattern HOST_NAME = Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-f:.]+");

    /**
     * @param fixedUrl         the published URL
     * @param perPlayerPort    the self-host port when per-player URLs are on, else {@code <= 0}
     * @param virtualHost      the host the player connected with (may be {@code null})
     * @param playerOnLoopback {@code true} if the player connects from this machine
     */
    public static Choice choose(String fixedUrl, int perPlayerPort, String virtualHost, boolean playerOnLoopback) {
        if (perPlayerPort > 0) {
            String host = sanitizeHost(virtualHost);
            if (host != null && (playerOnLoopback || !isLocalOnly(host))) {
                return new Choice(Mode.PER_PLAYER, SelfHostAddress.buildUrl(host, perPlayerPort));
            }
        }
        if (!playerOnLoopback && isLocalOnly(SelfHostAddress.hostOf(fixedUrl))) {
            return new Choice(Mode.UNUSABLE, fixedUrl);
        }
        return new Choice(Mode.FIXED, fixedUrl);
    }

    /**
     * Cleans a handshake host: cuts a Forge/proxy suffix after {@code \0}, strips a trailing
     * dot and IPv6 brackets, lower-cases it.
     *
     * @return a host name or IP literal (IPv6 without brackets), or {@code null} if the
     *         value is missing, the wildcard address, or not a valid host
     */
    static String sanitizeHost(String raw) {
        if (raw == null) return null;
        int nul = raw.indexOf('\0');
        String h = (nul >= 0 ? raw.substring(0, nul) : raw).trim().toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.isEmpty() || h.length() > 253) return null;
        if (h.indexOf(':') >= 0) {
            if (!IPV6_CHARS.matcher(h).matches()) return null;
            try {
                // Only hex digits, ':' and '.': parsed as a literal, no DNS lookup.
                InetAddress a = InetAddress.getByName(h);
                return a.isAnyLocalAddress() ? null : h;
            } catch (UnknownHostException e) {
                return null;
            }
        }
        if (!HOST_NAME.matcher(h).matches()) return null;
        if (h.equals("0.0.0.0")) return null;
        return h;
    }

    /**
     * @return {@code true} if {@code host} only reaches this machine: {@code localhost},
     *         {@code *.localhost}, 127/8, {@code ::1}, IPv4-mapped loopback, or the wildcard
     *         address. Other names are not resolved.
     */
    public static boolean isLocalOnly(String host) {
        if (host == null || host.isBlank()) return true;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) h = h.substring(1, h.length() - 1);
        int zone = h.indexOf('%');
        if (zone >= 0) h = h.substring(0, zone);
        if (h.equals("localhost") || h.endsWith(".localhost")) return true;
        if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return h.startsWith("127.") || h.equals("0.0.0.0");
        if (h.indexOf(':') >= 0 && IPV6_CHARS.matcher(h).matches()) {
            try {
                InetAddress a = InetAddress.getByName(h);
                return a.isLoopbackAddress() || a.isAnyLocalAddress();
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * The startup line describing how pack URLs are built.
     *
     * @param perPlayer   {@code true} in per-player mode
     * @param behindProxy {@code true} if a proxy setup was detected
     * @param resolution  the fixed URL resolution
     * @param port        the self-host port
     */
    public static String modeLine(boolean perPlayer, boolean behindProxy, SelfHostAddress.Resolution resolution,
                                  int port) {
        String firewall = "TCP port " + port + " must be open in this machine's firewall (LAN players need it too)"
                + " and forwarded on your router for players on the internet.";
        if (perPlayer) {
            return "Pack URL mode: per-player: http://<server address the player joined with>:" + port
                    + SelfHostAddress.PACK_PATH + " (LAN IP, public IP or domain). " + firewall
                    + " Set upload.self-host.public-url or public-host to send one fixed URL instead.";
        }
        String why = switch (resolution.source()) {
            case PUBLIC_URL, PUBLIC_HOST -> "from " + resolution.source().description();
            default -> behindProxy
                    ? "behind a proxy, so it cannot be taken from the player's address; set"
                      + " upload.self-host.public-url or public-host"
                    : "from " + resolution.source().description();
        };
        String line = "Pack URL mode: fixed URL " + resolution.url() + " (" + why + ").";
        if (resolution.source() == SelfHostAddress.Source.PUBLIC_URL) {
            return line + " The built-in HTTP server listens on TCP port " + port
                    + "; your URL must reach it (firewall, port forward or reverse proxy).";
        }
        return line + " " + firewall;
    }
}
