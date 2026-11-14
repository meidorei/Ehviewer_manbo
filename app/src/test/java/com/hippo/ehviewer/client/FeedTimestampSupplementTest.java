package com.hippo.ehviewer.client;

import static org.junit.Assert.*;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.exception.CancelledException;
import com.hippo.ehviewer.subscription.FeedBoundary;
import com.hippo.ehviewer.subscription.FeedMarkerPolicy;
import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

public class FeedTimestampSupplementTest {
    @Test public void disabledAndAlreadyPrecisePagesDoNotRequestMetadata() throws Throwable {
        FeedTimestampSupplement.Loader unexpected = ignored -> { throw new AssertionError("unexpected request"); };
        assertNull(FeedTimestampSupplement.fill(null, List.of(minute(1, 120)), unexpected));
        FeedTimestampSupplement.Result result = FeedTimestampSupplement.fill(
                new FeedTimestampSupplement.Request(FeedBoundary.EMPTY, true), List.of(exact(1, 120)), unexpected);
        assertEquals(120, result.reliableTop().time);
        assertEquals(FeedBoundary.Precision.SECOND, result.reliableTop().precision);
    }

    @Test public void requestsOnlyTopAndBoundaryMinutesAndCopiesOnlyTime() throws Throwable {
        GalleryInfo top = minute(1, 180);
        GalleryInfo boundaryPeer = minute(2, 120);
        GalleryInfo old = minute(3, 60);
        GalleryInfo ready = exact(4, 180);
        top.title = "HTML title";
        top.posted = "HTML date";
        top.simpleTags = new String[]{"artist:html"};
        FeedBoundary boundary = new FeedBoundary(130, Set.of(5L));
        FeedTimestampSupplement.Result result = FeedTimestampSupplement.fill(
                new FeedTimestampSupplement.Request(boundary, true), Arrays.asList(top, ready, boundaryPeer, old), copies -> {
                    assertEquals(List.of(1L, 2L), copies.stream().map(g -> g.gid).collect(Collectors.toList()));
                    assertEquals("token1", copies.get(0).token);
                    fill(copies.get(0), 190);
                    fill(copies.get(1), 125);
                    copies.get(0).title = "API title";
                    copies.get(0).simpleTags = new String[]{"artist:api"};
                });
        assertEquals(190, result.reliableTop().time);
        assertEquals(Set.of(1L), result.reliableTop().gids);
        assertEquals("HTML title", top.title);
        assertEquals("HTML date", top.posted);
        assertArrayEquals(new String[]{"artist:html"}, top.simpleTags);
        assertEquals(FeedBoundary.Precision.MINUTE, old.postedTimestampPrecision);
        assertEquals(FeedMarkerPolicy.Relation.OLD, FeedMarkerPolicy.classify(boundary, boundaryPeer));
    }

    @Test public void apiFailureDoesNotPublishPartialMutationsOrReliableTop() throws Throwable {
        GalleryInfo top = minute(1, 120);
        FeedTimestampSupplement.Result result = FeedTimestampSupplement.fill(
                new FeedTimestampSupplement.Request(FeedBoundary.EMPTY, true), List.of(top), copies -> {
                    fill(copies.get(0), 130);
                    throw new IOException("failed after partial parse");
                });
        assertEquals(120, top.postedTimestamp);
        assertEquals(FeedBoundary.Precision.MINUTE, top.postedTimestampPrecision);
        assertTrue(result.reliableTop().isEmpty());
    }

    @Test public void missingTopMemberCannotMakePartiallyRefinedTopReliable() throws Throwable {
        GalleryInfo first = minute(1, 120);
        GalleryInfo missing = minute(2, 120);
        FeedTimestampSupplement.Result result = FeedTimestampSupplement.fill(
                new FeedTimestampSupplement.Request(FeedBoundary.EMPTY, true),
                List.of(first, missing), copies -> fill(copies.get(0), 130));
        assertEquals(130, first.postedTimestamp);
        assertEquals(FeedBoundary.Precision.SECOND, first.postedTimestampPrecision);
        assertEquals(120, missing.postedTimestamp);
        assertEquals(FeedBoundary.Precision.MINUTE, missing.postedTimestampPrecision);
        assertTrue(result.reliableTop().isEmpty());
    }

    @Test public void changedGalleryMinuteAndInvalidApiTimeAreRejected() throws Throwable {
        GalleryInfo first = minute(1, 120);
        GalleryInfo second = minute(2, 120);
        FeedTimestampSupplement.Result result = FeedTimestampSupplement.fill(
                new FeedTimestampSupplement.Request(FeedBoundary.EMPTY, true), List.of(first, second), copies -> {
                    fill(copies.get(0), 180);
                    fill(copies.get(1), 0);
                });
        assertTrue(result.reliableTop().isEmpty());
        assertEquals(120, first.postedTimestamp);
        assertEquals(120, second.postedTimestamp);
    }

    @Test public void completeLegacyAnchorsRefineDisplayOnly() throws Throwable {
        FeedBoundary legacy = new FeedBoundary(120, Set.of(1L, 2L), FeedBoundary.Precision.UNKNOWN);
        FeedTimestampSupplement.Result result = FeedTimestampSupplement.fill(
                new FeedTimestampSupplement.Request(legacy, false), List.of(minute(1, 120), minute(2, 120)), copies -> {
                    fill(copies.get(0), 130);
                    fill(copies.get(1), 150);
                });
        assertSame(legacy, result.requestedBoundary());
        assertEquals(120, legacy.time);
        assertEquals(150, result.displayBoundary().time);
        assertEquals(Set.of(2L), result.displayBoundary().gids);
        assertTrue(result.reliableTop().isEmpty());
    }

    @Test public void missingTokenLeavesTopUnreliableWithoutRequestingInvalidGallery() throws Throwable {
        GalleryInfo item = minute(1, 120);
        item.token = null;
        FeedTimestampSupplement.Result result = FeedTimestampSupplement.fill(
                new FeedTimestampSupplement.Request(FeedBoundary.EMPTY, true), Collections.singletonList(item),
                ignored -> { throw new AssertionError("invalid request"); });
        assertTrue(result.reliableTop().isEmpty());
    }

    @Test(expected = CancelledException.class) public void cancellationPropagates() throws Throwable {
        FeedTimestampSupplement.fill(new FeedTimestampSupplement.Request(FeedBoundary.EMPTY, true),
                List.of(minute(1, 120)), ignored -> { throw new CancelledException(); });
    }

    @Test(expected = InterruptedException.class) public void interruptionPropagates() throws Throwable {
        FeedTimestampSupplement.fill(new FeedTimestampSupplement.Request(FeedBoundary.EMPTY, true),
                List.of(minute(1, 120)), ignored -> { throw new InterruptedException(); });
    }

    @Test public void concurrentCancellationPropagatesWithoutPublishingPartialTimes() {
        GalleryInfo original = minute(1, 120);
        CancellationException cancelled = new CancellationException("cancelled after parsing one item");
        CancellationException actual = assertThrows(CancellationException.class, () ->
                FeedTimestampSupplement.fill(new FeedTimestampSupplement.Request(FeedBoundary.EMPTY, true),
                        List.of(original), copies -> {
                            fill(copies.get(0), 130);
                            throw cancelled;
                        }));
        assertSame(cancelled, actual);
        assertEquals(120, original.postedTimestamp);
        assertEquals(FeedBoundary.Precision.MINUTE, original.postedTimestampPrecision);
    }

    @Test(expected = AssertionError.class) public void fatalErrorsPropagate() throws Throwable {
        FeedTimestampSupplement.fill(new FeedTimestampSupplement.Request(FeedBoundary.EMPTY, true),
                List.of(minute(1, 120)), ignored -> { throw new AssertionError("fatal"); });
    }

    private static GalleryInfo minute(long gid, long time) {
        GalleryInfo result = new GalleryInfo();
        result.gid = gid;
        result.token = "token" + gid;
        result.postedTimestamp = time;
        result.postedTimestampPrecision = FeedBoundary.Precision.MINUTE;
        return result;
    }

    private static GalleryInfo exact(long gid, long time) {
        GalleryInfo result = minute(gid, time);
        fill(result, time);
        return result;
    }

    private static void fill(GalleryInfo item, long time) {
        item.postedTimestamp = time;
        item.postedTimestampPrecision = FeedBoundary.Precision.SECOND;
    }
}
