package com.hippo.ehviewer.ui.scene.gallery.list;

/** Decisions for retaining an anchor while revealing a bounded local feed in pages. */
final class FollowUpdatesPagingPolicy {
    private FollowUpdatesPagingPolicy() {}

    static boolean needsAnchorLookup(long anchorGid, int anchorPosition,
                                     int visibleLimit, int fetchedCount) {
        // The lookahead row only proves that another page exists. It is not enough data
        // to expand the visible page, even when it happens to be the requested anchor.
        return anchorGid > 0 && fetchedCount > visibleLimit
                && (anchorPosition < 0 || anchorPosition >= visibleLimit);
    }

    static int limitIncludingAnchor(int visibleLimit, int anchorPosition,
                                    int pageSize, int capacity) {
        if (anchorPosition < visibleLimit) return visibleLimit;
        return Math.min(capacity, ((anchorPosition / pageSize) + 1) * pageSize);
    }
}
