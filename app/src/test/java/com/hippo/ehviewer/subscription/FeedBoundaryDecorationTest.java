package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeedBoundaryDecorationTest {
    @Test
    public void markerStyleUsesEmphasizedDimensionsWithoutChangingSpacing() {
        assertEquals(4f, FeedBoundaryDecoration.lineWidthPx(2f), 0f);
        assertEquals(42f, FeedBoundaryDecoration.textSizePx(3f), 0f);
        assertEquals(64, FeedBoundaryDecoration.markerHeightPx(2f));
    }

    @Test
    public void markerLongPressUsesTheFullReservedHeight() {
        assertTrue(FeedBoundaryDecoration.isWithinMarkerTouchBounds(68f, 100, 32));
        assertTrue(FeedBoundaryDecoration.isWithinMarkerTouchBounds(99.9f, 100, 32));
        assertFalse(FeedBoundaryDecoration.isWithinMarkerTouchBounds(67.9f, 100, 32));
        assertFalse(FeedBoundaryDecoration.isWithinMarkerTouchBounds(100f, 100, 32));
    }
}
