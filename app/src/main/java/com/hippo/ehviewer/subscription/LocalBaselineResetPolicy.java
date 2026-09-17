package com.hippo.ehviewer.subscription;

import java.util.LinkedHashSet;
import java.util.Set;

/** Pure boundary rules shared by manual resets and local update scans. */
public final class LocalBaselineResetPolicy {
    private LocalBaselineResetPolicy() {}

    static boolean requiresStoppedJob(String status) {
        return LocalRefreshJobStore.STATUS_RUNNING.equals(status)
                || LocalRefreshJobStore.STATUS_PAUSED.equals(status);
    }

    public static FeedBoundary newer(FeedBoundary current, FeedBoundary floor) {
        if (current == null || current.isEmpty()) {
            return floor == null ? FeedBoundary.EMPTY : floor;
        }
        if (floor == null || floor.isEmpty() || current.time > floor.time) return current;
        if (floor.time > current.time) return floor;
        Set<Long> gids = new LinkedHashSet<>(current.gids);
        gids.addAll(floor.gids);
        return new FeedBoundary(current.time, gids);
    }
}
