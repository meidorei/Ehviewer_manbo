package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.GalleryInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class SubscriptionUpdateCalculatorTest {
    private static GalleryInfo gallery(long gid, long time, String... tags) {
        GalleryInfo value = new GalleryInfo();
        value.gid = gid;
        value.postedTimestamp = time;
        value.simpleTags = tags;
        return value;
    }

    @Test public void firstRunCreatesZeroBaseline() {
        SubscriptionUpdateCalculator.Outcome result = SubscriptionUpdateCalculator.calculate(
                FeedBoundary.EMPTY, Arrays.asList(gallery(2, 20, "artist:a"), gallery(1, 19, "artist:a")),
                Collections.singleton("artist:a"));
        assertTrue(result.complete);
        assertTrue(result.baselineOnly);
        assertEquals("0", result.states.get("artist:a").displayCount());
    }

    @Test public void sameSecondBoundaryAndMultipleTagsAreCounted() {
        FeedBoundary old = new FeedBoundary(10, Collections.singleton(1L));
        SubscriptionUpdateCalculator.Outcome result = SubscriptionUpdateCalculator.calculate(old,
                Arrays.asList(gallery(3, 11, "artist:a", "group:b"),
                        gallery(2, 10, "artist:a"), gallery(1, 10, "artist:a")),
                new HashSet<>(Arrays.asList("artist:a", "group:b")));
        assertTrue(result.complete);
        assertEquals(2, result.states.get("artist:a").count);
        assertEquals(1, result.states.get("group:b").count);
    }

    @Test public void watchedCountsUnknownGalleryAfterKnownOneInSameSecond() {
        FeedBoundary old = new FeedBoundary(10, Collections.singleton(1L));
        SubscriptionUpdateCalculator.Outcome outcome = SubscriptionUpdateCalculator.calculate(old,
                Arrays.asList(gallery(1, 10, "artist:a"), gallery(2, 10, "artist:a")),
                Collections.singleton("artist:a"), true);
        assertTrue(outcome.complete);
        assertEquals(1, outcome.states.get("artist:a").count);
        assertEquals(new HashSet<>(Arrays.asList(1L, 2L)), outcome.newBoundary.gids);
    }

    @Test public void exhaustedWatchedListCoversRemovedOldGallery() {
        SubscriptionUpdateCalculator.Outcome outcome = SubscriptionUpdateCalculator.calculate(
                new FeedBoundary(10, Collections.singleton(1L)),
                Collections.singletonList(gallery(2, 15, "artist:a")), Collections.singleton("artist:a"), true);
        assertTrue(outcome.complete);
        assertEquals(1, outcome.states.get("artist:a").count);
    }

    @Test public void incompleteGdataNeverCommits() {
        GalleryInfo invalid = gallery(3, 0, "artist:a");
        SubscriptionUpdateCalculator.Outcome result = SubscriptionUpdateCalculator.calculate(
                new FeedBoundary(10, Collections.singleton(1L)), Collections.singletonList(invalid),
                Collections.singleton("artist:a"));
        assertFalse(result.complete);
    }

    @Test public void unreadCountsAccumulateUntilViewed() {
        Map<String, TagUpdateState> old = new HashMap<>();
        old.put("artist:a", new TagUpdateState("artist:a", 3,
                TagUpdateState.State.EXACT, 10));
        Map<String, TagUpdateState> delta = new HashMap<>();
        delta.put("artist:a", new TagUpdateState("artist:a", 2,
                TagUpdateState.State.EXACT, 20));
        assertEquals(5, SubscriptionUpdateCalculator.mergeUnread(old, delta, false)
                .get("artist:a").count);
        assertEquals(0, SubscriptionUpdateCalculator.mergeUnread(old, delta, true)
                .get("artist:a").count);
    }

    @Test public void unreadCountsBecomeCappedLowerBound() {
        Map<String, TagUpdateState> old = new HashMap<>();
        old.put("artist:a", new TagUpdateState("artist:a", 18,
                TagUpdateState.State.EXACT, 10));
        Map<String, TagUpdateState> delta = new HashMap<>();
        delta.put("artist:a", new TagUpdateState("artist:a", 5,
                TagUpdateState.State.EXACT, 20));
        TagUpdateState merged = SubscriptionUpdateCalculator.mergeUnread(old, delta, false)
                .get("artist:a");
        assertEquals(20, merged.count);
        assertEquals(TagUpdateState.State.LOWER_BOUND, merged.state);
        assertEquals("20+", merged.displayCount());
    }

    @Test public void missingBoundaryNeverCommitsWithoutPageLimitShortcut() {
        SubscriptionUpdateCalculator.Outcome result = SubscriptionUpdateCalculator.calculate(
                new FeedBoundary(10, Collections.singleton(1L)),
                Collections.singletonList(gallery(3, 11, "artist:a")),
                Collections.singleton("artist:a"));
        assertFalse(result.complete);
    }

    @Test public void nonDescendingFeedNeverCommits() {
        SubscriptionUpdateCalculator.Outcome result = SubscriptionUpdateCalculator.calculate(
                new FeedBoundary(10, Collections.singleton(1L)),
                Arrays.asList(gallery(2, 12, "artist:a"), gallery(3, 13, "artist:a")),
                Collections.singleton("artist:a"));
        assertFalse(result.complete);
    }
}
