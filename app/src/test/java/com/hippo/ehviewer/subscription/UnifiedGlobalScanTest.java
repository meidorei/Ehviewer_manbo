package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;
import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class UnifiedGlobalScanTest {
    @Test public void oneFetchFeedsBothSourcesAndWaitsForOlderBoundary() throws Throwable {
        UnifiedGlobalScan scan = scan(200, 100);
        AtomicInteger requests = new AtomicInteger();
        assertTrue(scan.readPages("p1", 30, url -> {
            int page = requests.incrementAndGet();
            return page == 1 ? page("p2", gallery(210, 1), gallery(199, 2))
                    : page(null, gallery(100, 3), gallery(99, 4));
        }, () -> {}, () -> {}));
        assertEquals(2, requests.get());
        assertEquals(4, scan.follows.get("artist:example").size());
        assertEquals(4, scan.bookmarks.get(7L).size());
        assertTrue(scan.followCovered);
        assertTrue(scan.bookmarkCovered);
        assertFalse(scan.needsPage());
    }

    @Test public void sameSecondAcrossPagesIsNotSkipped() throws Throwable {
        UnifiedGlobalScan scan = scan(100, 100);
        scan.addPage(Collections.singletonList(gallery(100, 1)), true);
        assertTrue(scan.needsPage());
        scan.addPage(Arrays.asList(gallery(100, 2), gallery(99, 3)), true);
        assertFalse(scan.needsPage());
        assertEquals(2, scan.top.gids.size());
        assertTrue(scan.top.gids.contains(2L));
    }

    @Test public void firstSourceDoesNotStopTheExistingSource() {
        UnifiedGlobalScan scan = scan(0, 100);
        scan.addPage(Collections.singletonList(gallery(200, 1)), true);
        assertTrue(scan.followCovered);
        assertFalse(scan.bookmarkCovered);
        assertTrue(scan.needsPage());
        scan.addPage(Collections.singletonList(gallery(99, 2)), true);
        assertFalse(scan.needsPage());
        assertEquals(200, scan.top.time);
    }

    @Test public void pageLimitFallsBackOnlyForUncoveredSource() throws Throwable {
        UnifiedGlobalScan scan = scan(200, 100);
        AtomicInteger calls = new AtomicInteger();
        assertTrue(scan.readPages("p1", 1, url -> {
            calls.incrementAndGet();
            return page("p2", gallery(210, 1), gallery(150, 2));
        }, () -> {}, () -> {}));
        assertEquals(1, calls.get());
        assertTrue(scan.followCovered);
        assertFalse(scan.bookmarkCovered);
    }

    @Test public void reverseBoundaryFallsBackOnlyForFollows() {
        UnifiedGlobalScan scan = scan(100, 200);
        scan.addPage(Arrays.asList(gallery(210, 1), gallery(150, 2)), true);
        assertFalse(scan.followCovered);
        assertTrue(scan.bookmarkCovered);
    }

    @Test public void emptySourcesDoNotRequireRequests() throws Throwable {
        UnifiedGlobalScan empty = new UnifiedGlobalScan(Collections.emptyList(),
                Collections.emptyMap(), FeedBoundary.EMPTY, FeedBoundary.EMPTY);
        assertTrue(empty.readPages("p1", 30, url -> { fail("unexpected request"); return null; },
                () -> {}, () -> {}));
        assertEquals(0, empty.pages);
    }

    @Test public void eitherSourceCanRunOnItsOwn() {
        UnifiedGlobalScan follows = new UnifiedGlobalScan(Collections.singletonList("artist:example"),
                Collections.emptyMap(), boundary(100), FeedBoundary.EMPTY);
        follows.addPage(Collections.singletonList(gallery(99, 1)), false);
        assertFalse(follows.needsPage());
        UnifiedGlobalScan bookmarks = new UnifiedGlobalScan(Collections.emptyList(),
                matchers(), FeedBoundary.EMPTY, boundary(100));
        bookmarks.addPage(Collections.singletonList(gallery(99, 1)), false);
        assertFalse(bookmarks.needsPage());
        assertEquals(1, bookmarks.bookmarks.get(7L).size());
    }

    @Test public void firstRunOnlyNeedsOnePageForBothBaselines() throws Throwable {
        UnifiedGlobalScan scan = scan(0, 0);
        scan.readPages("p1", 30, url -> page("p2", gallery(200, 1)), () -> {}, () -> {});
        assertEquals(1, scan.pages);
        assertFalse(scan.needsPage());
    }

    @Test public void endOfListCoversBothBoundaries() {
        UnifiedGlobalScan scan = scan(100, 50);
        scan.addPage(Collections.singletonList(gallery(200, 1)), false);
        assertFalse(scan.needsPage());
    }

    @Test public void invalidTimestampRejectsWholePage() {
        UnifiedGlobalScan scan = scan(100, 100);
        assertThrows(IllegalArgumentException.class, () ->
                scan.addPage(Arrays.asList(gallery(200, 1), gallery(0, 2)), true));
        assertEquals(0, scan.pages);
        assertTrue(scan.top.isEmpty());
        assertTrue(scan.follows.get("artist:example").isEmpty());
        assertTrue(scan.bookmarks.get(7L).isEmpty());
    }

    @Test public void cyclicPaginationRejectsScanEvenAtBoundary() throws Throwable {
        UnifiedGlobalScan scan = scan(100, 100);
        try {
            scan.readPages("p1", 30, url -> page("p1", gallery(99, 1)), () -> {}, () -> {});
            fail("expected cycle rejection");
        } catch (IllegalStateException expected) {
            assertEquals(0, scan.pages);
        }
    }

    @Test public void networkFailureCannotReturnACompletedScan() throws Throwable {
        UnifiedGlobalScan scan = scan(100, 100);
        AtomicInteger requests = new AtomicInteger();
        try {
            scan.readPages("p1", 30, url -> {
                if (requests.incrementAndGet() == 2) throw new IOException("offline");
                return page("p2", gallery(200, 1));
            }, () -> {}, () -> {});
            fail("expected network failure");
        } catch (IOException expected) {
            assertEquals(1, scan.pages);
            assertTrue(scan.needsPage());
        }
    }

    @Test public void pauseOrStopDuringFetchRejectsTheFetchedPage() throws Throwable {
        for (String reason : Arrays.asList("pause", "stop")) {
            UnifiedGlobalScan scan = scan(100, 100);
            AtomicBoolean stopped = new AtomicBoolean();
            try {
                scan.readPages("p1", 30, url -> {
                    stopped.set(true);
                    return page(null, gallery(99, 1));
                }, () -> { if (stopped.get()) throw new InterruptedException(reason); }, () -> {});
                fail("expected interruption");
            } catch (InterruptedException expected) {
                assertEquals(0, scan.pages);
                assertTrue(scan.top.isEmpty());
            }
        }
    }

    @Test public void hostSwitchRejectsNewHostPageAndRestartHasNoOldMatches() throws Throwable {
        UnifiedGlobalScan old = scan(100, 100);
        AtomicInteger requests = new AtomicInteger();
        assertFalse(old.readPages("p1", 30, url -> {
            if (requests.incrementAndGet() == 1) return page("p2", gallery(200, 1));
            return new UnifiedGlobalScan.Page(Collections.singletonList(gallery(99, 2)), null, true);
        }, () -> {}, () -> {}));
        assertEquals(1, old.pages);
        UnifiedGlobalScan restarted = scan(80, 60);
        restarted.readPages("p1", 30, url -> page(null, gallery(50, 3)), () -> {}, () -> {});
        assertEquals(1, restarted.follows.get("artist:example").size());
        assertEquals(3, restarted.bookmarks.get(7L).get(0).gid);
        assertEquals(50, restarted.top.time);
    }

    private static UnifiedGlobalScan scan(long follow, long bookmark) {
        return new UnifiedGlobalScan(Collections.singletonList("artist:example"),
                matchers(), boundary(follow), boundary(bookmark));
    }

    private static Map<Long, BookmarkGlobalMatcher> matchers() {
        QuickSearch search = new QuickSearch();
        search.category = com.hippo.ehviewer.client.EhUtils.NONE;
        search.minRating = -1;
        search.mode = ListUrlBuilder.MODE_TAG;
        search.keyword = "artist:example";
        search.pageFrom = -1;
        search.pageTo = -1;
        return Collections.singletonMap(7L, BookmarkGlobalMatcher.compile(search).matcher);
    }

    private static FeedBoundary boundary(long time) {
        return time == 0 ? FeedBoundary.EMPTY : new FeedBoundary(time, Collections.emptySet());
    }

    private static GalleryInfo gallery(long time, long gid) {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = gid;
        gallery.postedTimestamp = time;
        gallery.simpleTags = new String[]{"artist:example"};
        return gallery;
    }

    private static UnifiedGlobalScan.Page page(String next, GalleryInfo... items) {
        return new UnifiedGlobalScan.Page(Arrays.asList(items), next, false);
    }
}
