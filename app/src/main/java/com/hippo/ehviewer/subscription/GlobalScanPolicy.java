package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.List;

/** Boundary rules for a newest-first shared scan. */
public final class GlobalScanPolicy {
    private GlobalScanPolicy() {}

    public static long validatePageOrder(List<GalleryInfo> page, long previousTime) {
        for (GalleryInfo gallery : page) {
            if (gallery == null || gallery.postedTimestamp <= 0) {
                throw new IllegalArgumentException("更新结果发布时间无效");
            }
            if (gallery.postedTimestamp > previousTime) {
                throw new IllegalArgumentException("更新结果未按发布时间降序排列");
            }
            previousTime = gallery.postedTimestamp;
        }
        return previousTime;
    }

    /**
     * Same-second entries must all be scanned. The cursor is covered only after the feed moves
     * to an older timestamp, preventing a newly indexed same-second GID from being skipped.
     */
    public static boolean hasPassedCursor(FeedBoundary cursor, List<GalleryInfo> galleries) {
        if (cursor == null || cursor.isEmpty() || galleries == null) return false;
        for (GalleryInfo gallery : galleries) {
            if (gallery != null && gallery.postedTimestamp > 0
                    && gallery.postedTimestamp < cursor.time) {
                return true;
            }
        }
        return false;
    }

    static boolean coversCursor(FeedBoundary cursor, List<GalleryInfo> galleries, boolean hasNext) {
        return !hasNext || hasPassedCursor(cursor, galleries);
    }

    /**
     * An item whose own cursor predates the shared cursor cannot be updated safely from the
     * shared scan alone: that scan intentionally stops before reaching the item's boundary.
     */
    public static boolean requiresItemBridge(
            FeedBoundary itemCursor, FeedBoundary sharedCursor) {
        return itemCursor != null && sharedCursor != null
                && !itemCursor.isEmpty() && !sharedCursor.isEmpty()
                && itemCursor.time < sharedCursor.time;
    }

    /**
     * A fallback must cover both the shared scan and every gallery it actually counted. Its
     * query may see newer arrivals; keep that newer top and merge known GIDs for the same second.
     * The shared global cursor remains independent of this per-item boundary.
     */
    public static FeedBoundary fallbackCommitBoundary(
            FeedBoundary itemTop, FeedBoundary sharedTop) {
        return LocalBaselineResetPolicy.newer(itemTop, sharedTop);
    }
}
