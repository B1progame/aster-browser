package app.aster.browser;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class CoalescingExecutorTest {
    @Test public void navigationBurstKeepsLatestSnapshotAndOtherKeys() {
        Queue<Runnable> queue = new ArrayDeque<>();
        CoalescingExecutor writer = new CoalescingExecutor(queue::add);
        List<String> writes = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            final int snapshot = i;
            writer.submit("tabs", () -> writes.add("tabs:" + snapshot));
        }
        writer.submit("privacy", () -> writes.add("privacy"));
        assertEquals(1, queue.size());
        queue.remove().run();
        assertEquals(List.of("tabs:999", "privacy"), writes);
        writer.submit("tabs", () -> writes.add("next launch"));
        queue.remove().run();
        assertEquals("next launch", writes.get(2));
    }

    @Test public void slowDiskDoesNotBlockCallerOrLoseNewestLifecycleSave() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseDisk = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        List<String> writes = new ArrayList<>();
        try {
            CoalescingExecutor writer = new CoalescingExecutor(worker);
            writer.submit("tabs", () -> {
                firstWriteStarted.countDown();
                try {
                    if (!releaseDisk.await(5, TimeUnit.SECONDS)) throw new AssertionError("disk latch timed out");
                } catch (InterruptedException e) { throw new AssertionError(e); }
                writes.add("old");
            });
            assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS));
            writer.submit("tabs", () -> writes.add("intermediate"));
            writer.submit("tabs", () -> { writes.add("paused"); finished.countDown(); });
            // Submissions returned while the first write remains blocked.
            assertEquals(1, releaseDisk.getCount());
            releaseDisk.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("old", "paused"), writes);
        } finally {
            releaseDisk.countDown();
            worker.shutdownNow();
        }
    }

    @Test public void storageFailureDoesNotDisableFutureSaves() {
        Queue<Runnable> queue = new ArrayDeque<>();
        CoalescingExecutor writer = new CoalescingExecutor(queue::add);
        List<String> writes = new ArrayList<>();
        writer.submit("tabs", () -> { throw new IllegalStateException("disk unavailable"); });
        writer.submit("privacy", () -> writes.add("privacy"));
        queue.remove().run();
        writer.submit("tabs", () -> writes.add("recovered"));
        queue.remove().run();
        assertEquals(List.of("privacy", "recovered"), writes);
    }
}
