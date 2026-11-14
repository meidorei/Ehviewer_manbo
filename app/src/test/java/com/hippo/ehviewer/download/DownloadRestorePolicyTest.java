package com.hippo.ehviewer.download;

import com.hippo.ehviewer.client.exception.CancelledException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class DownloadRestorePolicyTest {
    @Test public void failedBatchDoesNotDiscardLaterBatches() throws Throwable {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 51; i++) items.add(i);
        List<Integer> sizes = new ArrayList<>(), loaded = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        DownloadRestorePolicy.fill(items, () -> false, batch -> {
            sizes.add(batch.size());
            if (batch.get(0) == 25) throw new IOException("offline");
            loaded.addAll(batch);
        }, failures::add);
        assertEquals(Arrays.asList(25, 25, 1), sizes);
        assertEquals(26, loaded.size());
        assertEquals(Integer.valueOf(50), loaded.get(25));
        assertEquals(1, failures.size());
        assertEquals(51, items.size());
    }

    @Test public void cancellationPreventsNextBatchAndCompletion() {
        AtomicBoolean cancelled = new AtomicBoolean();
        List<Integer> calls = new ArrayList<>();
        assertThrows(CancellationException.class, () ->
                DownloadRestorePolicy.fill(Collections.nCopies(26, 1), cancelled::get,
                        batch -> { calls.add(1); cancelled.set(true); },
                        error -> fail("Cancellation is not a batch failure")));
        assertEquals(1, calls.size());
    }

    @Test public void interruptedAndCancelledRequestsPropagate() {
        for (Throwable failure : Arrays.asList(new InterruptedException(), new CancelledException(),
                new CancellationException(), new AssertionError())) {
            Throwable actual = assertThrows(failure.getClass(), () ->
                    DownloadRestorePolicy.fill(Collections.singletonList(1), () -> false,
                            batch -> { throw failure; }, error -> fail("Must propagate")));
            assertSame(failure, actual);
        }
    }

    @Test public void alreadyCancelledOrInterruptedDoesNotLoad() {
        assertThrows(CancellationException.class, () -> DownloadRestorePolicy.fill(
                Collections.singletonList(1), () -> true, batch -> fail(), error -> fail()));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> DownloadRestorePolicy.fill(
                    Collections.singletonList(1), () -> false, batch -> fail(), error -> fail()));
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void duplicatesAndInvalidMetadataAreSkipped() {
        Set<Long> seen = new HashSet<>();
        assertFalse(DownloadRestorePolicy.accept(1, "token", true, seen));
        assertFalse(DownloadRestorePolicy.accept(-1, "token", false, seen));
        assertFalse(DownloadRestorePolicy.accept(1, "", false, seen));
        assertTrue(DownloadRestorePolicy.accept(1, "token", false, seen));
        assertFalse(DownloadRestorePolicy.accept(1, "token", false, seen));
    }

    @Test public void titleFallsBackWithoutDiscardingLocalEntry() {
        assertEquals("API", DownloadRestorePolicy.title("API", "folder", 1));
        assertEquals("folder", DownloadRestorePolicy.title(null, "folder", 1));
        assertEquals("1", DownloadRestorePolicy.title("", "", 1));
    }
}
