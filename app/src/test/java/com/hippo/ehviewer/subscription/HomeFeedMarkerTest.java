package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

public class HomeFeedMarkerTest {
    private final FeedBoundary boundary = new FeedBoundary(100, Collections.singleton(10L));

    private int markerIndex(long[][] items) {
        int found = -1;
        for (int i = 0; i < items.length; i++) {
            if (boundary.isHomeMarkerBefore(items[i][0], items[i][1],
                    i == 0 ? 0 : items[i - 1][0], i == 0 ? 0 : items[i - 1][1])) {
                assertEquals("Only one marker in a chronological loaded range", -1, found);
                found = i;
            }
        }
        return found;
    }

    @Test public void jumpingToHistoryAndPrependingOlderPagesDoesNotInventMarker() {
        assertEquals(-1, markerIndex(new long[][]{{50, 5}, {40, 4}}));
        assertEquals(-1, markerIndex(new long[][]{{70, 7}, {60, 6}, {50, 5}, {40, 4}}));
        assertEquals(-1, markerIndex(new long[][]{{90, 9}, {80, 8}, {70, 7}, {60, 6}}));
    }

    @Test public void reachingSavedAnchorThenPrependingKeepsMarkerAtSameGallery() {
        assertEquals(0, markerIndex(new long[][]{{100, 10}, {90, 9}, {80, 8}}));
        assertEquals(1, markerIndex(new long[][]{{110, 11}, {100, 10}, {90, 9}, {80, 8}}));
        assertEquals(2, markerIndex(new long[][]{{120, 12}, {110, 11}, {100, 10}, {90, 9}}));
    }

    @Test public void filteredAnchorStillAllowsActualNewToOldCrossing() {
        assertEquals(1, markerIndex(new long[][]{{110, 11}, {90, 9}, {80, 8}}));
        assertEquals(-1, markerIndex(new long[][]{{90, 9}, {80, 8}}));
    }

    @Test public void newerOnlyPagesDoNotShowMarker() {
        assertEquals(-1, markerIndex(new long[][]{{120, 12}, {110, 11}}));
    }

    @Test public void sameSecondUnseenGalleryRemainsAboveSavedAnchor() {
        assertEquals(1, markerIndex(new long[][]{{100, 99}, {100, 10}, {90, 9}}));
        assertEquals(-1, markerIndex(new long[][]{{100, 99}}));
    }

    @Test public void unknownTimestampDoesNotProveCrossingIntoHistory() {
        assertFalse(boundary.isHomeMarkerBefore(90, 9, 0, 11));
        assertFalse(boundary.isHomeMarkerBefore(0, 9, 110, 11));
        assertTrue(boundary.isHomeMarkerBefore(100, 10, 0, 0));
    }

    @Test public void unsetManualBoundaryNeverShowsMarker() {
        assertFalse(FeedBoundary.EMPTY.isHomeMarkerBefore(90, 9, 110, 11));
        assertFalse(FeedBoundary.EMPTY.isHomeMarkerBefore(90, 9, 0, 0));
    }
}
