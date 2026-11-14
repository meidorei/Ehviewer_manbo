package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class BookmarkUnreadClearPolicyTest {
    @Test public void overlappingBookmarksDecreaseBySharedGids() {
        Map<String, Collection<Long>> unread = unread(
                "a", gids(1, 2, 3, 4, 5),
                "b", gids(4, 5, 6, 7, 8));

        Map<String, Integer> remaining = calculate(gids(1, 2, 3, 4, 5), unread);

        assertEquals(Integer.valueOf(0), remaining.get("a"));
        assertEquals(Integer.valueOf(3), remaining.get("b"));
    }

    @Test public void oneGidCanClearAnyNumberOfBookmarks() {
        Map<String, Collection<Long>> unread = unread(
                "a", gids(1, 2), "b", gids(1, 3), "c", gids(1, 4));

        Map<String, Integer> remaining = calculate(gids(1, 2), unread);

        assertEquals(Integer.valueOf(0), remaining.get("a"));
        assertEquals(Integer.valueOf(1), remaining.get("b"));
        assertEquals(Integer.valueOf(1), remaining.get("c"));
    }

    @Test public void disjointAndAlreadyClearedBookmarksStayUntouched() {
        Map<String, Collection<Long>> unread = unread(
                "a", gids(1), "b", gids(2), "empty", Collections.emptySet());
        Map<String, Integer> first = calculate(gids(1), unread);
        assertEquals(Collections.singletonMap("a", 0), first);

        Map<String, Collection<Long>> afterClear = unread(
                "a", Collections.emptySet(), "b", gids(2));
        assertTrue(calculate(gids(1), afterClear).isEmpty());
    }

    @Test public void onlyGidsPresentAtEntryAreShared() {
        Map<String, Collection<Long>> unread = unread(
                "a", gids(1, 9), "b", gids(1, 9, 10));

        Map<String, Integer> remaining = calculate(gids(1), unread);

        assertEquals(Integer.valueOf(1), remaining.get("a"));
        assertEquals(Integer.valueOf(2), remaining.get("b"));
    }

    @Test public void completeOverlapClearsBothExactCounts() {
        Map<String, Collection<Long>> unread = unread(
                "a", gids(1, 2), "b", gids(1, 2));
        Map<String, Integer> remaining = calculate(gids(1, 2), unread);
        assertEquals(Integer.valueOf(0), remaining.get("a"));
        assertEquals(Integer.valueOf(0), remaining.get("b"));
    }

    @Test public void lowerBoundCanKeepTwentyPlusAfterRowsAreRemoved() {
        Map<String, Collection<Long>> unread = unread(
                "a", gids(1, 2),
                "b", gids(1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                        11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21));
        int remaining = calculate(gids(1, 2), unread).get("b");
        TagUpdateState state = new TagUpdateState(
                "b", remaining, TagUpdateState.State.LOWER_BOUND, 1);
        assertEquals("20+", state.displayCount());
    }

    @Test public void followSourcesNeverShareBookmarkReads() {
        Map<String, Collection<Long>> unread = unread("follow", gids(1, 2));
        assertTrue(BookmarkUnreadClearPolicy.remainingCounts(
                LocalFollowRepository.SOURCE_FOLLOW, gids(1), unread).isEmpty());
    }

    @Test public void exactZeroAdvancesToRecordedUpdateTop() {
        FeedBoundary updateTop = boundary(100, 10, 11);

        FeedBoundary result = BookmarkUnreadClearPolicy.boundaryToAdvance(
                "opened", "affected", 2, 0,
                TagUpdateState.State.EXACT, FeedBoundary.EMPTY, updateTop);

        assertSame(updateTop, result);
        assertTrue(result.isFirstOld(100, 10));
        assertTrue(result.isFirstOld(100, 11));
        assertTrue(result.isNew(101, 12));
    }

    @Test public void partialClearDoesNotAdvanceBoundary() {
        assertTrue(BookmarkUnreadClearPolicy.boundaryToAdvance(
                "opened", "affected", 2, 1, TagUpdateState.State.EXACT,
                FeedBoundary.EMPTY, boundary(100, 10)).isEmpty());
    }

    @Test public void lowerBoundZeroDoesNotAdvanceBoundary() {
        assertTrue(BookmarkUnreadClearPolicy.boundaryToAdvance(
                "opened", "affected", 20, 0, TagUpdateState.State.LOWER_BOUND,
                FeedBoundary.EMPTY, boundary(100, 10)).isEmpty());
    }

    @Test public void emptySyncBoundaryDoesNotAdvanceBoundary() {
        assertTrue(BookmarkUnreadClearPolicy.boundaryToAdvance(
                "opened", "affected", 2, 0, TagUpdateState.State.EXACT,
                FeedBoundary.EMPTY, FeedBoundary.EMPTY).isEmpty());
    }

    @Test public void openedBookmarkKeepsItsLoadedPageBoundary() {
        assertTrue(BookmarkUnreadClearPolicy.boundaryToAdvance(
                "same", "same", 2, 0, TagUpdateState.State.EXACT,
                FeedBoundary.EMPTY, boundary(100, 10)).isEmpty());
    }

    @Test public void alreadyZeroBookmarkDoesNotAdvanceBoundary() {
        assertTrue(BookmarkUnreadClearPolicy.boundaryToAdvance(
                "opened", "affected", 0, 0, TagUpdateState.State.EXACT,
                FeedBoundary.EMPTY, boundary(100, 10)).isEmpty());
    }

    @Test public void multipleExactZerosKeepTheirOwnRecordedUpdateTops() {
        FeedBoundary firstTop = boundary(100, 10);
        FeedBoundary secondTop = boundary(200, 20, 21);

        assertSame(firstTop, BookmarkUnreadClearPolicy.boundaryToAdvance(
                "opened", "first", 1, 0, TagUpdateState.State.EXACT,
                FeedBoundary.EMPTY, firstTop));
        assertSame(secondTop, BookmarkUnreadClearPolicy.boundaryToAdvance(
                "opened", "second", 2, 0, TagUpdateState.State.EXACT,
                FeedBoundary.EMPTY, secondTop));
    }

    @Test public void newerOpenBoundaryDoesNotRegress() {
        assertTrue(BookmarkUnreadClearPolicy.boundaryToAdvance(
                "opened", "affected", 2, 0, TagUpdateState.State.EXACT,
                boundary(200, 20), boundary(100, 10)).isEmpty());
    }

    @Test public void olderOpenBoundaryAdvancesToRecordedUpdateTop() {
        FeedBoundary updateTop = boundary(200, 20);

        FeedBoundary result = BookmarkUnreadClearPolicy.boundaryToAdvance(
                "opened", "affected", 2, 0, TagUpdateState.State.EXACT,
                boundary(100, 10), updateTop);

        assertSame(updateTop, result);
    }

    @Test public void sameSecondBoundaryMergesGids() {
        FeedBoundary result = BookmarkUnreadClearPolicy.boundaryToAdvance(
                "opened", "affected", 2, 0, TagUpdateState.State.EXACT,
                boundary(100, 10), boundary(100, 11, 12));

        assertEquals(100, result.time);
        assertEquals(gids(10, 11, 12), result.gids);
    }

    @Test public void sameSecondCoveredBoundaryDoesNotRewrite() {
        assertTrue(BookmarkUnreadClearPolicy.boundaryToAdvance(
                "opened", "affected", 2, 0, TagUpdateState.State.EXACT,
                boundary(100, 10, 11), boundary(100, 11)).isEmpty());
    }

    @Test public void bookmarkSyncBoundarySqlQuotesCurrentTimeIdentifier() {
        String sql = LocalFollowRepository.READ_BOOKMARK_SYNC_BOUNDARY_SQL;
        assertTrue(sql.contains("SELECT \"CURRENT_TIME\","));
        assertTrue(sql.contains("AND \"CURRENT_TIME\">0"));
        assertFalse(sql.contains("SELECT CURRENT_TIME,"));
        assertFalse(sql.contains("AND CURRENT_TIME>0"));
    }

    private static Map<String, Integer> calculate(
            Set<Long> opened, Map<String, Collection<Long>> unread) {
        return BookmarkUnreadClearPolicy.remainingCounts(
                LocalFollowRepository.SOURCE_BOOKMARK, opened, unread);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Collection<Long>> unread(Object... entries) {
        Map<String, Collection<Long>> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], (Collection<Long>) entries[i + 1]);
        }
        return result;
    }

    private static Set<Long> gids(long... values) {
        Set<Long> result = new LinkedHashSet<>();
        for (long value : values) result.add(value);
        return result;
    }

    private static FeedBoundary boundary(long time, long... values) {
        return new FeedBoundary(time, gids(values));
    }
}
