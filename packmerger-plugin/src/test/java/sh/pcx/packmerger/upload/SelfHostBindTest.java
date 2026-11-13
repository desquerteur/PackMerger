package sh.pcx.packmerger.upload;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

/** upload.self-host.bind: which address the HTTP server listens on and where the local check connects. */
class SelfHostBindTest {

    @Test
    void blankBindListensOnAllInterfaces() throws IOException {
        assertTrue(SelfHostProvider.bindAddress("", 28000).getAddress().isAnyLocalAddress());
        assertTrue(SelfHostProvider.bindAddress("  ", 28000).getAddress().isAnyLocalAddress());
        assertTrue(SelfHostProvider.bindAddress(null, 28000).getAddress().isAnyLocalAddress());
        assertEquals(28000, SelfHostProvider.bindAddress("", 28000).getPort());
    }

    @Test
    void ipLiteralsAndLocalhostAreBoundAsGiven() throws IOException {
        assertEquals("127.0.0.1", SelfHostProvider.bindAddress("127.0.0.1", 1).getAddress().getHostAddress());
        assertEquals("127.0.0.1", SelfHostProvider.bindAddress(" 127.0.0.1 ", 1).getAddress().getHostAddress());
        assertTrue(SelfHostProvider.bindAddress("[::1]", 1).getAddress().isLoopbackAddress());
        assertTrue(SelfHostProvider.bindAddress("localhost", 1).getAddress().isLoopbackAddress());
    }

    @Test
    void unresolvableBindFailsWithTheSetting() {
        IOException e = assertThrows(IOException.class,
                () -> SelfHostProvider.bindAddress("no-such-host.invalid", 1));
        assertTrue(e.getMessage().contains("upload.self-host.bind 'no-such-host.invalid'"), e.getMessage());
    }

    @Test
    void localCheckUsesLoopbackForTheWildcardAndTheBoundIpOtherwise() throws IOException {
        assertEquals("127.0.0.1", SelfHostProvider.localProbeHost(null));
        assertEquals("127.0.0.1", SelfHostProvider.localProbeHost(InetAddress.getByName("0.0.0.0")));
        assertEquals("127.0.0.1", SelfHostProvider.localProbeHost(InetAddress.getByName("::")));
        assertEquals("192.168.1.20", SelfHostProvider.localProbeHost(InetAddress.getByName("192.168.1.20")));
        assertEquals("[0:0:0:0:0:0:0:1]", SelfHostProvider.localProbeHost(InetAddress.getByName("::1")));
    }

    @Test
    void aServerBoundToLoopbackIsReachableThroughTheProbeHost() throws IOException {
        InetSocketAddress address = SelfHostProvider.bindAddress("127.0.0.1", 0);
        HttpServer http = HttpServer.create(address, 0);
        try {
            assertTrue(http.getAddress().getAddress().isLoopbackAddress());
            assertEquals("127.0.0.1", SelfHostProvider.localProbeHost(http.getAddress().getAddress()));
        } finally {
            http.stop(0);
        }
    }
}
