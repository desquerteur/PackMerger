package sh.pcx.packmerger.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Downloads one file that must match a known hash ({@link VerifiedSpec}). Pure: no
 * Bukkit references; {@link RemotePackManager#fetchVerified} wraps it with logging.
 *
 * <ul>
 *   <li>HTTPS only; redirects are followed by hand (at most {@value #MAX_REDIRECTS})
 *       and every hop must be HTTPS to an allowed host;</li>
 *   <li>a {@code Content-Length} over the cap (or over the expected size) is refused
 *       before reading; the body is counted while streaming and aborted at the cap;</li>
 *   <li>SHA-512 (and SHA-1 and size, when known) are verified before the file is moved
 *       into place; nothing unverified is ever left at the target;</li>
 *   <li>a target that already has the expected SHA-512 is not downloaded again.</li>
 * </ul>
 */
public final class VerifiedDownload {

    static final int MAX_REDIRECTS = 3;

    private final HttpClient client;

    /** Test hook: also allow plain HTTP to a loopback host (stub servers). Never set in production. */
    boolean allowLoopbackHttp;

    public VerifiedDownload() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    VerifiedDownload(HttpClient client) {
        this.client = client;
    }

    /**
     * @return {@code FETCHED} (downloaded and verified), {@code NOT_MODIFIED} (the target
     *         already had the expected hash; no request made) or {@code ERROR_NO_CACHE}
     *         with the reason
     */
    public FetchResult fetch(VerifiedSpec spec, Path target) {
        String expected = spec.expectedSha512() == null ? null : spec.expectedSha512().toLowerCase(Locale.ROOT);
        if (expected == null || !expected.matches("[0-9a-f]{128}")) {
            return error(spec, "no valid SHA-512 to verify against");
        }
        try {
            if (Files.isRegularFile(target) && expected.equals(hashes(target)[1])) {
                return new FetchResult(spec.alias(), FetchResult.Status.NOT_MODIFIED, "already downloaded");
            }
        } catch (IOException e) {
            // Unreadable cached file: download again.
        }
        long deadline = System.nanoTime() + spec.timeout().toNanos();
        URI uri = spec.url();
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                String refusal = refuse(uri, spec);
                if (refusal != null) return error(spec, refusal);
                Duration left = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
                HttpRequest req = HttpRequest.newBuilder(uri).timeout(left)
                        .header("User-Agent", spec.userAgent()).GET().build();
                HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = resp.statusCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    resp.body().close();
                    String location = resp.headers().firstValue("Location").orElse(null);
                    if (location == null) return error(spec, "HTTP " + code + " without a Location header");
                    uri = uri.resolve(location);
                    continue;
                }
                if (code != 200) {
                    resp.body().close();
                    return error(spec, "HTTP " + code + " from " + uri.getHost());
                }
                return download(spec, resp, target, expected, deadline);
            }
            return error(spec, "more than " + MAX_REDIRECTS + " redirects");
        } catch (HttpTimeoutException e) {
            return error(spec, "timed out after " + spec.timeout().toSeconds() + "s");
        } catch (IOException e) {
            return error(spec, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(spec, "interrupted");
        }
    }

    /** @return why a hop is not allowed, or {@code null} */
    private String refuse(URI uri, VerifiedSpec spec) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("127.0.0.1") || host.equals("localhost");
        if (!scheme.equals("https") && !(allowLoopbackHttp && loopback && scheme.equals("http"))) {
            return "refusing non-HTTPS URL " + uri;
        }
        if (!spec.allowedHosts().contains(host)) {
            return "host " + host + " not in allowed-hosts";
        }
        return null;
    }

    private FetchResult download(VerifiedSpec spec, HttpResponse<InputStream> resp, Path target, String expected,
                                 long deadline) throws IOException, InterruptedException {
        long cap = spec.maxBytes();
        if (spec.expectedSize() > 0) cap = Math.min(cap, spec.expectedSize());
        OptionalLong length = resp.headers().firstValueAsLong("Content-Length");
        if (length.isPresent() && length.getAsLong() > cap) {
            resp.body().close();
            return error(spec, "Content-Length " + length.getAsLong() + " exceeds the limit of " + cap + " bytes");
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        final long limit = cap;
        InputStream in = resp.body();
        // The body is copied on another thread so a stalled server cannot hold this one
        // past the deadline; closing the stream ends the copy.
        CountDownLatch finished = new CountDownLatch(1);
        CompletableFuture<long[]> copy = CompletableFuture.supplyAsync(() -> {
            try (in; OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int r;
                while ((r = in.read(buf)) > 0) {
                    total += r;
                    if (total > limit) return new long[]{-1, total};
                    out.write(buf, 0, r);
                }
                return new long[]{0, total};
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } finally {
                finished.countDown();
            }
        });
        try {
            long[] result;
            try {
                result = copy.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                in.close(); // unblocks the reading thread
                return error(spec, "timed out after " + spec.timeout().toSeconds() + "s");
            } catch (ExecutionException e) {
                Throwable c = e.getCause() instanceof java.io.UncheckedIOException u ? u.getCause() : e.getCause();
                return error(spec, "download failed: " + c.getMessage());
            }
            if (result[0] < 0) {
                return error(spec, "download exceeds the limit of " + limit + " bytes");
            }
            long size = result[1];
            if (spec.expectedSize() > 0 && size != spec.expectedSize()) {
                return error(spec, "size mismatch: expected " + spec.expectedSize() + " bytes, got " + size);
            }
            String[] h = hashes(part);
            if (!expected.equals(h[1])) return error(spec, "hash mismatch (sha512)");
            if (spec.expectedSha1() != null && !spec.expectedSha1().equalsIgnoreCase(h[0])) {
                return error(spec, "hash mismatch (sha1)");
            }
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new FetchResult(spec.alias(), FetchResult.Status.FETCHED, "verified sha512, " + size + " bytes");
        } finally {
            // Let the copy thread release the .part file before deleting it (Windows locks open files).
            finished.await(5, TimeUnit.SECONDS);
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // still open; the next download replaces it
            }
        }
    }

    private static FetchResult error(VerifiedSpec spec, String detail) {
        return new FetchResult(spec.alias(), FetchResult.Status.ERROR_NO_CACHE, detail);
    }

    /** @return {sha1, sha512} of a file, lowercase hex */
    static String[] hashes(Path file) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    sha1.update(buf, 0, r);
                    sha512.update(buf, 0, r);
                }
            }
            HexFormat hex = HexFormat.of();
            return new String[]{hex.formatHex(sha1.digest()), hex.formatHex(sha512.digest())};
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
