package com.hippo.ehviewer.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.dao.DownloadInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ReadingQueuePolicyTest {
    @Test
    public void capacityBoundsAreOneThroughOneHundred() {
        assertFalse(ReadingQueuePolicy.isValidCapacity(0));
        assertTrue(ReadingQueuePolicy.isValidCapacity(1));
        assertTrue(ReadingQueuePolicy.isValidCapacity(100));
        assertFalse(ReadingQueuePolicy.isValidCapacity(101));
    }

    @Test
    public void overflowCountUsesConfiguredCapacity() {
        assertEquals(0, ReadingQueuePolicy.overflowCount(20, 20));
        assertEquals(3, ReadingQueuePolicy.overflowCount(23, 20));
        assertEquals(0, ReadingQueuePolicy.overflowCount(200, 0));
    }

    @Test
    public void capacityInputAcceptsOnlyWholeNumbersInRange() {
        assertEquals(Integer.valueOf(1), ReadingQueuePolicy.parseCapacity(" 1 "));
        assertEquals(Integer.valueOf(100), ReadingQueuePolicy.parseCapacity("100"));
        assertEquals(null, ReadingQueuePolicy.parseCapacity(""));
        assertEquals(null, ReadingQueuePolicy.parseCapacity("0"));
        assertEquals(null, ReadingQueuePolicy.parseCapacity("101"));
        assertEquals(null, ReadingQueuePolicy.parseCapacity("1.5"));
    }

    @Test
    public void confirmationIsOnlyRequiredWhenEnablingOrLoweringWouldDelete() {
        assertEquals(3, ReadingQueuePolicy.confirmationDeletionCount(
                false, 20, true, 20, 23));
        assertEquals(5, ReadingQueuePolicy.confirmationDeletionCount(
                true, 20, true, 10, 15));
        assertEquals(0, ReadingQueuePolicy.confirmationDeletionCount(
                false, 20, false, 10, 50));
        assertEquals(0, ReadingQueuePolicy.confirmationDeletionCount(
                true, 10, true, 20, 50));
        assertEquals(0, ReadingQueuePolicy.confirmationDeletionCount(
                true, 20, true, 10, 10));
    }

    @Test
    public void oldestOverflowReturnsOldestFirst() {
        assertEquals(Arrays.asList(5L, 4L), ReadingQueuePolicy.oldestOverflow(
                Arrays.asList(1L, 2L, 3L, 4L, 5L), 3));
        assertEquals(Collections.emptyList(), ReadingQueuePolicy.oldestOverflow(
                Arrays.asList(1L, 2L), 2));
    }

    @Test
    public void onlyCompleteAppManagedDownloadsAreEligible() {
        assertFalse(ReadingQueueEligibility.isEligible(
                DownloadInfo.STATE_FINISH, "local-album:content://album", true));
        assertTrue(ReadingQueueEligibility.isEligible(
                DownloadInfo.STATE_FINISH, null, true));
        assertFalse(ReadingQueueEligibility.isEligible(
                DownloadInfo.STATE_WAIT, null, true));
        assertFalse(ReadingQueueEligibility.isEligible(
                DownloadInfo.STATE_FINISH, "content://archive", true));
        assertFalse(ReadingQueueEligibility.isEligible(
                DownloadInfo.STATE_FINISH, null, false));
    }
}
