package com.hippo.ehviewer.download;

import org.junit.Test;
import java.util.Arrays;
import java.util.LinkedList;
import static org.junit.Assert.*;

public class ArchiveImportGateTest {
    @Test public void newlyQueuedSameGalleryWaitsWhileOtherDownloadsContinue() {
        ArchiveImportGate gate = new ArchiveImportGate();
        assertTrue(gate.acquire(10, 100));
        LinkedList<Long> queue = new LinkedList<>(Arrays.asList(10L, 11L, 12L));
        assertEquals(Long.valueOf(11), gate.takeNext(queue, Long::longValue));
        assertEquals(Arrays.asList(10L, 12L), queue);
        assertEquals(Long.valueOf(12), gate.takeNext(queue, Long::longValue));
        assertNull(gate.takeNext(queue, Long::longValue));
        assertEquals(Arrays.asList(10L), queue);
        assertTrue(gate.release(10, 100));
        assertEquals(Long.valueOf(10), gate.takeNext(queue, Long::longValue));
        assertTrue(queue.isEmpty());
    }

    @Test public void successfulFailedAndCancelledImportAllAllowRescheduling() {
        for (int outcome = 0; outcome < 3; outcome++) {
            ArchiveImportGate gate = new ArchiveImportGate();
            assertTrue(gate.acquire(10, 100));
            LinkedList<Long> queue = new LinkedList<>(Arrays.asList(10L));
            try {
                assertNull(gate.takeNext(queue, Long::longValue));
                if (outcome != 0) throw new IllegalStateException("Failed or cancelled");
            } catch (IllegalStateException expected) {
                // The same finally path releases all outcomes.
            } finally {
                assertTrue(gate.release(10, 100));
            }
            assertEquals(Long.valueOf(10), gate.takeNext(queue, Long::longValue));
        }
    }

    @Test public void oldCompletionCannotReleaseReplacementOwner() {
        ArchiveImportGate gate = new ArchiveImportGate();
        assertTrue(gate.acquire(10, 100));
        assertFalse(gate.acquire(10, 101));
        assertTrue(gate.release(10, 100));
        assertTrue(gate.acquire(10, 101));
        assertFalse(gate.release(10, 100));
        assertTrue(gate.owns(10, 101));
        assertFalse(gate.owns(10, 100));
    }

    @Test public void successfulImportRemovesItsWaitingRequestsBeforeRescheduling() {
        ArchiveImportGate gate = new ArchiveImportGate();
        gate.acquire(10, 100);
        LinkedList<Long> queue = new LinkedList<>(Arrays.asList(10L, 11L, 10L));
        gate.commitAndRemoveWaiting(queue, 10, Long::longValue,
                () -> assertEquals(Arrays.asList(10L, 11L, 10L), queue));
        assertEquals(Arrays.asList(11L), queue);
        assertTrue(gate.release(10, 100));
        assertEquals(Long.valueOf(11), gate.takeNext(queue, Long::longValue));
        assertNull(gate.takeNext(queue, Long::longValue));
        // An explicit request made after completion is still accepted normally.
        queue.add(10L);
        assertEquals(Long.valueOf(10), gate.takeNext(queue, Long::longValue));
    }

    @Test public void failedCommitPreservesWaitingRequestForNormalDownload() {
        ArchiveImportGate gate = new ArchiveImportGate();
        gate.acquire(10, 100);
        LinkedList<Long> queue = new LinkedList<>(Arrays.asList(10L, 11L));
        assertThrows(IllegalStateException.class, () -> gate.commitAndRemoveWaiting(
                queue, 10, Long::longValue, () -> { throw new IllegalStateException("Database full"); }));
        assertEquals(Arrays.asList(10L, 11L), queue);
        assertTrue(gate.release(10, 100));
        assertEquals(Long.valueOf(10), gate.takeNext(queue, Long::longValue));
    }
}
