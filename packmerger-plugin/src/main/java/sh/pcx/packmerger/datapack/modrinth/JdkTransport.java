package sh.pcx.packmerger.datapack.modrinth;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link ModrinthClient.Transport} on the JDK {@link HttpClient}. Redirects are not
 * followed (the API does not redirect), the body is read up to {@code maxBytes + 1}
 * bytes only, and the client refuses non-HTTPS URLs except for a loopback host
 * (tests and local mirrors).
 *
 * <p>{@code timeout} covers the whole request, body included: the JDK's request
 * timeout ends when the headers arrive, so the body is read on another thread and
 * abandoned (stream closed) at the deadline. A half-open connection therefore cannot
 * hang a datapack scan.</p>
 */
public final class JdkTransport implements ModrinthClient.Transport, AutoCloseable {

    private final HttpClient client;
    private final int maxBytes;

    public JdkTransport(int maxBytes) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.maxBytes = maxBytes;
    }

    @Override
    public Response send(String method, URI uri, String jsonBody, Map<String, String> headers, Duration timeout)
            throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri.getHost())) {
            throw new IOException("refusing non-HTTPS Modrinth URL " + uri);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(b::header);
        b.method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody));
        HttpResponse<InputStream> r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] body = readBody(r.body(), maxBytes + 1, deadline);
        return new Response(r.statusCode(), r.headers().map(), body);
    }

    /**
     * Reads at most {@code limit} bytes before {@code deadline} ({@link System#nanoTime()}).
     *
     * @throws HttpTimeoutException if the deadline passes first (the stream is closed)
     */
    static byte[] readBody(InputStream in, int limit, long deadline) throws IOException, InterruptedException {
        CompletableFuture<byte[]> read = CompletableFuture.supplyAsync(() -> {
            try (in) {
                return in.readNBytes(limit);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        try {
            return read.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            in.close(); // unblocks the reading thread
            throw new HttpTimeoutException("response body not received in time");
        } catch (ExecutionException e) {
            Throwable c = e.getCause() instanceof java.io.UncheckedIOException u ? u.getCause() : e.getCause();
            throw c instanceof IOException io ? io : new IOException(String.valueOf(c.getMessage()), c);
        }
    }

    /**
     * Releases the client's connections and selector thread (on {@code /pm reload} and
     * disable). Does not wait: a request still running fails instead of blocking the caller.
     */
    @Override
    public void close() {
        client.shutdownNow();
    }

    static boolean isLoopback(String host) {
        return host != null && (host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost") || host.equals("[::1]"));
    }
}
