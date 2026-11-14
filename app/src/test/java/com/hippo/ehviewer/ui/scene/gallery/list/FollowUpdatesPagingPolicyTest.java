package com.hippo.ehviewer.ui.scene.gallery.list;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FollowUpdatesPagingPolicyTest {
    @Test public void anchorInLookaheadRequiresFetchingEnoughDataForExpandedPage() {
        assertTrue(FollowUpdatesPagingPolicy.needsAnchorLookup(51, 50, 50, 51));
        assertEquals(100, FollowUpdatesPagingPolicy.limitIncludingAnchor(50, 50, 50, 1000));
        // The same boundary must work on later pages, not only the first 50 records.
        assertTrue(FollowUpdatesPagingPolicy.needsAnchorLookup(101, 100, 100, 101));
        assertEquals(150, FollowUpdatesPagingPolicy.limitIncludingAnchor(100, 100, 50, 1000));
    }

    @Test public void anchorPushedToSeventiethRowExpandsToTwoCompletePages() {
        assertTrue(FollowUpdatesPagingPolicy.needsAnchorLookup(70, -1, 50, 51));
        assertEquals(100, FollowUpdatesPagingPolicy.limitIncludingAnchor(50, 69, 50, 1000));
    }

    @Test public void visibleAnchorKeepsExistingLoadedPages() {
        assertFalse(FollowUpdatesPagingPolicy.needsAnchorLookup(30, 29, 50, 51));
        assertEquals(50, FollowUpdatesPagingPolicy.limitIncludingAnchor(50, 29, 50, 1000));
        assertEquals(150, FollowUpdatesPagingPolicy.limitIncludingAnchor(150, 29, 50, 1000));
    }

    @Test public void deletedAnchorDoesNotRevealEveryRemainingRecord() {
        assertTrue(FollowUpdatesPagingPolicy.needsAnchorLookup(30, -1, 50, 51));
        assertEquals(50, FollowUpdatesPagingPolicy.limitIncludingAnchor(50, -1, 50, 1000));
        assertFalse(FollowUpdatesPagingPolicy.needsAnchorLookup(30, -1, 50, 49));
    }

    @Test public void firstVisitUsesOnlyTheRequestedPageAndLookahead() {
        assertFalse(FollowUpdatesPagingPolicy.needsAnchorLookup(-1, -1, 50, 51));
        assertEquals(50, FollowUpdatesPagingPolicy.limitIncludingAnchor(50, -1, 50, 1000));
    }

    @Test public void lastRetainedRecordNeverExpandsPastCapacity() {
        assertTrue(FollowUpdatesPagingPolicy.needsAnchorLookup(1000, -1, 50, 51));
        assertEquals(1000, FollowUpdatesPagingPolicy.limitIncludingAnchor(50, 999, 50, 1000));
        assertFalse(FollowUpdatesPagingPolicy.needsAnchorLookup(1000, 999, 1000, 1000));
    }
}
