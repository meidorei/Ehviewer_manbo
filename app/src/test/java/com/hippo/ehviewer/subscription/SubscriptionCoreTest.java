package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.GalleryInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

public class SubscriptionCoreTest {
    @Test public void gidSerializationIsStableAndUnique() {
        HashSet<Long> gids = new HashSet<>(Arrays.asList(9L, 2L, 9L, 5L));
        assertEquals("2,5,9", SubscriptionRepository.serializeGids(gids));
        assertEquals(gids, SubscriptionRepository.parseGids("9,2,5,2"));
    }

    @Test public void boundaryHandlesSameSecondGidsAndUnknownTime() {
        FeedBoundary boundary = new FeedBoundary(100, new HashSet<>(Arrays.asList(1L, 2L)));
        assertTrue(boundary.isNew(101, 1));
        assertTrue(boundary.isNew(100, 3));
        assertFalse(boundary.isNew(100, 2));
        assertTrue(boundary.isFirstOld(99, 8));
        assertFalse(boundary.isNew(0, 8));
    }

    @Test public void querySignatureSeparatesActuallyAppliedPolicy() {
        assertNotEquals(QuerySignatureFactory.create("foo", false),
                QuerySignatureFactory.create("foo", true));
        assertEquals(QuerySignatureFactory.create("foo", true),
                QuerySignatureFactory.create("foo", true));
    }

    @Test public void syncAggregateAndPerTagSeenCheckpointsNeverCollide() {
        FeedSourceContext aggregate = new FeedSourceContext(
                FeedSourceContext.Type.SUBSCRIPTION_AGGREGATE, "watched", "q");
        FeedSourceContext tag = new FeedSourceContext(
                FeedSourceContext.Type.SUBSCRIPTION_TAG, " Artist:A ", "q");
        CheckpointKey sync = FeedCheckpointKeys.subscriptionSync("account", aggregate);
        CheckpointKey aggregateSeen = FeedCheckpointKeys.seen("account", aggregate);
        CheckpointKey tagSeen = FeedCheckpointKeys.seen("account", tag);
        assertNotEquals(sync.sourceType, aggregateSeen.sourceType);
        assertNotEquals(sync.sourceType, tagSeen.sourceType);
        assertNotEquals(aggregateSeen.sourceType, tagSeen.sourceType);
        assertEquals("artist:a", tagSeen.sourceKey);
    }

    @Test public void updateMarkerOnlyShowsForUpdateSources() {
        assertTrue(new FeedSourceContext(
                FeedSourceContext.Type.HOME, "home", "").showsMarker());
        assertFalse(new FeedSourceContext(
                FeedSourceContext.Type.TEMP_SEARCH, "", "foo").showsMarker());
        assertTrue(new FeedSourceContext(
                FeedSourceContext.Type.SUBSCRIPTION_AGGREGATE, "watched", "").showsMarker());
        assertTrue(new FeedSourceContext(
                FeedSourceContext.Type.SUBSCRIPTION_TAG, "artist:a", "").showsMarker());
        assertTrue(new FeedSourceContext(
                FeedSourceContext.Type.QUICK_SEARCH, "42", "foo").showsMarker());
    }

    @Test public void homepageManualMarkerKeyIsDeviceWide() {
        CheckpointKey key = FeedCheckpointKeys.homeManual();
        assertEquals("shared", key.accountKey);
        assertEquals("HOME_MANUAL", key.sourceType);
        assertEquals("home", key.sourceKey);
        assertEquals("", key.querySignature);
    }

    @Test public void homepageManualBoundaryKeepsEveryNewestSameSecondGid() {
        GalleryInfo first = new GalleryInfo();
        first.gid = 10L;
        first.postedTimestamp = 200L;
        GalleryInfo second = new GalleryInfo();
        second.gid = 11L;
        second.postedTimestamp = 200L;
        GalleryInfo older = new GalleryInfo();
        older.gid = 12L;
        older.postedTimestamp = 199L;

        FeedBoundary boundary = LocalFollowRepository.boundaryOf(
                Arrays.asList(first, second, older));

        assertEquals(200L, boundary.time);
        assertEquals(new HashSet<>(Arrays.asList(10L, 11L)), boundary.gids);
        assertTrue(boundary.isFirstOld(200L, 10L));
        assertTrue(boundary.isFirstOld(200L, 11L));
        assertTrue(boundary.isNew(200L, 13L));
    }

    @Test public void localBadgeCapsAtTwentyPlus() {
        TagUpdateState state = new TagUpdateState("artist:a", 21,
                TagUpdateState.State.EXACT, 1);
        assertEquals(20, state.count);
        assertEquals(TagUpdateState.State.LOWER_BOUND, state.state);
        assertEquals("20+", state.displayCount());
    }

    @Test public void localTagNormalizationIsStable() {
        assertEquals("artist:foo bar",
                SubscriptionRepository.normalizeTagName("  Artist:Foo   Bar  "));
        assertTrue(LocalFollowJson.isValidStandardTag("artist:foo bar"));
        assertFalse(LocalFollowJson.isValidStandardTag("arbitrary search"));
    }
}
