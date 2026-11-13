package sh.pcx.packmerger.datapack;

import sh.pcx.packmerger.datapack.DatapackPackManager.ScanReport;
import sh.pcx.packmerger.datapack.DatapackPackManager.ScanTrigger;

import java.util.concurrent.CompletableFuture;

/**
 * One datapack scan at a time. A request made while a scan runs is answered by the
 * next scan, which starts once the running one ends: the running scan may have read
 * the folders before the change that prompted the request ({@code /pm scan} right
 * after copying a datapack, {@code /pm rebuild}). Requests made meanwhile share that
 * next scan; it runs with the strongest trigger and with a forced lookup if any of
 * them asked for one ({@code /pm scan refresh}).
 */
final class ScanQueue {

    /**
     * A scan to run once the current one ends.
     *
     * @param result completed with that scan's report (handed to every request it serves)
     */
    record Queued(ScanTrigger trigger, boolean forceLookup, CompletableFuture<ScanReport> result) {}

    private boolean running;
    private CompletableFuture<ScanReport> current;
    private Queued queued;

    /**
     * @param done the future of the scan the caller would start
     * @return {@code null} if no scan runs: the caller starts it now and calls
     *         {@link #finish()} when it ends; otherwise the future of the queued scan
     *         that will serve this request
     */
    synchronized CompletableFuture<ScanReport> tryStart(ScanTrigger trigger, boolean forceLookup,
                                                       CompletableFuture<ScanReport> done) {
        if (!running) {
            running = true;
            current = done;
            return null;
        }
        if (queued == null) {
            queued = new Queued(trigger, forceLookup, new CompletableFuture<>());
        } else {
            // A watcher event never downgrades a queued manual scan, rebuild or reload.
            ScanTrigger t = trigger == ScanTrigger.WATCHER ? queued.trigger() : trigger;
            queued = new Queued(t, forceLookup || queued.forceLookup(), queued.result());
        }
        return queued.result();
    }

    /**
     * Ends the running scan.
     *
     * @return the scan requested meanwhile (the caller starts it and relays its report
     *         to {@link Queued#result()}), or {@code null}
     */
    synchronized Queued finish() {
        running = false;
        Queued next = queued;
        queued = null;
        return next;
    }

    synchronized boolean isRunning() {
        return running;
    }

    /** @return the running scan's future, or the last one's once it ended ({@code null} before the first) */
    synchronized CompletableFuture<ScanReport> current() {
        return current;
    }
}
