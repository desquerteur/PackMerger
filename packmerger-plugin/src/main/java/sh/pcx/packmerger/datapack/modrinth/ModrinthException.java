package sh.pcx.packmerger.datapack.modrinth;

/** A Modrinth request failed. {@link #kind()} tells the scan whether to stop or carry on. */
public final class ModrinthException extends Exception {

    public enum Kind {
        /** HTTP 429, too few requests left, or the per-scan budget is used up: stop this scan. */
        RATE_LIMITED,
        /** Connection failure or timeout. */
        NETWORK,
        /** HTTP 5xx or another unexpected status. */
        SERVER_ERROR,
        /** Malformed or oversized response. */
        BAD_RESPONSE
    }

    private final Kind kind;

    public ModrinthException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
