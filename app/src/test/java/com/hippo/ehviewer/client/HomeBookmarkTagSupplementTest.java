package com.hippo.ehviewer.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.exception.CancelledException;
import com.hippo.ehviewer.dao.QuickSearch;
import com.hippo.ehviewer.subscription.BookmarkTagMarkerPolicy;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public class HomeBookmarkTagSupplementTest {
    @Test public void disabledEmptyAndCompleteListsMakeNoRequest() throws Throwable {
        HomeBookmarkTagSupplement.Loader unexpected = missing -> {
            throw new AssertionError("Unneeded metadata request");
        };
        GalleryInfo gallery = gallery(1);
        assertTrue(HomeBookmarkTagSupplement.fillMissing(false,
                Collections.singletonList(gallery), unexpected));
        assertTrue(HomeBookmarkTagSupplement.fillMissing(true,
                Collections.emptyList(), unexpected));
        gallery.simpleTags = new String[0];
        assertTrue(HomeBookmarkTagSupplement.fillMissing(true,
                Collections.singletonList(gallery), unexpected));
        gallery.simpleTags = new String[] {"male:old man"};
        assertTrue(HomeBookmarkTagSupplement.fillMissing(true,
                Collections.singletonList(gallery), unexpected));
    }

    @Test public void onlyMissingItemsAreRequestedAndOnlyTagsAreCopiedBack() throws Throwable {
        GalleryInfo complete = gallery(1);
        complete.simpleTags = new String[] {"female:foo"};
        GalleryInfo missing = gallery(2);
        missing.title = "HTML title";
        missing.pages = 12;
        missing.tgList = new ArrayList<>(Collections.singletonList("female:bar"));
        String[] tags = {"male:old man", "language:chinese"};
        assertTrue(HomeBookmarkTagSupplement.fillMissing(true, Arrays.asList(complete, missing), requests -> {
            assertEquals(1, requests.size());
            assertEquals(2, requests.get(0).gid);
            assertEquals("token2", requests.get(0).token);
            requests.get(0).simpleTags = tags;
            requests.get(0).title = "API title";
            requests.get(0).pages = 999;
        }));
        tags[0] = "modified";
        assertArrayEquals(new String[] {"male:old man", "language:chinese"}, missing.simpleTags);
        assertArrayEquals(new String[] {"female:foo"}, complete.simpleTags);
        assertEquals("HTML title", missing.title);
        assertEquals(12, missing.pages);
        assertEquals(Collections.singletonList("female:bar"), missing.tgList);
    }

    @Test public void completeApiTagsRevealMaleBookmarkMissingFromHtml() throws Throwable {
        GalleryInfo gallery = gallery(1);
        gallery.tgList = new ArrayList<>(Collections.singletonList("female:foo"));
        QuickSearch bookmark = new QuickSearch();
        bookmark.mode = ListUrlBuilder.MODE_NORMAL;
        bookmark.keyword = "m:\"old man\"$";
        bookmark.name = "男性老人";
        BookmarkTagMarkerPolicy marker = BookmarkTagMarkerPolicy.compile(
                Collections.singletonList(bookmark), true);
        assertTrue(marker.matchGallery(gallery).isEmpty());
        assertTrue(HomeBookmarkTagSupplement.fillMissing(true, Collections.singletonList(gallery),
                requests -> requests.get(0).simpleTags = new String[] {
                        "female:foo", "male:old man", "language:chinese"}));
        assertEquals(1, marker.matchGallery(gallery).size());
        assertEquals("男性老人", marker.matchGallery(gallery).get(0).bookmarkName);
        assertEquals(Collections.singletonList("female:foo"), gallery.tgList);
    }

    @Test public void failedApiKeepsHtmlEvenAfterPartialParsing() throws Throwable {
        GalleryInfo gallery = gallery(1);
        gallery.title = "HTML title";
        gallery.tgList = new ArrayList<>(Collections.singletonList("female:foo"));
        assertFalse(HomeBookmarkTagSupplement.fillMissing(true, Collections.singletonList(gallery), requests -> {
            requests.get(0).title = "partially parsed";
            requests.get(0).simpleTags = new String[] {"male:old man"};
            throw new IOException("API unavailable");
        }));
        assertNull(gallery.simpleTags);
        assertEquals("HTML title", gallery.title);
        assertEquals(Collections.singletonList("female:foo"), gallery.tgList);
    }

    @Test public void omittedApiItemsRemainMissingWithoutDiscardingValidTags() throws Throwable {
        GalleryInfo first = gallery(1);
        GalleryInfo second = gallery(2);
        assertFalse(HomeBookmarkTagSupplement.fillMissing(true, Arrays.asList(first, second),
                requests -> requests.get(0).simpleTags = new String[] {"male:old man"}));
        assertArrayEquals(new String[] {"male:old man"}, first.simpleTags);
        assertNull(second.simpleTags);
    }

    @Test(expected = CancelledException.class)
    public void cancellationIsNotConvertedToSuccessfulHtmlFallback() throws Throwable {
        HomeBookmarkTagSupplement.fillMissing(true, Collections.singletonList(gallery(1)),
                requests -> { throw new CancelledException(); });
    }

    @Test(expected = InterruptedException.class)
    public void interruptionIsNotConvertedToSuccessfulHtmlFallback() throws Throwable {
        HomeBookmarkTagSupplement.fillMissing(true, Collections.singletonList(gallery(1)),
                requests -> { throw new InterruptedException(); });
    }

    @Test(expected = AssertionError.class)
    public void errorsAreNotSwallowed() throws Throwable {
        HomeBookmarkTagSupplement.fillMissing(true, Collections.singletonList(gallery(1)),
                requests -> { throw new AssertionError("fatal"); });
    }

    private static GalleryInfo gallery(long gid) {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = gid;
        gallery.token = "token" + gid;
        return gallery;
    }
}
