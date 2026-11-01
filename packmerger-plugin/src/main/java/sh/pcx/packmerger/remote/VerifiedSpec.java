package sh.pcx.packmerger.remote;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * A download that must match a known file exactly (used for approved external
 * resource packs).
 *
 * @param alias          name used in results and logs
 * @param url            the HTTPS URL to fetch
 * @param allowedHosts   hosts the URL and every redirect must use (lowercase)
 * @param maxBytes       hard size cap, checked before and while reading
 * @param timeout        limit for the whole download
 * @param expectedSha512 required SHA-512 (lowercase hex)
 * @param expectedSha1   SHA-1 to check as well, or {@code null}
 * @param expectedSize   exact size to check, or {@code -1}
 * @param userAgent      the User-Agent header
 */
public record VerifiedSpec(String alias, URI url, Set<String> allowedHosts, long maxBytes, Duration timeout,
                           String expectedSha512, String expectedSha1, long expectedSize, String userAgent) {}
