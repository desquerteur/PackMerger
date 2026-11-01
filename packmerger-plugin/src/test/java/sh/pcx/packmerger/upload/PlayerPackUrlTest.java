package sh.pcx.packmerger.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sh.pcx.packmerger.upload.PlayerPackUrl.Choice;
import sh.pcx.packmerger.upload.PlayerPackUrl.Mode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zero-config self-host: each player gets a pack URL built from the address they joined
 * with, and a URL that only works on the server machine is never sent to a remote player.
 */
class PlayerPackUrlTest {

    /** What 1.4.0 published with nothing configured (the live-server bug). */
    private static final String LOCALHOST_URL = "http://localhost:25566/pack";

    @Test
    void rootCause_nothingConfigured_resolvesToLocalhost() {
        // public-url, public-host and server-ip empty, detect-public-ip off (default):
        // only the wildcard bind address is left, and it is advertised as localhost.
        SelfHostAddress.Resolution r = SelfHostAddress.resolve("", List.of(
                new SelfHostAddress.Candidate(SelfHostAddress.Source.PUBLIC_HOST, ""),
                new SelfHostAddress.Candidate(SelfHostAddress.Source.SERVER_IP, ""),
                new SelfHostAddress.Candidate(SelfHostAddress.Source.BIND_ADDRESS, "0.0.0.0")), 25566);
        assertEquals(LOCALHOST_URL, r.url());
        assertEquals(SelfHostAddress.Source.BIND_ADDRESS, r.source());
    }

    @Test
    void lanPlayer_getsTheLanAddressTheyJoinedWith() {
        Choice c = PlayerPackUrl.choose(LOCALHOST_URL, 25566, "192.168.178.20", false);
        assertEquals(Mode.PER_PLAYER, c.mode());
        assertEquals("http://192.168.178.20:25566/pack", c.url());
    }

    @Test
    void internetPlayers_getTheDomainOrPublicIp() {
        assertEquals("http://play.example.com:25566/pack",
                PlayerPackUrl.choose(LOCALHOST_URL, 25566, "Play.Example.com", false).url());
        assertEquals("http://203.0.113.7:25566/pack",
                PlayerPackUrl.choose(LOCALHOST_URL, 25566, "203.0.113.7", false).url());
    }

    @Test
    void ipv6_isBracketed() {
        assertEquals("http://[2001:db8::1]:25566/pack",
                PlayerPackUrl.choose(LOCALHOST_URL, 25566, "2001:db8::1", false).url());
        assertEquals("http://[2001:db8::1]:25566/pack",
                PlayerPackUrl.choose(LOCALHOST_URL, 25566, "[2001:db8::1]", false).url());
    }

    @Test
    void forgeSuffixAndTrailingDot_areStripped() {
        assertEquals("http://mc.example.com:25566/pack",
                PlayerPackUrl.choose(LOCALHOST_URL, 25566, "mc.example.com.\0FML3\0", false).url());
    }

    @Test
    void missingVirtualHost_fallsBackToTheFixedUrl() {
        Choice c = PlayerPackUrl.choose("http://203.0.113.7:25566/pack", 25566, null, false);
        assertEquals(Mode.FIXED, c.mode());
        assertEquals("http://203.0.113.7:25566/pack", c.url());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "evil.com/x?y", "a b", "host:abc", "0.0.0.0", "::", "-bad.example", "fe80::1%eth0"})
    void invalidVirtualHost_isNotUsed(String vhost) {
        Choice c = PlayerPackUrl.choose("http://203.0.113.7:25566/pack", 25566, vhost, false);
        assertEquals(Mode.FIXED, c.mode());
    }

    @Test
    void loopbackVirtualHostFromRemotePlayer_isNotUsed() {
        // e.g. a tunnel agent on this machine: the player typed localhost but is remote.
        assertEquals(Mode.FIXED, PlayerPackUrl.choose("http://203.0.113.7:25566/pack", 25566, "localhost", false).mode());
        assertEquals(Mode.FIXED, PlayerPackUrl.choose("http://203.0.113.7:25566/pack", 25566, "127.0.0.1", false).mode());
    }

    @Test
    void playerOnTheServerMachine_mayUseLocalhost() {
        Choice c = PlayerPackUrl.choose(LOCALHOST_URL, 25566, "localhost", true);
        assertEquals(Mode.PER_PLAYER, c.mode());
        assertEquals(LOCALHOST_URL, c.url());
        assertEquals(Mode.FIXED, PlayerPackUrl.choose(LOCALHOST_URL, -1, null, true).mode());
    }

    @Test
    void loopbackUrl_isNeverSentToARemotePlayer() {
        // Proxy / explicit host settings disable per-player URLs (port -1).
        assertEquals(Mode.UNUSABLE, PlayerPackUrl.choose(LOCALHOST_URL, -1, "192.168.178.20", false).mode());
        assertEquals(Mode.UNUSABLE, PlayerPackUrl.choose("http://127.0.0.1:25566/pack", -1, null, false).mode());
        assertEquals(Mode.UNUSABLE, PlayerPackUrl.choose("http://[::1]:25566/pack", -1, null, false).mode());
        // Per-player on, but the virtual host is unusable and the fallback is loopback.
        assertEquals(Mode.UNUSABLE, PlayerPackUrl.choose(LOCALHOST_URL, 25566, null, false).mode());
    }

    @Test
    void explicitUrl_isKeptWhenPerPlayerIsOff() {
        Choice c = PlayerPackUrl.choose("https://cdn.example.com/rp.zip", -1, "192.168.178.20", false);
        assertEquals(Mode.FIXED, c.mode());
        assertEquals("https://cdn.example.com/rp.zip", c.url());
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "a.localhost", "127.0.0.1", "127.8.9.10", "::1", "[::1]", "::ffff:127.0.0.1",
            "0.0.0.0", "::", ""})
    void localOnlyHosts(String host) {
        assertTrue(PlayerPackUrl.isLocalOnly(host));
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.168.178.20", "10.0.0.5", "203.0.113.7", "play.example.com", "2001:db8::1", "fd00::1"})
    void reachableHosts(String host) {
        assertFalse(PlayerPackUrl.isLocalOnly(host));
    }

    @Test
    void modeLine_perPlayer_namesThePortAndTheFirewall() {
        SelfHostAddress.Resolution r = new SelfHostAddress.Resolution(LOCALHOST_URL, "localhost",
                SelfHostAddress.Source.BIND_ADDRESS, false);
        String line = PlayerPackUrl.modeLine(true, false, r, 25566);
        assertTrue(line.startsWith("Pack URL mode: per-player: http://<server address the player joined with>:25566/pack"));
        assertTrue(line.contains("TCP port 25566 must be open in this machine's firewall (LAN players need it too)"));
        assertTrue(line.contains("forwarded on your router"));
    }

    @Test
    void modeLine_fixed_namesTheSourceOrTheProxy() {
        SelfHostAddress.Resolution host = new SelfHostAddress.Resolution("http://play.example.com:25566/pack",
                "play.example.com", SelfHostAddress.Source.PUBLIC_HOST, true);
        String line = PlayerPackUrl.modeLine(false, false, host, 25566);
        assertTrue(line.startsWith("Pack URL mode: fixed URL http://play.example.com:25566/pack (from upload.self-host.public-host)"));
        assertTrue(line.contains("TCP port 25566"));

        SelfHostAddress.Resolution bind = new SelfHostAddress.Resolution(LOCALHOST_URL, "localhost",
                SelfHostAddress.Source.BIND_ADDRESS, false);
        assertTrue(PlayerPackUrl.modeLine(false, true, bind, 25566).contains("behind a proxy"));
    }
}
