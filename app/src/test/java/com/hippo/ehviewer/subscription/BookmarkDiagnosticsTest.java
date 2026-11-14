package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class BookmarkDiagnosticsTest {
    @Test public void equivalentTagSpellingsShareOneDuplicateGroup() {
        BookmarkDiagnostics.Result result = BookmarkDiagnostics.analyze(Arrays.asList(
                search(1, ListUrlBuilder.MODE_NORMAL, "female:mom"),
                search(2, ListUrlBuilder.MODE_NORMAL, "female:mom$"),
                search(3, ListUrlBuilder.MODE_NORMAL, "\"female:mom\""),
                search(4, ListUrlBuilder.MODE_NORMAL, "female:\"mom\"")));

        assertEquals(1, result.duplicateGroups.size());
        assertEquals(4, result.duplicateBookmarks);
        assertEquals("female:mom", result.duplicateGroups.get(0).canonicalQuery);
        assertTrue(result.fallbackItems.isEmpty());
    }

    @Test public void exactAndTagsNormalizeOrderAliasesCaseAndWhitespace() {
        BookmarkDiagnostics.Result result = BookmarkDiagnostics.analyze(Arrays.asList(
                search(1, ListUrlBuilder.MODE_NORMAL,
                        " Female:Mom   -male:SON lang:CHINESE$ "),
                search(2, ListUrlBuilder.MODE_NORMAL,
                        "language:\"chinese\" -male:son$ female:mom$")));

        assertEquals(1, result.duplicateGroups.size());
        assertEquals("-male:son female:mom language:chinese",
                result.duplicateGroups.get(0).canonicalQuery);
    }

    @Test public void tagAndNormalSingleExactTagShareOneDuplicateGroup() {
        QuickSearch tag = search(1, ListUrlBuilder.MODE_TAG, "female:mom");
        QuickSearch normal = search(2, ListUrlBuilder.MODE_NORMAL, "female:\"mom$\"");
        QuickSearch rated = search(3, ListUrlBuilder.MODE_NORMAL, "female:mom");
        rated.minRating = 4;
        QuickSearch paged = search(4, ListUrlBuilder.MODE_NORMAL, "female:mom");
        paged.pageFrom = 10;

        BookmarkDiagnostics.Result result =
                BookmarkDiagnostics.analyze(Arrays.asList(tag, normal, rated, paged));

        assertEquals(1, result.duplicateGroups.size());
        assertEquals(Arrays.asList(tag, normal),
                result.duplicateGroups.get(0).bookmarks);
    }

    @Test public void spacedTagAndNormalExactTagShareOneDuplicateGroup() {
        BookmarkDiagnostics.Result result = BookmarkDiagnostics.analyze(Arrays.asList(
                search(1, ListUrlBuilder.MODE_TAG, "female:aaa bbb"),
                search(2, ListUrlBuilder.MODE_NORMAL, "female:\"aaa bbb$\"")));

        assertEquals(1, result.duplicateGroups.size());
        assertEquals(2, result.duplicateBookmarks);
        assertEquals("female:aaa bbb",
                result.duplicateGroups.get(0).canonicalQuery);
    }

    @Test public void nonExactAndOtherModesDoNotCrossMatchTagMode() {
        BookmarkDiagnostics.Result result = BookmarkDiagnostics.analyze(Arrays.asList(
                search(1, ListUrlBuilder.MODE_TAG, "female:aaa bbb"),
                search(2, ListUrlBuilder.MODE_NORMAL, "female:aaa bbb"),
                search(3, ListUrlBuilder.MODE_FILTER, "female:\"aaa bbb$\""),
                search(4, ListUrlBuilder.MODE_NORMAL,
                        "female:\"aaa bbb$\" male:ccc"),
                search(5, ListUrlBuilder.MODE_NORMAL, "-female:\"aaa bbb$\""),
                search(6, ListUrlBuilder.MODE_NORMAL, "female:\"aaa bbb*\"")));

        assertTrue(result.duplicateGroups.isEmpty());
    }

    @Test public void complexQueriesAreComparedConservatively() {
        BookmarkDiagnostics.Result result = BookmarkDiagnostics.analyze(Arrays.asList(
                search(1, ListUrlBuilder.MODE_NORMAL, "female:mom OR male:son"),
                search(2, ListUrlBuilder.MODE_NORMAL, "male:son OR female:mom")));

        assertTrue(result.duplicateGroups.isEmpty());
        assertEquals(2, result.fallbackItems.size());
    }

    @Test public void onlySupportedQueriesThatDirectlyFallbackAreReported() {
        QuickSearch mixed = search(1, ListUrlBuilder.MODE_NORMAL, "female:xxx aaa");
        QuickSearch wildcard = search(2, ListUrlBuilder.MODE_NORMAL, "female:xxx*");
        QuickSearch negativeUploader =
                search(3, ListUrlBuilder.MODE_NORMAL, "-uploader:someone");
        QuickSearch exact = search(4, ListUrlBuilder.MODE_NORMAL,
                "female:xxx artist:someone");
        QuickSearch uploader =
                search(5, ListUrlBuilder.MODE_UPLOADER, "someone");
        QuickSearch languageConflict =
                search(6, ListUrlBuilder.MODE_NORMAL, "language:english$");
        QuickSearch unsupported =
                search(7, ListUrlBuilder.MODE_TOP_LIST, "female:xxx");

        BookmarkDiagnostics.Result result = BookmarkDiagnostics.analyze(Arrays.asList(
                mixed, wildcard, negativeUploader, exact, uploader,
                languageConflict, unsupported));

        assertEquals(3, result.fallbackItems.size());
        assertEquals(BookmarkGlobalMatcher.FallbackReason.FULL_TEXT_KEYWORD,
                result.fallbackItems.get(0).reason);
        assertEquals(BookmarkGlobalMatcher.FallbackReason.FUZZY_EXPRESSION,
                result.fallbackItems.get(1).reason);
        assertEquals(BookmarkGlobalMatcher.FallbackReason.NEGATIVE_UPLOADER,
                result.fallbackItems.get(2).reason);
    }

    @Test public void nullInputProducesAnEmptyImmutableReport() {
        BookmarkDiagnostics.Result result = BookmarkDiagnostics.analyze(null);
        assertEquals(0, result.totalBookmarks);
        assertEquals(Collections.emptyList(), result.duplicateGroups);
        assertEquals(Collections.emptyList(), result.fallbackItems);
    }

    private static QuickSearch search(long id, int mode, String keyword) {
        QuickSearch search = new QuickSearch();
        search.id = id;
        search.name = "bookmark-" + id;
        search.mode = mode;
        search.keyword = keyword;
        search.category = -1;
        search.advanceSearch = -1;
        search.minRating = -1;
        search.pageFrom = -1;
        search.pageTo = -1;
        return search;
    }
}
