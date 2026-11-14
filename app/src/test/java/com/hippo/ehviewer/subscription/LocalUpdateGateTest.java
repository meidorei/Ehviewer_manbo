package com.hippo.ehviewer.subscription;

import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class LocalUpdateGateTest {
    @Test public void maintenanceExcludesUpdatesAndCannotBeReleasedByUpdateCleanup() {
        LocalUpdateGate gate = new LocalUpdateGate();
        assertTrue(gate.tryStartMaintenance());
        assertTrue(gate.isBusy());
        assertFalse(gate.isUpdating());
        assertFalse(gate.tryStartUpdate());
        assertFalse(gate.tryStartMaintenance());
        gate.finishUpdate();
        assertTrue(gate.isBusy());
        gate.finishMaintenance();
        assertTrue(gate.tryStartUpdate());
        assertFalse(gate.tryStartMaintenance());
        gate.finishMaintenance();
        assertTrue(gate.isUpdating());
        gate.finishUpdate();
        assertFalse(gate.isBusy());
    }

    @Test public void simultaneousResetAndUpdateHaveOnlyOneOwner() throws Exception {
        LocalUpdateGate gate = new LocalUpdateGate();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger owners = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> update = workers.submit(() -> {
                await(start);
                if (gate.tryStartUpdate()) owners.incrementAndGet();
            });
            Future<?> reset = workers.submit(() -> {
                await(start);
                if (gate.tryStartMaintenance()) owners.incrementAndGet();
            });
            start.countDown();
            update.get(5, TimeUnit.SECONDS);
            reset.get(5, TimeUnit.SECONDS);
            assertEquals(1, owners.get());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test public void failedMaintenanceReleasesItsOwnerInFinally() {
        LocalUpdateGate gate = new LocalUpdateGate();
        try {
            assertTrue(gate.tryStartMaintenance());
            throw new IllegalStateException("database failure");
        } catch (IllegalStateException expected) {
            assertEquals("database failure", expected.getMessage());
        } finally {
            gate.finishMaintenance();
        }
        assertTrue(gate.tryStartUpdate());
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }
}
