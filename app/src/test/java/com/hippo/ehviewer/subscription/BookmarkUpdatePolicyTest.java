package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;

import org.junit.Test;

public class BookmarkUpdatePolicyTest {
    @Test public void explicitNonChineseLanguageIsRejected() {
        QuickSearch search = search(ListUrlBuilder.MODE_NORMAL,
                "artist:foo language:english$");
        assertFalse(BookmarkUpdatePolicy.resolve(search).supported);
        assertFalse(BookmarkUpdatePolicy.resolve(search(
                ListUrlBuilder.MODE_NORMAL, "artist:foo l:english$")).supported);
        assertFalse(BookmarkUpdatePolicy.resolve(search(
                ListUrlBuilder.MODE_NORMAL, "artist:foo lang:english$")).supported);
    }

    @Test public void specialModesAreRejected() {
        assertFalse(BookmarkUpdatePolicy.resolve(
                search(ListUrlBuilder.MODE_TOP_LIST, "artist:foo")).supported);
    }

    @Test public void renameKeepsSignatureButQueryChangeDoesNot() {
        QuickSearch original = search(ListUrlBuilder.MODE_NORMAL, "artist:foo");
        original.name = "A";
        QuickSearch renamed = search(ListUrlBuilder.MODE_NORMAL, "artist:foo");
        renamed.name = "B";
        QuickSearch changed = search(ListUrlBuilder.MODE_NORMAL, "artist:bar");
        String first = BookmarkUpdatePolicy.querySignature(original);
        assertEquals(first, BookmarkUpdatePolicy.querySignature(renamed));
        assertNotEquals(first, BookmarkUpdatePolicy.querySignature(changed));
    }

    @Test public void unsupportedQueryStillHasAStableSignature() {
        BookmarkUpdatePolicy.Result result = BookmarkUpdatePolicy.resolve(
                search(ListUrlBuilder.MODE_NORMAL, "language:english$"));
        assertFalse(result.supported);
        assertFalse(result.signature.isEmpty());
    }

    @Test public void globalMatcherSupportsMultipleStandardTagsWithoutDollarSuffix() {
        QuickSearch search = search(ListUrlBuilder.MODE_NORMAL,
                "artist:foo female:\"bar baz\"");
        BookmarkGlobalMatcher.Result result = BookmarkGlobalMatcher.compile(search);
        assertTrue(result.exact);
        GalleryInfo gallery = gallery("someone", 4.5f, 30,
                "artist:foo", "female:bar baz", "language:chinese");
        assertTrue(result.matcher.matches(gallery));
        gallery.simpleTags = new String[]{"artist:foo", "language:chinese"};
        assertFalse(result.matcher.matches(gallery));
    }

    @Test public void globalMatcherSupportsUploaderAndLocalFilters() {
        QuickSearch search = search(ListUrlBuilder.MODE_UPLOADER, "UploaderName");
        search.category = EhConfig.MANGA;
        search.minRating = 4;
        search.pageFrom = 20;
        search.pageTo = 40;
        BookmarkGlobalMatcher.Result result = BookmarkGlobalMatcher.compile(search);
        assertTrue(result.exact);
        assertTrue(result.matcher.matches(gallery("uploadername", 4.5f, 30,
                "language:chinese")));
        assertFalse(result.matcher.matches(gallery("another", 4.5f, 30,
                "language:chinese")));
        assertTrue(BookmarkGlobalMatcher.compile(
                search(ListUrlBuilder.MODE_NORMAL, "uploader:\"UploaderName\"")).exact);
        assertFalse(BookmarkGlobalMatcher.compile(
                search(ListUrlBuilder.MODE_NORMAL, "UploaderName")).exact);
    }

    @Test public void globalMatcherFallsBackForFullTextAndOperators() {
        assertFalse(BookmarkGlobalMatcher.compile(
                search(ListUrlBuilder.MODE_NORMAL, "ordinary title words")).exact);
        assertFalse(BookmarkGlobalMatcher.compile(
                search(ListUrlBuilder.MODE_NORMAL, "artist:foo OR female:bar")).exact);
        assertFalse(BookmarkGlobalMatcher.compile(
                search(ListUrlBuilder.MODE_NORMAL, "artist:foo*")).exact);
    }

    @Test public void tagModeIsExactEvenWhenStoredWithoutDollarSuffix() {
        BookmarkGlobalMatcher.Result result = BookmarkGlobalMatcher.compile(
                search(ListUrlBuilder.MODE_TAG, "female:bbw"));
        assertTrue(result.exact);
        assertTrue(result.matcher.matches(gallery("uploader", 4.5f, 30,
                "female:bbw", "language:chinese")));
        assertFalse(result.matcher.matches(gallery("uploader", 4.5f, 30,
                "female:bbw expansion", "language:chinese")));
    }

    @Test public void everyStandardNamespaceIsExactWithOrWithoutDollarSuffix() {
        String[] namespaces = {"artist", "group", "parody", "character", "female",
                "male", "misc", "language", "cosplayer", "mixed", "other", "reclass"};
        for (String namespace : namespaces) {
            assertTrue(namespace, BookmarkGlobalMatcher.compile(
                    search(ListUrlBuilder.MODE_NORMAL, namespace + ":sample")).exact);
            assertTrue(namespace + "$", BookmarkGlobalMatcher.compile(
                    search(ListUrlBuilder.MODE_NORMAL, namespace + ":sample$")).exact);
        }
    }

    @Test public void globalMatcherSupportsNegativeStandardTags() {
        BookmarkGlobalMatcher.Result result = BookmarkGlobalMatcher.compile(
                search(ListUrlBuilder.MODE_NORMAL, "artist:foo -female:bbw"));
        assertTrue(result.exact);
        assertTrue(result.matcher.matches(gallery("uploader", 4.5f, 30,
                "artist:foo", "language:chinese")));
        assertFalse(result.matcher.matches(gallery("uploader", 4.5f, 30,
                "artist:foo", "female:bbw", "language:chinese")));
    }

    @Test public void bareWordsStillFallBackEvenWithDollarSuffixOrMixedTags() {
        assertFalse(BookmarkGlobalMatcher.compile(
                search(ListUrlBuilder.MODE_NORMAL, "bbw")).exact);
        assertFalse(BookmarkGlobalMatcher.compile(
                search(ListUrlBuilder.MODE_NORMAL, "bbw$")).exact);
        assertFalse(BookmarkGlobalMatcher.compile(
                search(ListUrlBuilder.MODE_NORMAL, "female:bbw titleword")).exact);
    }

    private static QuickSearch search(int mode, String keyword) {
        QuickSearch search = new QuickSearch();
        search.id = 7L;
        search.mode = mode;
        search.keyword = keyword;
        search.category = -1;
        search.advanceSearch = -1;
        search.minRating = -1;
        search.pageFrom = -1;
        search.pageTo = -1;
        return search;
    }

    private static GalleryInfo gallery(String uploader, float rating, int pages,
                                       String... tags) {
        GalleryInfo gallery = new GalleryInfo();
        gallery.category = EhConfig.MANGA;
        gallery.uploader = uploader;
        gallery.rating = rating;
        gallery.pages = pages;
        gallery.simpleTags = tags;
        return gallery;
    }
}
