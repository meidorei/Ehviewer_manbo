package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One in-memory scan, with independent coverage and matches for both sources. */
final class UnifiedGlobalScan {
    final Map<String, List<GalleryInfo>> follows = new LinkedHashMap<>();
    final Map<Long, List<GalleryInfo>> bookmarks = new LinkedHashMap<>();
    private final Map<Long, BookmarkGlobalMatcher> matchers;
    private final FeedBoundary followCursor;
    private final FeedBoundary bookmarkCursor;
    private final Set<String> visited = new HashSet<>();
    FeedBoundary top = FeedBoundary.EMPTY;
    int pages;
    int galleries;
    boolean followCovered;
    boolean bookmarkCovered;

    UnifiedGlobalScan(List<String> tags, Map<Long, BookmarkGlobalMatcher> matchers,
                      FeedBoundary followCursor, FeedBoundary bookmarkCursor) {
        for (String tag : tags) follows.put(tag, new ArrayList<>());
        this.matchers = new LinkedHashMap<>(matchers);
        for (Long id : matchers.keySet()) bookmarks.put(id, new ArrayList<>());
        this.followCursor = followCursor;
        this.bookmarkCursor = bookmarkCursor;
        followCovered = tags.isEmpty();
        bookmarkCovered = matchers.isEmpty();
    }

    interface PageSource { Page fetch(String url) throws Throwable; }
    interface StopCheck { void check() throws InterruptedException; }
    interface Progress { void update(); }

    static final class Page {
        final List<GalleryInfo> items;
        final String next;
        final boolean hostChanged;

        Page(List<GalleryInfo> items, String next, boolean hostChanged) {
            this.items = items;
            this.next = next;
            this.hostChanged = hostChanged;
        }
    }

    /** Returns false only when a new host requires fresh source-specific cursors. */
    boolean readPages(String url, int limit, PageSource source, StopCheck stop,
                      Progress progress) throws Throwable {
        while (needsPage() && url != null && pages < limit) {
            stop.check();
            visit(url);
            Page page = source.fetch(url);
            stop.check();
            if (page.hostChanged) return false;
            checkNext(page.next);
            addPage(page.items, page.next != null);
            progress.update();
            url = page.next;
        }
        stop.check();
        return true;
    }

    boolean needsPage() {
        return !followCovered || !bookmarkCovered;
    }

    void visit(String url) {
        if (!visited.add(url)) throw new IllegalStateException("全局分页地址重复");
    }

    void checkNext(String url) {
        if (url != null && visited.contains(url)) {
            throw new IllegalStateException("全局分页地址重复");
        }
    }

    void addPage(List<GalleryInfo> items, boolean hasNext) {
        // Validate the whole page before accepting any of its data.
        for (GalleryInfo gallery : items) {
            if (gallery == null || gallery.postedTimestamp <= 0) {
                throw new IllegalArgumentException("全局结果发布时间无效");
            }
        }
        pages++;
        galleries += items.size();
        // Merge the complete newest second, even when it spans multiple pages.
        top = LocalBaselineResetPolicy.newer(top, LocalFollowRepository.boundaryOf(items));
        for (GalleryInfo gallery : items) {
            Set<String> tags = new HashSet<>();
            if (gallery.simpleTags != null) {
                for (String tag : gallery.simpleTags) {
                    tags.add(SubscriptionRepository.normalizeTagName(tag));
                }
            }
            for (Map.Entry<String, List<GalleryInfo>> entry : follows.entrySet()) {
                if (tags.contains(entry.getKey())) entry.getValue().add(gallery);
            }
            for (Map.Entry<Long, BookmarkGlobalMatcher> entry : matchers.entrySet()) {
                if (entry.getValue().matches(gallery)) bookmarks.get(entry.getKey()).add(gallery);
            }
        }
        boolean end = !hasNext || items.isEmpty();
        followCovered |= followCursor.isEmpty() || end
                || GlobalScanPolicy.hasPassedCursor(followCursor, items);
        bookmarkCovered |= bookmarkCursor.isEmpty() || end
                || GlobalScanPolicy.hasPassedCursor(bookmarkCursor, items);
    }
}
