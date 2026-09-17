package com.hippo.ehviewer.subscription;

import static org.junit.Assert.*;
import com.hippo.ehviewer.client.data.GalleryInfo;
import java.util.*;
import org.junit.Test;

public class LocalBaselineResetPolicyTest {
    @Test public void resetSecondIsHistoryAndFollowingSecondIsNew() {
        FeedBoundary floor = BaselineBoundaryPolicy.provisional(100_999);
        assertEquals(101, floor.time);
        assertFalse(floor.isNew(100, 1));
        assertTrue(floor.isNew(101, 2));
        assertTrue(floor.isNew(102, 3));
    }

    @Test public void newerBoundaryNeverRegressesAndMergesSameSecondGids() {
        assertEquals(200, LocalBaselineResetPolicy.newer(boundary(100, 1), boundary(200, 2)).time);
        assertEquals(200, LocalBaselineResetPolicy.newer(boundary(200, 2), boundary(100, 1)).time);
        FeedBoundary same = LocalBaselineResetPolicy.newer(boundary(200, 2), boundary(200, 3));
        assertEquals(new LinkedHashSet<>(Arrays.asList(2L, 3L)), same.gids);
        assertFalse(same.isNew(200, 2));
        assertTrue(same.isNew(200, 4));
        assertTrue(LocalBaselineResetPolicy.newer(null, null).isEmpty());
        assertEquals(200, LocalBaselineResetPolicy.newer(FeedBoundary.EMPTY, boundary(200, 2)).time);
    }

    @Test public void longBacklogStopsAtResetRatherThanOldCheckpoint() {
        FeedBoundary old = LocalBaselineResetPolicy.newer(boundary(1, 1),
                BaselineBoundaryPolicy.provisional(100_000));
        BookmarkScanAccumulator scan = new BookmarkScanAccumulator(old);
        assertFalse(scan.addPage(Arrays.asList(gallery(102, 3), gallery(101, 2),
                gallery(100, 1), gallery(99, 4)), true));
        BookmarkScanResult result = scan.finish();
        assertEquals(Arrays.asList(3L, 2L), result.newGids);
        assertEquals(1, result.pages);
        assertTrue(result.boundaryProven);
    }

    @Test public void delayedFirstCheckCountsEverythingAfterResetAcrossPages() {
        BookmarkScanAccumulator scan = new BookmarkScanAccumulator(
                BaselineBoundaryPolicy.provisional(100_000));
        assertTrue(scan.addPage(Arrays.asList(gallery(104, 4), gallery(103, 3)), true));
        assertFalse(scan.addPage(Arrays.asList(gallery(101, 2), gallery(100, 1)), true));
        assertEquals(Arrays.asList(4L, 3L, 2L), scan.finish().newGids);
    }

    @Test public void emptyResultDoesNotInventUpdatesOrEraseResetFloor() {
        FeedBoundary floor = BaselineBoundaryPolicy.provisional(100_000);
        BookmarkScanAccumulator scan = new BookmarkScanAccumulator(floor);
        assertFalse(scan.addPage(Collections.emptyList(), false));
        BookmarkScanResult result = scan.finish();
        assertTrue(result.newGids.isEmpty());
        assertEquals(floor.time, LocalBaselineResetPolicy.newer(result.top, floor).time);
    }

    @Test public void globalBootstrapAndFallbackKeepResetFloor() {
        FeedBoundary floor = BaselineBoundaryPolicy.provisional(100_000);
        Map<String, FeedCheckpoint> items = new LinkedHashMap<>();
        items.put("old", new FeedCheckpoint(FeedBoundary.EMPTY,
                LocalBaselineResetPolicy.newer(boundary(1, 1), floor), 10));
        items.put("uninitialized", new FeedCheckpoint(FeedBoundary.EMPTY,
                LocalBaselineResetPolicy.newer(FeedBoundary.EMPTY, floor), 0));
        FeedBoundary cursor = LocalGlobalCursorStore.oldest(items);
        assertEquals(101, cursor.time);
        assertFalse(GlobalScanPolicy.requiresItemBridge(items.get("old").current, cursor));
        assertTrue(GlobalScanPolicy.hasPassedCursor(cursor, Arrays.asList(gallery(100, 1))));
        FeedBoundary top = GlobalScanPolicy.fallbackCommitBoundary(boundary(90, 2), boundary(100, 1));
        assertEquals(101, LocalBaselineResetPolicy.newer(top, floor).time);
    }

    @Test public void stoppedJobRequirementIncludesPausedAndRecoveredJobs() {
        assertTrue(LocalBaselineResetPolicy.requiresStoppedJob("RUNNING"));
        assertTrue(LocalBaselineResetPolicy.requiresStoppedJob("PAUSED"));
        for (String status : Arrays.asList(null, "SUCCESS", "FAILED", "CANCELLED")) {
            assertFalse(LocalBaselineResetPolicy.requiresStoppedJob(status));
        }
    }

    private static FeedBoundary boundary(long time, long gid) {
        return new FeedBoundary(time, Collections.singleton(gid));
    }
    private static GalleryInfo gallery(long time, long gid) {
        GalleryInfo info = new GalleryInfo();
        info.postedTimestamp = time;
        info.gid = gid;
        return info;
    }
}
