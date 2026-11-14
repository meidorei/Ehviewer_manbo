package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure accumulation and stopping rules for a newest-first bookmark scan. */
final class BookmarkScanAccumulator {
    private final FeedBoundary oldBoundary;
    private final Set<Long> newGids = new LinkedHashSet<>();
    private FeedBoundary top = FeedBoundary.EMPTY;
    private long oldestTime = Long.MAX_VALUE;
    private int pages;
    private int galleryCount;
    private BookmarkScanResult.EndReason endReason;
    private boolean boundaryProven;

    BookmarkScanAccumulator(FeedBoundary oldBoundary) {
        this.oldBoundary = oldBoundary == null ? FeedBoundary.EMPTY : oldBoundary;
    }

    boolean addPage(List<GalleryInfo> galleries, boolean hasNextPage) {
        if (endReason != null) throw new IllegalStateException("scan already complete");
        List<GalleryInfo> safe = galleries == null ? new ArrayList<>() : galleries;
        oldestTime = GlobalScanPolicy.validatePageOrder(safe, oldestTime);
        pages++;
        galleryCount += safe.size();
        for (GalleryInfo gallery : safe) {
            if (oldBoundary.isNewForUpdate(gallery.postedTimestamp, gallery.gid)) {
                newGids.add(gallery.gid);
            }
        }

        top = LocalBaselineResetPolicy.newer(top, LocalFollowRepository.boundaryOf(safe));
        if (oldBoundary.isEmpty() && GlobalScanPolicy.coversCursor(top, safe, hasNextPage)) {
            endReason = BookmarkScanResult.EndReason.BASELINE;
            return false;
        }
        if (GlobalScanPolicy.hasPassedCursor(oldBoundary, safe)) {
            boundaryProven = true;
            endReason = BookmarkScanResult.EndReason.BOUNDARY;
            return false;
        }
        if (!hasNextPage) {
            boundaryProven = true;
            endReason = BookmarkScanResult.EndReason.LIST_END;
            return false;
        }
        return true;
    }

    BookmarkScanResult finish() {
        if (endReason == null) throw new IllegalStateException("scan is incomplete");
        return new BookmarkScanResult(new ArrayList<>(newGids), top,
                boundaryProven, pages, galleryCount, endReason);
    }
}
