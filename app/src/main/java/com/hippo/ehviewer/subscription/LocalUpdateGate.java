package com.hippo.ehviewer.subscription;

import java.util.concurrent.atomic.AtomicReference;

/** One owner for update workers and local bookmark maintenance. */
final class LocalUpdateGate {
    private enum Owner { IDLE, UPDATE, MAINTENANCE }
    private final AtomicReference<Owner> owner = new AtomicReference<>(Owner.IDLE);

    boolean tryStartUpdate() { return owner.compareAndSet(Owner.IDLE, Owner.UPDATE); }
    boolean tryStartMaintenance() { return owner.compareAndSet(Owner.IDLE, Owner.MAINTENANCE); }
    boolean isBusy() { return owner.get() != Owner.IDLE; }
    boolean isUpdating() { return owner.get() == Owner.UPDATE; }
    void finishUpdate() { owner.compareAndSet(Owner.UPDATE, Owner.IDLE); }
    void finishMaintenance() { owner.compareAndSet(Owner.MAINTENANCE, Owner.IDLE); }
}
