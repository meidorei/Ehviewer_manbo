package com.hippo.ehviewer.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ReadingQueueSnapshotTest {
    @Test
    public void snapshotTracksMembershipAndProgress() {
        ReadingQueueSnapshot empty = ReadingQueueSnapshot.from(Collections.emptyList());
        assertEquals(0, empty.size());
        assertFalse(empty.contains(10L));

        ReadingQueueSnapshot initial = ReadingQueueSnapshot.from(Arrays.asList(
                new ReadingQueueSnapshot.Entry(10L, 12, 50),
                new ReadingQueueSnapshot.Entry(20L, 0, 0),
                new ReadingQueueSnapshot.Entry(10L, 13, 50)));
        assertEquals(2, initial.size());
        assertEquals(13, initial.get(10L).currentPage);
        assertEquals(50, initial.get(10L).totalPages);
        assertTrue(initial.get(10L).isKnown());
        assertFalse(initial.get(20L).isKnown());
        assertNull(initial.get(30L));

        ReadingQueueSnapshot removed = ReadingQueueSnapshot.from(
                Collections.singletonList(new ReadingQueueSnapshot.Entry(20L, 0, 0)));
        assertFalse(removed.contains(10L));

        ReadingQueueSnapshot readded = ReadingQueueSnapshot.from(Arrays.asList(
                new ReadingQueueSnapshot.Entry(10L, 1, 50),
                new ReadingQueueSnapshot.Entry(20L, 0, 0)));
        assertTrue(readded.contains(10L));
    }

    @Test
    public void snapshotClampsInvalidProgress() {
        ReadingQueueSnapshot snapshot = ReadingQueueSnapshot.from(Arrays.asList(
                new ReadingQueueSnapshot.Entry(1L, 80, 50),
                new ReadingQueueSnapshot.Entry(2L, -1, 50),
                new ReadingQueueSnapshot.Entry(3L, 12, -1)));
        assertEquals(50, snapshot.get(1L).currentPage);
        assertEquals(0, snapshot.get(2L).currentPage);
        assertFalse(snapshot.get(2L).isKnown());
        assertFalse(snapshot.get(3L).isKnown());
    }
}
