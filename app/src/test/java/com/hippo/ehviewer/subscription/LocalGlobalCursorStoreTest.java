package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import org.junit.Test;

public class LocalGlobalCursorStoreTest {
    @Test public void readQueryQuotesSqliteCurrentTimeKeyword() {
        assertTrue(LocalGlobalCursorStore.READ_SQL.contains("\"CURRENT_TIME\""));
        assertFalse(LocalGlobalCursorStore.READ_SQL.contains("SELECT CURRENT_TIME,"));
    }

    @Test public void bootstrapUsesOldestItemBoundaryAndMergesSameSecondGids() {
        Map<String, FeedCheckpoint> checkpoints = new LinkedHashMap<>();
        checkpoints.put("new", checkpoint(200, 20L));
        checkpoints.put("old-a", checkpoint(100, 1L, 2L));
        checkpoints.put("old-b", checkpoint(100, 3L));

        FeedBoundary result = LocalGlobalCursorStore.oldest(checkpoints);

        assertEquals(100, result.time);
        assertEquals(new LinkedHashSet<>(Arrays.asList(1L, 2L, 3L)), result.gids);
    }

    @Test public void bootstrapIgnoresEmptyItemBoundaries() {
        Map<String, FeedCheckpoint> checkpoints = new LinkedHashMap<>();
        checkpoints.put("empty", new FeedCheckpoint(
                FeedBoundary.EMPTY, FeedBoundary.EMPTY, 0));
        assertTrue(LocalGlobalCursorStore.oldest(checkpoints).isEmpty());
    }

    @Test public void boundaryConvertsUnixSecondsForUiFormatting() {
        assertEquals(1_750_000_000_000L,
                new FeedBoundary(1_750_000_000L, new LinkedHashSet<>()).timeMillis());
        assertEquals(0, FeedBoundary.EMPTY.timeMillis());
    }

    private static FeedCheckpoint checkpoint(long time, Long... gids) {
        return new FeedCheckpoint(FeedBoundary.EMPTY,
                new FeedBoundary(time, new LinkedHashSet<>(Arrays.asList(gids))), time);
    }
}
