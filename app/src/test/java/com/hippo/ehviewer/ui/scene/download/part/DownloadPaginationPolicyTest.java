package com.hippo.ehviewer.ui.scene.download.part;

import static org.junit.Assert.*;
import org.junit.Test;

public class DownloadPaginationPolicyTest {
    private int count(int size, int page) {
        return DownloadPaginationPolicy.itemCount(size, 50, page, true);
    }

    @Test public void thresholdUsesSameBoundsForCountAndPositions() {
        for (int size : new int[]{499, 500, 501}) {
            boolean paged = size > 500;
            assertEquals(paged, DownloadPaginationPolicy.isPaged(size, true));
            assertEquals(paged ? 50 : size, count(size, 2));
            int start = paged ? 50 : 0;
            assertEquals(start, DownloadPaginationPolicy.positionInList(0, size, 50, 2, true));
            assertEquals(0, DownloadPaginationPolicy.indexInPage(start, size, 50, 2, true));
        }
    }

    @Test public void fullPageDeletionRefillsRatherThanShrinkingAdapterCount() {
        assertEquals(50, count(601, 1));
        assertEquals(50, count(600, 1));
        assertEquals(49, DownloadPaginationPolicy.positionInList(49, 600, 50, 1, true));
    }

    @Test public void anotherPageNeverWrapsIntoVisiblePage() {
        assertEquals(-1, DownloadPaginationPolicy.indexInPage(3, 601, 50, 2, true));
        assertEquals(-1, DownloadPaginationPolicy.indexInPage(103, 601, 50, 2, true));
        assertEquals(3, DownloadPaginationPolicy.indexInPage(53, 601, 50, 2, true));
    }

    @Test public void emptiedLastPageClampsBeforeCountAndIndexCalculation() {
        assertEquals(1, count(551, 12));
        assertEquals(11, DownloadPaginationPolicy.clampPage(550, 50, 12, true));
        assertEquals(50, count(550, 12));
        assertEquals(500, DownloadPaginationPolicy.positionInList(0, 550, 50, 12, true));
    }

    @Test public void repeatedDeletionCrossesPagingThresholdSafely() {
        int page = 12;
        for (int size = 551; size >= 0; size--) {
            page = DownloadPaginationPolicy.clampPage(size, 50, page, true);
            int start = DownloadPaginationPolicy.pageStart(size, 50, page, true);
            int visible = count(size, page);
            assertTrue(visible >= 0);
            assertTrue(start + visible <= size);
            if (visible > 0) {
                assertEquals(start + visible - 1,
                        DownloadPaginationPolicy.positionInList(visible - 1, size, 50, page, true));
            }
            if (size <= 500) assertEquals(1, page);
        }
    }

    @Test public void disabledPagingUsesWholeList() {
        assertEquals(800, DownloadPaginationPolicy.itemCount(800, 50, 4, false));
        assertEquals(0, DownloadPaginationPolicy.pageStart(800, 50, 4, false));
        assertEquals(1, DownloadPaginationPolicy.clampPage(800, 50, 4, false));
    }

    @Test public void emptyAndInvalidInputsNeverProduceNegativeBounds() {
        assertEquals(0, count(0, Integer.MAX_VALUE));
        assertEquals(0, count(-1, -1));
        assertEquals(1, DownloadPaginationPolicy.clampPage(501, 50, -4, true));
        assertEquals(11, DownloadPaginationPolicy.clampPage(501, 50, Integer.MAX_VALUE, true));
        assertEquals(1, DownloadPaginationPolicy.itemCount(501, 0, 2, true));
        assertEquals(-1, DownloadPaginationPolicy.positionInList(-1, 501, 50, 1, true));
        assertEquals(-1, DownloadPaginationPolicy.positionInList(50, 501, 50, 1, true));
        assertEquals(-1, DownloadPaginationPolicy.positionInList(0, 0, 50, 1, true));
    }

    @Test public void pageGrowthAndShrinkPreserveValidRequestedPage() {
        assertEquals(1, DownloadPaginationPolicy.clampPage(500, 50, 3, true));
        assertEquals(3, DownloadPaginationPolicy.clampPage(501, 50, 3, true));
        assertEquals(50, count(501, 3));
    }
}
