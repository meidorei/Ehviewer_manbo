package com.hippo.ehviewer.subscription;

import static org.junit.Assert.*;

import com.hippo.ehviewer.client.data.GalleryInfo;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

public class FeedMarkerPolicyTest {
    @Test public void exactTimesSeparateBothSidesOfBoundaryWithinOneMinute() {
        FeedBoundary boundary = boundary(130, FeedBoundary.Precision.SECOND, 10L);
        assertEquals(FeedMarkerPolicy.Relation.OLD, FeedMarkerPolicy.classify(boundary, exact(9, 125)));
        assertEquals(FeedMarkerPolicy.Relation.NEW, FeedMarkerPolicy.classify(boundary, exact(11, 165)));
        assertEquals(FeedMarkerPolicy.Relation.OLD, FeedMarkerPolicy.classify(boundary, exact(10, 130)));
        assertEquals(FeedMarkerPolicy.Relation.NEW, FeedMarkerPolicy.classify(boundary, exact(12, 130)));
    }

    @Test public void minutePeerIsUncertainAndKnownAnchorStaysOld() {
        FeedBoundary boundary = boundary(130, FeedBoundary.Precision.SECOND, 10L);
        assertEquals(FeedMarkerPolicy.Relation.UNCERTAIN,
                FeedMarkerPolicy.classify(boundary, minute(11, 120)));
        assertEquals(FeedMarkerPolicy.Relation.OLD,
                FeedMarkerPolicy.classify(boundary, minute(10, 120)));
        assertEquals(FeedMarkerPolicy.Relation.OLD,
                FeedMarkerPolicy.classify(boundary, minute(9, 60)));
        assertEquals(FeedMarkerPolicy.Relation.NEW,
                FeedMarkerPolicy.classify(boundary, minute(12, 180)));
        assertFalse(FeedMarkerPolicy.isMarkerBefore(boundary, exact(10, 130), minute(11, 120), false));
    }

    @Test public void exactSecondZeroIsNotTreatedAsTruncatedMinute() {
        FeedBoundary boundary = boundary(120, FeedBoundary.Precision.SECOND, 10L);
        assertEquals(FeedMarkerPolicy.Relation.NEW,
                FeedMarkerPolicy.classify(boundary, exact(11, 121)));
        assertEquals(FeedMarkerPolicy.Relation.NEW,
                FeedMarkerPolicy.classify(boundary, exact(12, 120)));
    }

    @Test public void legacyMinuteDoesNotPromoteItsKnownGidsToNew() {
        FeedBoundary legacy = boundary(120, FeedBoundary.Precision.UNKNOWN, 10L, 11L);
        assertEquals(FeedMarkerPolicy.Relation.OLD,
                FeedMarkerPolicy.classify(legacy, exact(10, 130)));
        assertEquals(FeedMarkerPolicy.Relation.OLD,
                FeedMarkerPolicy.classify(legacy, exact(11, 170)));
        assertEquals(FeedMarkerPolicy.Relation.UNCERTAIN,
                FeedMarkerPolicy.classify(legacy, exact(12, 150)));
    }

    @Test public void legacyRefinementRequiresEverySavedAnchorAndUsesItsRealTop() {
        FeedBoundary legacy = boundary(120, FeedBoundary.Precision.UNKNOWN, 10L, 11L);
        assertSame(legacy, FeedMarkerPolicy.resolveLegacyBoundary(legacy,
                Arrays.asList(exact(12, 175), exact(10, 130))));
        FeedBoundary refined = FeedMarkerPolicy.resolveLegacyBoundary(legacy,
                Arrays.asList(exact(12, 175), exact(11, 170), exact(10, 130)));
        assertEquals(170, refined.time);
        assertEquals(Set.of(11L), refined.gids);
        assertEquals(FeedBoundary.Precision.SECOND, refined.precision);
        assertEquals(FeedMarkerPolicy.Relation.OLD,
                FeedMarkerPolicy.classify(refined, exact(13, 150)));
        assertEquals(FeedMarkerPolicy.Relation.NEW,
                FeedMarkerPolicy.classify(refined, exact(12, 175)));
        assertEquals(120, legacy.time);
    }

    @Test public void changedOrImpreciseLegacyAnchorCannotRefineBoundary() {
        FeedBoundary legacy = boundary(120, FeedBoundary.Precision.UNKNOWN, 10L);
        assertSame(legacy, FeedMarkerPolicy.resolveLegacyBoundary(legacy,
                Collections.singletonList(exact(10, 180))));
        assertSame(legacy, FeedMarkerPolicy.resolveLegacyBoundary(legacy,
                Collections.singletonList(minute(10, 120))));
    }

    @Test public void reliableTopRequiresEntireTopMinuteAndPreservesTopSecondGroup() {
        assertTrue(FeedMarkerPolicy.reliableTop(Arrays.asList(exact(1, 130), minute(2, 120))).isEmpty());
        FeedBoundary top = FeedMarkerPolicy.reliableTop(
                Arrays.asList(exact(1, 130), exact(2, 130), exact(3, 125), minute(4, 60)));
        assertEquals(130, top.time);
        assertEquals(Set.of(1L, 2L), top.gids);
        assertEquals(FeedBoundary.Precision.SECOND, top.precision);
        assertTrue(FeedMarkerPolicy.reliableTop(Arrays.asList(exact(1, 130), minute(2, 0))).isEmpty());
    }

    @Test public void homeHistoryPageStillNeedsAnchorOrActualCrossing() {
        FeedBoundary boundary = boundary(130, FeedBoundary.Precision.SECOND, 10L);
        assertFalse(FeedMarkerPolicy.isMarkerBefore(boundary, minute(9, 60), null, true));
        assertTrue(FeedMarkerPolicy.isMarkerBefore(boundary, exact(10, 130), null, true));
        assertTrue(FeedMarkerPolicy.isMarkerBefore(boundary, exact(10, 130), exact(11, 150), true));
        assertFalse(FeedMarkerPolicy.isMarkerBefore(boundary, exact(9, 125), minute(11, 120), true));
    }

    @Test public void artistSnapshotDoesNotDependOnTimestampPrecision() {
        ArtistUnreadSnapshot snapshot = new ArtistUnreadSnapshot(7, Set.of(10L));
        assertEquals(1, snapshot.lastMatch(Arrays.asList(minute(11, 0), minute(10, 0))));
        assertEquals(1, snapshot.lastMatch(Arrays.asList(exact(11, 170), exact(10, 130))));
    }

    private static FeedBoundary boundary(long time, FeedBoundary.Precision precision, Long... gids) {
        return new FeedBoundary(time, Set.of(gids), precision);
    }

    private static GalleryInfo exact(long gid, long time) { return gallery(gid, time, FeedBoundary.Precision.SECOND); }
    private static GalleryInfo minute(long gid, long time) { return gallery(gid, time, FeedBoundary.Precision.MINUTE); }
    private static GalleryInfo gallery(long gid, long time, FeedBoundary.Precision precision) {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = gid;
        gallery.postedTimestamp = time;
        gallery.postedTimestampPrecision = precision;
        return gallery;
    }
}
