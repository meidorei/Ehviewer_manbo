package com.hippo.ehviewer.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class DownloadReadingNoticePolicyTest {

    @Test
    public void openingWithoutSkipShowsOneNotice() {
        assertEquals(Collections.singletonList(
                DownloadReadingNoticePolicy.Notice.OPENING),
                DownloadReadingNoticePolicy.buildAdvanceNotices(0, true));
    }

    @Test
    public void skippedGalleryAndOpeningUseSeparateOrderedNotices() {
        assertEquals(Arrays.asList(
                        DownloadReadingNoticePolicy.Notice.SKIPPED,
                        DownloadReadingNoticePolicy.Notice.OPENING),
                DownloadReadingNoticePolicy.buildAdvanceNotices(2, true));
    }

    @Test
    public void skippedGalleryAndQueueEndUseSeparateOrderedNotices() {
        assertEquals(Arrays.asList(
                        DownloadReadingNoticePolicy.Notice.SKIPPED,
                        DownloadReadingNoticePolicy.Notice.END),
                DownloadReadingNoticePolicy.buildAdvanceNotices(1, false));
    }

    @Test
    public void titleUsesGidFallbackAndUnicodeSafeEllipsis() {
        assertEquals("123", DownloadReadingNoticePolicy.ellipsizeTitle("  ", 123L));
        String longTitle = "\uD83D\uDE00".repeat(45);
        String shortened = DownloadReadingNoticePolicy.ellipsizeTitle(longTitle, 123L);
        assertEquals(DownloadReadingNoticePolicy.MAX_TITLE_CODE_POINTS,
                shortened.codePointCount(0, shortened.length()));
        assertTrue(shortened.endsWith("\u2026"));
    }

    @Test
    public void providerErrorIsOnlyShownOnceForQueuedTransitions() {
        assertTrue(DownloadReadingNoticePolicy.shouldShowProviderError(
                true, false, true));
        assertFalse(DownloadReadingNoticePolicy.shouldShowProviderError(
                true, true, true));
        assertFalse(DownloadReadingNoticePolicy.shouldShowProviderError(
                false, false, true));
        assertFalse(DownloadReadingNoticePolicy.shouldShowProviderError(
                true, false, false));
    }
}
