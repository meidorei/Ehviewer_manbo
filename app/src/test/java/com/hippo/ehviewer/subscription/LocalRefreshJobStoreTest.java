package com.hippo.ehviewer.subscription;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalRefreshJobStoreTest {
    @Test
    public void onlyFullFollowAndBookmarkJobsRecordAttempts() {
        assertTrue(LocalRefreshJobStore.shouldRecordAttempt(
                snapshot(LocalRefreshJobStore.TYPE_FOLLOW, "GLOBAL", 10, 10, ""),
                LocalRefreshJobStore.STATUS_SUCCESS));
        assertTrue(LocalRefreshJobStore.shouldRecordAttempt(
                snapshot(LocalRefreshJobStore.TYPE_BOOKMARK, "FIRST_PAGE", 10, 10, ""),
                LocalRefreshJobStore.STATUS_CANCELLED));
        assertFalse(LocalRefreshJobStore.shouldRecordAttempt(
                snapshot(LocalRefreshJobStore.TYPE_BOOKMARK, "SINGLE:7", 1, 1, ""),
                LocalRefreshJobStore.STATUS_SUCCESS));
        assertFalse(LocalRefreshJobStore.shouldRecordAttempt(
                snapshot(LocalRefreshJobStore.TYPE_BASELINE, "AUTO", 5, 5, ""),
                LocalRefreshJobStore.STATUS_SUCCESS));
        assertFalse(LocalRefreshJobStore.shouldRecordAttempt(
                snapshot(LocalRefreshJobStore.TYPE_FOLLOW, "TAGS", 3, 10, ""),
                LocalRefreshJobStore.STATUS_PAUSED));
    }

    @Test
    public void attemptResultDistinguishesSuccessPartialFailureAndStop() {
        LocalRefreshJobStore.Snapshot success =
                snapshot(LocalRefreshJobStore.TYPE_FOLLOW, "GLOBAL", 10, 10, "");
        assertEquals(LocalRefreshJobStore.RESULT_SUCCESS,
                LocalRefreshJobStore.deriveAttemptResult(success,
                        LocalRefreshJobStore.STATUS_SUCCESS, 0));

        LocalRefreshJobStore.Snapshot partial =
                snapshot(LocalRefreshJobStore.TYPE_BOOKMARK, "FIRST_PAGE",
                        10, 10, "one\ntwo");
        assertEquals(LocalRefreshJobStore.RESULT_PARTIAL,
                LocalRefreshJobStore.deriveAttemptResult(partial,
                        LocalRefreshJobStore.STATUS_FAILED, 2));

        LocalRefreshJobStore.Snapshot failed =
                snapshot(LocalRefreshJobStore.TYPE_BOOKMARK, "FIRST_PAGE",
                        3, 10, "fatal");
        assertEquals(LocalRefreshJobStore.RESULT_FAILED,
                LocalRefreshJobStore.deriveAttemptResult(failed,
                        LocalRefreshJobStore.STATUS_FAILED, 1));
        assertEquals(LocalRefreshJobStore.RESULT_CANCELLED,
                LocalRefreshJobStore.deriveAttemptResult(failed,
                        LocalRefreshJobStore.STATUS_CANCELLED, 1));
    }

    @Test
    public void stoppingPausedJobRecordsCancellationWithoutClaimingSuccess() {
        for (String type : new String[]{LocalRefreshJobStore.TYPE_FOLLOW,
                LocalRefreshJobStore.TYPE_BOOKMARK, LocalRefreshJobStore.TYPE_BASELINE}) {
            LocalRefreshJobStore.Snapshot paused = new LocalRefreshJobStore.Snapshot(
                    type, "GLOBAL", LocalRefreshJobStore.STATUS_PAUSED,
                    3, 10, 2, 40, "current", "host", "", 1L, 2L);
            assertEquals(LocalRefreshJobStore.RESULT_CANCELLED,
                    LocalRefreshJobStore.deriveAttemptResult(paused,
                            LocalRefreshJobStore.STATUS_CANCELLED, 0));
            assertEquals(!LocalRefreshJobStore.TYPE_BASELINE.equals(type),
                    LocalRefreshJobStore.shouldRecordAttempt(paused,
                            LocalRefreshJobStore.STATUS_CANCELLED));
        }
    }

    @Test
    public void failureCountIgnoresEmptyLines() {
        assertEquals(0, LocalRefreshJobStore.failureCount(""));
        assertEquals(0, LocalRefreshJobStore.failureCount(null));
        assertEquals(2, LocalRefreshJobStore.failureCount("one\n\n two "));
    }

    @Test
    public void combinedHistoryUpdatesBothSourcesButNotLegacyJobs() {
        org.junit.Assert.assertArrayEquals(new String[]{"ALL", "FOLLOW", "BOOKMARK"},
                LocalRefreshJobStore.attemptTypes(LocalRefreshJobStore.TYPE_ALL));
        org.junit.Assert.assertArrayEquals(new String[]{"FOLLOW"},
                LocalRefreshJobStore.attemptTypes(LocalRefreshJobStore.TYPE_FOLLOW));
        LocalRefreshJobStore.Snapshot combined = snapshot("ALL", "GLOBAL", 4, 4, "");
        assertTrue(LocalRefreshJobStore.shouldRecordAttempt(combined, "SUCCESS"));
        assertFalse(LocalRefreshJobStore.shouldRecordAttempt(combined, "PAUSED"));
        assertEquals("CANCELLED", LocalRefreshJobStore.deriveAttemptResult(combined, "CANCELLED", 0));
        assertEquals("PARTIAL", LocalRefreshJobStore.deriveAttemptResult(
                snapshot("ALL", "GLOBAL", 4, 4, "one"), "FAILED", 1));
        assertFalse(LocalRefreshJobStore.shouldRecordAttempt(
                snapshot("ALL", "GLOBAL", 0, 0, ""), "SUCCESS"));
    }

    @Test
    public void combinedSuccessRequiresEveryItemAndNoFailures() {
        assertTrue(LocalRefreshJobStore.isFullCombinedSuccess(
                snapshot("ALL", "GLOBAL", 4, 4, ""), "SUCCESS"));
        assertFalse(LocalRefreshJobStore.isFullCombinedSuccess(
                snapshot("ALL", "GLOBAL", 3, 4, ""), "SUCCESS"));
        assertFalse(LocalRefreshJobStore.isFullCombinedSuccess(
                snapshot("ALL", "GLOBAL", 4, 4, "one"), "SUCCESS"));
        assertFalse(LocalRefreshJobStore.isFullCombinedSuccess(
                snapshot("ALL", "GLOBAL", 4, 4, ""), "PAUSED"));
        assertFalse(LocalRefreshJobStore.isFullCombinedSuccess(
                snapshot("ALL", "GLOBAL", 4, 4, ""), "CANCELLED"));
        assertFalse(LocalRefreshJobStore.isFullCombinedSuccess(
                snapshot("ALL", "GLOBAL", 0, 0, ""), "SUCCESS"));
    }

    private static LocalRefreshJobStore.Snapshot snapshot(
            String type, String method, int index, int total, String failures) {
        return new LocalRefreshJobStore.Snapshot(type, method,
                LocalRefreshJobStore.STATUS_RUNNING, index, total,
                0, 0, "", "", failures, 1L, 1L);
    }
}
