package sh.pcx.packmerger.datapack;

import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.datapack.DatapackPackManager.ScanReport;
import sh.pcx.packmerger.datapack.DatapackPackManager.ScanTrigger;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ScanQueueTest {

    @Test
    void theFirstRequestStartsAScan() {
        ScanQueue q = new ScanQueue();
        CompletableFuture<ScanReport> done = new CompletableFuture<>();
        assertNull(q.tryStart(ScanTrigger.MANUAL, false, done));
        assertTrue(q.isRunning());
        assertSame(done, q.current());
        assertNull(q.finish());
        assertFalse(q.isRunning());
    }

    /** /pm scan while a world-load scan runs must not get that older scan's report. */
    @Test
    void aRequestDuringAScanIsServedByTheNextScan() {
        ScanQueue q = new ScanQueue();
        CompletableFuture<ScanReport> running = new CompletableFuture<>();
        q.tryStart(ScanTrigger.WORLD_CHANGE, false, running);

        CompletableFuture<ScanReport> answer = q.tryStart(ScanTrigger.MANUAL, false, new CompletableFuture<>());
        assertNotNull(answer);
        assertNotSame(running, answer);

        ScanQueue.Queued next = q.finish();
        assertNotNull(next);
        assertEquals(ScanTrigger.MANUAL, next.trigger());
        assertSame(answer, next.result());
        assertFalse(q.isRunning());
        assertNull(q.finish(), "the queued scan is handed out once");
    }

    @Test
    void requestsDuringOneScanShareTheNextScanWithTheStrongestSettings() {
        ScanQueue q = new ScanQueue();
        q.tryStart(ScanTrigger.WATCHER, false, new CompletableFuture<>());
        CompletableFuture<ScanReport> refresh = q.tryStart(ScanTrigger.MANUAL, true, new CompletableFuture<>());
        CompletableFuture<ScanReport> watcher = q.tryStart(ScanTrigger.WATCHER, false, new CompletableFuture<>());
        assertSame(refresh, watcher);

        ScanQueue.Queued next = q.finish();
        // A later watcher event neither drops /pm scan refresh's forced lookup nor its trigger.
        assertTrue(next.forceLookup());
        assertEquals(ScanTrigger.MANUAL, next.trigger());
    }

    @Test
    void aLaterExplicitTriggerReplacesAQueuedWatcherScan() {
        ScanQueue q = new ScanQueue();
        q.tryStart(ScanTrigger.STARTUP, false, new CompletableFuture<>());
        q.tryStart(ScanTrigger.WATCHER, false, new CompletableFuture<>());
        q.tryStart(ScanTrigger.REBUILD, false, new CompletableFuture<>());
        ScanQueue.Queued next = q.finish();
        assertEquals(ScanTrigger.REBUILD, next.trigger());
        assertFalse(next.forceLookup());
    }
}
