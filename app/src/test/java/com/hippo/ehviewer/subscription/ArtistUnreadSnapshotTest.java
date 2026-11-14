package com.hippo.ehviewer.subscription;

import static org.junit.Assert.*;

import com.hippo.ehviewer.client.data.GalleryInfo;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ArtistUnreadSnapshotTest {
    private static GalleryInfo gallery(long gid) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        return info;
    }

    @Test public void singleUnreadAtTopAlsoWorksWhenItIsTheOnlyCard() {
        ArtistUnreadSnapshot snapshot = new ArtistUnreadSnapshot(8, Set.of(42L));
        assertEquals(0, snapshot.lastMatch(List.of(gallery(42), gallery(11))));
        assertEquals(0, snapshot.lastMatch(List.of(gallery(42))));
    }

    @Test public void markerFollowsLastLoadedMatchAcrossPagesAndGaps() {
        ArtistUnreadSnapshot snapshot = new ArtistUnreadSnapshot(9, Set.of(42L, 11L));
        assertEquals(-1, snapshot.lastMatch(List.of(gallery(50))));
        assertEquals(1, snapshot.lastMatch(List.of(gallery(50), gallery(42))));
        assertEquals(3, snapshot.lastMatch(List.of(gallery(50), gallery(42), gallery(17), gallery(11))));
        assertEquals(0, snapshot.lastMatch(List.of(gallery(11), gallery(7))));
    }

    @Test public void clearedOrRestoredBatchKeepsEntryIds() {
        Set<Long> entry = new HashSet<>(Set.of(42L));
        ArtistUnreadSnapshot snapshot = new ArtistUnreadSnapshot(8, entry);
        entry.clear();
        ArtistUnreadSnapshot restored = new ArtistUnreadSnapshot(snapshot.lastRowId(),
                SubscriptionRepository.parseGids(SubscriptionRepository.serializeGids(snapshot.gids())));
        assertEquals(snapshot, restored);
        assertTrue(restored.usesUnreadMarker());
        assertEquals(0, restored.lastMatch(List.of(gallery(42))));
        ArtistUnreadSnapshot empty = new ArtistUnreadSnapshot(0, entry);
        assertFalse(empty.usesUnreadMarker());
        assertEquals(-1, empty.lastMatch(List.of(gallery(42))));
    }

    @Test public void emptyBatchRetainsSavedTimeBoundaryAcrossRestore() {
        ArtistUnreadSnapshot empty = new ArtistUnreadSnapshot(0, Set.of());
        FeedBoundary reset = BaselineBoundaryPolicy.provisional(130_999);
        FeedBoundary restored = FeedBoundaryCodec.decode(reset.time, FeedBoundaryCodec.encode(reset));
        assertSame(restored, empty.displayBoundary(restored));
        GalleryInfo old = gallery(10);
        old.postedTimestamp = 130;
        old.postedTimestampPrecision = FeedBoundary.Precision.SECOND;
        assertTrue(FeedMarkerPolicy.isMarkerBefore(empty.displayBoundary(restored), old, null, false));
        // First-entry pages without a saved boundary must stay empty when restored.
        assertTrue(empty.displayBoundary(FeedBoundary.EMPTY).isEmpty());
        // An entry with unread IDs must never draw a second, time-based marker.
        ArtistUnreadSnapshot unread = new ArtistUnreadSnapshot(7, Set.of(10L));
        assertTrue(unread.displayBoundary(restored).isEmpty());
        assertEquals(0, unread.lastMatch(List.of(old)));
    }

    @Test public void onlyArtistFollowsUseIdMarkers() {
        assertTrue(new FeedSourceContext(FeedSourceContext.Type.SUBSCRIPTION_TAG, "artist:a", "").isFollowedArtist());
        assertFalse(new FeedSourceContext(FeedSourceContext.Type.SUBSCRIPTION_TAG, "group:a", "").isFollowedArtist());
        assertFalse(new FeedSourceContext(FeedSourceContext.Type.QUICK_SEARCH, "artist:a", "").isFollowedArtist());
        assertFalse(new FeedSourceContext(FeedSourceContext.Type.HOME, "home", "").isFollowedArtist());
    }
}
