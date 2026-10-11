package com.hippo.ehviewer.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BookmarkTagLayoutTest {
    private static BookmarkTagLayout.Result arrange(int width, int... widths) {
        return BookmarkTagLayout.arrange(width, widths, 20, 4, 4, hidden -> 20);
    }

    @Test public void separateTagsKeepOrderAndSpacingOnOneRow() {
        BookmarkTagLayout.Result result = arrange(100, 30, 20, 25);
        assertEquals(3, result.shownCount);
        assertEquals(0, result.hiddenCount);
        assertEquals(20, result.height);
        assertEquals(0, result.positions.get(0).left);
        assertEquals(34, result.positions.get(1).left);
        assertEquals(58, result.positions.get(2).left);
    }

    @Test public void wrapsWholeTagsOntoASecondRow() {
        BookmarkTagLayout.Result result = arrange(70, 30, 30, 30);
        assertEquals(3, result.shownCount);
        assertEquals(0, result.hiddenCount);
        assertEquals(44, result.height);
        assertEquals(0, result.positions.get(1).top);
        assertEquals(24, result.positions.get(2).top);
        assertEquals(0, result.positions.get(2).left);
    }

    @Test public void exactWidthDoesNotCreateAnUnnecessaryRow() {
        BookmarkTagLayout.Result result = arrange(64, 30, 30);
        assertEquals(2, result.shownCount);
        assertEquals(20, result.height);
        assertEquals(34, result.positions.get(1).left);
    }

    @Test public void overflowCountFitsInsideTwoRowsAndIncludesDisplacedTags() {
        BookmarkTagLayout.Result result = arrange(80, 60, 60, 60);
        assertEquals(1, result.shownCount);
        assertEquals(2, result.hiddenCount);
        assertEquals(2, result.positions.size());
        assertEquals(24, result.positions.get(1).top);
        assertEquals(20, result.positions.get(1).width);
        assertEquals(44, result.height);
    }

    @Test public void singleLongTagIsBoundedWithoutABlankFirstRow() {
        BookmarkTagLayout.Result result = arrange(50, 120);
        assertEquals(1, result.shownCount);
        assertEquals(0, result.hiddenCount);
        assertEquals(0, result.positions.get(0).top);
        assertEquals(50, result.positions.get(0).width);
        assertEquals(20, result.height);
    }

    @Test public void countWidthChangesAreMeasuredForTheFinalHiddenCount() {
        int[] widths = new int[12];
        java.util.Arrays.fill(widths, 60);
        BookmarkTagLayout.Result result = BookmarkTagLayout.arrange(
                80, widths, 20, 4, 4, hidden -> hidden >= 10 ? 24 : 16);
        assertEquals(1, result.shownCount);
        assertEquals(11, result.hiddenCount);
        assertEquals(24, result.positions.get(1).width);
        for (BookmarkTagLayout.Position position : result.positions) {
            assertTrue(position.left >= 0 && position.left + position.width <= 80);
            assertTrue(position.top < 44);
        }
    }

    @Test public void veryNarrowWidthKeepsChipsWithinBounds() {
        BookmarkTagLayout.Result result = arrange(8, 40, 40, 40);
        // One bounded label and the counter can each occupy a row.
        assertEquals(1, result.shownCount);
        assertEquals(2, result.hiddenCount);
        assertEquals(8, result.positions.get(1).width);
        assertEquals(44, result.height);
    }

    @Test public void zeroWidthAndEmptyLabelsHaveNoVisibleHeight() {
        BookmarkTagLayout.Result zero = arrange(0, 30, 20);
        assertEquals(0, zero.shownCount);
        assertEquals(2, zero.hiddenCount);
        assertEquals(0, zero.height);
        assertTrue(zero.positions.isEmpty());
        BookmarkTagLayout.Result empty = arrange(100);
        assertEquals(0, empty.hiddenCount);
        assertEquals(0, empty.height);
    }

    @Test public void systemFontScalingIncreasesRowHeightWithoutAddingRows() {
        BookmarkTagLayout.Result result = BookmarkTagLayout.arrange(
                70, new int[]{30, 30, 30}, 38, 4, 4, hidden -> 20);
        assertEquals(3, result.shownCount);
        assertEquals(80, result.height);
        assertEquals(42, result.positions.get(2).top);
    }
}
