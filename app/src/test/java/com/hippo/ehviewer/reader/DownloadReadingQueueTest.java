package com.hippo.ehviewer.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DownloadReadingQueueTest {

    @Test
    public void snapshotPreservesCurrentListOrder() {
        assertArrayEquals(new long[]{30L, 10L, 20L}, DownloadReadingQueue.snapshot(
                Arrays.asList(info(30L), info(10L), info(20L))));
    }

    @Test
    public void queueFindsCurrentAndNextEntries() {
        long[] queue = {30L, 10L, 20L};
        assertEquals(1, DownloadReadingQueue.indexOf(queue, 10L));
        assertEquals(2, DownloadReadingQueue.nextIndex(queue, 1));
        assertEquals(-1, DownloadReadingQueue.nextIndex(queue, 2));
        assertEquals(-1, DownloadReadingQueue.indexOf(queue, 99L));
    }

    @Test
    public void emptyQueueIsSafe() {
        assertArrayEquals(new long[0], DownloadReadingQueue.snapshot(Collections.emptyList()));
        assertEquals(-1, DownloadReadingQueue.nextIndex(null, 0));
    }

    @Test
    public void unavailableAndFailingEntriesAreSkipped() {
        long[] queue = {10L, 20L, 30L, 40L};
        assertEquals(3, DownloadReadingQueue.findNextAvailableIndex(queue, 0, gid -> {
            if (gid == 20L) {
                throw new IllegalStateException("missing archive permission");
            }
            return gid == 40L;
        }));
    }

    @Test
    public void exhaustedQueueStopsAtEnd() {
        long[] queue = {10L, 20L, 30L};
        assertEquals(-1, DownloadReadingQueue.findNextAvailableIndex(
                queue, 0, gid -> false));
        assertEquals(-1, DownloadReadingQueue.findNextAvailableIndex(
                queue, 2, gid -> true));
    }

    private static GalleryInfo info(long gid) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        return info;
    }

    @Test
    public void candidateSnapshotIsDetachedFromDeletedOrChangedManagerEntries() {
        DownloadInfo source = new DownloadInfo(2);
        source.title = "before";
        source.token = "token";
        source.archiveUri = "content://archive";
        Map<Long, DownloadInfo> live = new HashMap<>();
        live.put(2L, source);
        List<Long> lookedUp = new ArrayList<>();
        Map<Long, DownloadInfo> snapshot =
                DownloadReadingQueue.snapshotCandidates(new long[]{1, 2, 3}, 0, gid -> {
                    lookedUp.add(gid);
                    return live.get(gid);
                });
        assertEquals(Arrays.asList(2L, 3L), lookedUp);
        source.title = "after";
        source.archiveUri = null;
        live.remove(2L);
        assertEquals(1, snapshot.size());
        assertNotSame(source, snapshot.get(2L));
        assertEquals("before", snapshot.get(2L).title);
        assertEquals("token", snapshot.get(2L).token);
        assertEquals("content://archive", snapshot.get(2L).archiveUri);
        assertNull(live.get(2L)); // Main-thread recheck rejects this candidate.
    }

    @Test
    public void candidateSnapshotSkipsCurrentAndHandlesMissingAndDuplicateGids() {
        DownloadInfo info = new DownloadInfo(2);
        assertTrue(
                DownloadReadingQueue.snapshotCandidates(null, 0, gid -> info).isEmpty());
        assertTrue(
                DownloadReadingQueue.snapshotCandidates(new long[]{1}, 0, gid -> info).isEmpty());
        assertEquals(1, DownloadReadingQueue.snapshotCandidates(
                new long[]{1, 2, 2, 3}, 0, gid -> gid == 2 ? info : null).size());
    }
}
