package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Boundary helpers for automatic zero baselines. */
public final class BaselineBoundaryPolicy {
    private BaselineBoundaryPolicy() {}

    /**
     * Uses the next whole second as an exclusive watermark. Galleries stamped in the
     * second in which the item was added are therefore treated as existing history,
     * while galleries from the following second are still new.
     */
    public static FeedBoundary provisional(long addedAtMillis) {
        long nextSecond = Math.max(1L, addedAtMillis / 1000L + 1L);
        return new FeedBoundary(nextSecond, java.util.Collections.emptySet());
    }

    /**
     * Returns the newest complete timestamp group in the page that is no later than
     * the insertion time. Results published while a baseline job waited in the queue
     * are deliberately excluded.
     */
    public static FeedBoundary newestAtOrBefore(List<GalleryInfo> galleries,
                                                long addedAtMillis) {
        if (galleries == null || galleries.isEmpty()) return FeedBoundary.EMPTY;
        long cutoff = Math.max(0L, addedAtMillis / 1000L);
        long selected = 0L;
        Set<Long> gids = new LinkedHashSet<>();
        boolean groupEnded = false;
        for (GalleryInfo gallery : galleries) {
            if (gallery == null || gallery.postedTimestamp <= 0
                    || gallery.postedTimestamp > cutoff) {
                continue;
            }
            if (selected == 0L) selected = gallery.postedTimestamp;
            if (gallery.postedTimestamp != selected) {
                groupEnded = true;
                break;
            }
            gids.add(gallery.gid);
        }
        // If the selected timestamp group reaches the page edge, another result with
        // the same timestamp may be on the next page. Keep the safer time fallback.
        return selected == 0L || !groupEnded
                ? FeedBoundary.EMPTY : new FeedBoundary(selected, gids);
    }
}
