package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.GalleryInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class BaselineBoundaryPolicyTest {
    private static GalleryInfo gallery(long gid, long time) {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = gid;
        gallery.postedTimestamp = time;
        return gallery;
    }

    @Test public void provisionalBoundaryExcludesInsertionSecondOnly() {
        FeedBoundary boundary = BaselineBoundaryPolicy.provisional(10_900L);
        assertEquals(11L, boundary.time);
        assertFalse(boundary.isNew(10L, 1L));
        assertTrue(boundary.isNew(11L, 2L));
    }

    @Test public void refinementKeepsAllSameSecondGids() {
        FeedBoundary boundary = BaselineBoundaryPolicy.newestAtOrBefore(Arrays.asList(
                gallery(9L, 102L), gallery(7L, 100L), gallery(8L, 100L),
                gallery(6L, 99L)), 100_500L);
        assertEquals(100L, boundary.time);
        assertEquals(2, boundary.gids.size());
        assertTrue(boundary.gids.contains(7L));
        assertTrue(boundary.gids.contains(8L));
    }

    @Test public void queuedResultsNewerThanInsertionAreNotSwallowed() {
        FeedBoundary boundary = BaselineBoundaryPolicy.newestAtOrBefore(Arrays.asList(
                gallery(3L, 103L), gallery(2L, 102L)), 100_000L);
        assertTrue(boundary.isEmpty());
    }

    @Test public void timestampGroupAtPageEdgeUsesSafeFallback() {
        FeedBoundary boundary = BaselineBoundaryPolicy.newestAtOrBefore(Arrays.asList(
                gallery(3L, 103L), gallery(7L, 100L), gallery(8L, 100L)), 100_000L);
        assertTrue(boundary.isEmpty());
    }

    @Test public void emptyPageKeepsFallbackBoundary() {
        assertTrue(BaselineBoundaryPolicy.newestAtOrBefore(
                Collections.emptyList(), 100_000L).isEmpty());
    }
}
