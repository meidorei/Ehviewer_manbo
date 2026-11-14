package com.hippo.ehviewer.subscription;

import java.util.Collections;
import java.util.List;

/** Immutable result of one complete newest-first bookmark scan. */
final class BookmarkScanResult {
    enum EndReason { BASELINE, BOUNDARY, LIST_END }

    final List<Long> newGids;
    final FeedBoundary top;
    final boolean boundaryProven;
    final int pages;
    final int galleryCount;
    final EndReason endReason;

    BookmarkScanResult(List<Long> newGids, FeedBoundary top,
                       boolean boundaryProven, int pages, int galleryCount,
                       EndReason endReason) {
        this.newGids = Collections.unmodifiableList(newGids);
        this.top = top == null ? FeedBoundary.EMPTY : top;
        this.boundaryProven = boundaryProven;
        this.pages = pages;
        this.galleryCount = galleryCount;
        this.endReason = endReason;
    }
}
