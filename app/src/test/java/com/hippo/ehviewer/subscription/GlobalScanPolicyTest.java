package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class GlobalScanPolicyTest {
    @Test public void lastTagPageProvesAnExactCountEvenIfTheOldGalleryDisappeared() {
        FeedBoundary old = new FeedBoundary(100, Collections.singleton(1L));
        assertTrue(GlobalScanPolicy.coversCursor(old, Collections.singletonList(gallery(150, 2)), false));
        assertFalse(GlobalScanPolicy.coversCursor(old, Collections.singletonList(gallery(150, 2)), true));
    }
    @Test public void sameSecondDoesNotFinishUntilAnOlderGalleryAppears() {
        FeedBoundary cursor = new FeedBoundary(100,
                Collections.singleton(1L));
        assertFalse(GlobalScanPolicy.hasPassedCursor(cursor,
                Arrays.asList(gallery(101, 3), gallery(100, 2), gallery(100, 1))));
        assertTrue(GlobalScanPolicy.hasPassedCursor(cursor,
                Arrays.asList(gallery(100, 2), gallery(100, 1), gallery(99, 9))));
    }

    @Test public void olderItemCursorRequiresOneTimeBridge() {
        FeedBoundary shared = new FeedBoundary(200, Collections.singleton(2L));
        assertTrue(GlobalScanPolicy.requiresItemBridge(
                new FeedBoundary(199, Collections.singleton(1L)), shared));
        assertFalse(GlobalScanPolicy.requiresItemBridge(
                new FeedBoundary(200, Collections.singleton(1L)), shared));
        assertFalse(GlobalScanPolicy.requiresItemBridge(
                new FeedBoundary(201, Collections.singleton(1L)), shared));
        assertFalse(GlobalScanPolicy.requiresItemBridge(FeedBoundary.EMPTY, shared));
        assertFalse(GlobalScanPolicy.requiresItemBridge(shared, FeedBoundary.EMPTY));
    }

    @Test public void globalFallbackCommitsAtSharedTopAndDoesNotBridgeAgain() {
        FeedBoundary itemTop = new FeedBoundary(150, Collections.singleton(1L));
        FeedBoundary sharedTop = new FeedBoundary(200, Collections.singleton(2L));

        FeedBoundary committed =
                GlobalScanPolicy.fallbackCommitBoundary(itemTop, sharedTop);

        assertSame(sharedTop, committed);
        assertFalse(GlobalScanPolicy.requiresItemBridge(committed, sharedTop));
        assertSame(itemTop, GlobalScanPolicy.fallbackCommitBoundary(
                itemTop, FeedBoundary.EMPTY));
    }

    @Test public void emptyFallbackKeepsSharedCoverageAndHostResetUsesOnlyTheQueryTop() {
        FeedBoundary sharedTop = new FeedBoundary(200, Collections.singleton(2L));
        FeedBoundary queryTop = new FeedBoundary(150, Collections.singleton(1L));
        assertSame(sharedTop, GlobalScanPolicy.fallbackCommitBoundary(FeedBoundary.EMPTY, sharedTop));
        assertSame(queryTop, GlobalScanPolicy.fallbackCommitBoundary(queryTop, null));
        assertTrue(GlobalScanPolicy.fallbackCommitBoundary(FeedBoundary.EMPTY, null).isEmpty());
    }

    private static GalleryInfo gallery(long time, long gid) {
        GalleryInfo gallery = new GalleryInfo();
        gallery.postedTimestamp = time;
        gallery.gid = gid;
        return gallery;
    }
}
