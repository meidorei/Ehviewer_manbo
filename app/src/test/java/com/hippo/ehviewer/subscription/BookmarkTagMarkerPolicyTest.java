package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.QuickSearch;

import org.junit.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class BookmarkTagMarkerPolicyTest {
    @Test public void htmlTagsWorkWithoutApiMetadataOrPageCount() {
        GalleryInfo gallery = new GalleryInfo();
        gallery.tgList = new ArrayList<>(Arrays.asList("artist:foo", "language:chinese"));
        BookmarkTagMarkerPolicy policy = compile(true,
                bookmark(ListUrlBuilder.MODE_TAG, "artist:foo", "Saved artist"));
        assertNull(gallery.simpleTags);
        assertEquals(0, gallery.pages);
        List<BookmarkTagMarkerPolicy.Match> result = policy.matchGallery(gallery);
        assertEquals(1, result.size());
        assertEquals("Saved artist", result.get(0).bookmarkName);
        assertNull(gallery.simpleTags);
        assertEquals(2, gallery.tgList.size());
    }

    @Test public void combinesHtmlAndApiTagsWithoutDuplicatingHints() {
        GalleryInfo gallery = new GalleryInfo();
        gallery.tgList = new ArrayList<>(Arrays.asList("artist:foo", "female:bar"));
        gallery.simpleTags = tags("language:chinese", "artist:foo");
        BookmarkTagMarkerPolicy policy = compile(true,
                bookmark(ListUrlBuilder.MODE_NORMAL, "artist:foo female:bar", "Both"));
        assertEquals(Arrays.asList("artist:foo", "female:bar"),
                tagNames(policy.matchGallery(gallery)));
        assertEquals(Arrays.asList("artist:foo", "female:bar"), gallery.tgList);
        assertEquals(Arrays.asList("language:chinese", "artist:foo"),
                Arrays.asList(gallery.simpleTags));
    }

    @Test public void galleryWithoutEitherTagSourceStillHasNoHint() {
        BookmarkTagMarkerPolicy policy = compile(false,
                bookmark(ListUrlBuilder.MODE_TAG, "artist:foo", "A"));
        assertTrue(policy.matchGallery(null).isEmpty());
        assertTrue(policy.matchGallery(new GalleryInfo()).isEmpty());
        GalleryInfo gallery = new GalleryInfo();
        gallery.simpleTags = tags("artist:foo");
        assertEquals(1, policy.matchGallery(gallery).size());
    }

    @Test public void singleTagUsesSavedNameAndExactTagIdentity() {
        BookmarkTagMarkerPolicy policy = compile(false,
                bookmark(ListUrlBuilder.MODE_TAG, "artist:foo", "My artist"));
        List<BookmarkTagMarkerPolicy.Match> result = policy.match(tags("artist:foo"));
        assertEquals(1, result.size());
        assertEquals("artist:foo", result.get(0).tag);
        assertEquals("My artist", result.get(0).bookmarkName);
        assertTrue(policy.match(tags("artist:foo bar")).isEmpty());
    }

    @Test public void combinationMarksOnlyThePresentConstituents() {
        BookmarkTagMarkerPolicy policy = compile(false,
                bookmark(ListUrlBuilder.MODE_NORMAL, "artist:foo female:bar", "Both"));
        List<BookmarkTagMarkerPolicy.Match> partial = policy.match(tags("artist:foo"));
        assertEquals(Arrays.asList("artist:foo"), tagNames(partial));
        assertNull(partial.get(0).bookmarkName);
        assertEquals(Arrays.asList("artist:foo", "female:bar"),
                tagNames(policy.match(tags("female:bar", "artist:foo"))));
    }

    @Test public void deduplicatesTagsAndPrefersFirstMatchingSingleBookmarkName() {
        BookmarkTagMarkerPolicy policy = compile(false,
                bookmark(ListUrlBuilder.MODE_NORMAL, "artist:foo female:bar", "Combined"),
                bookmark(ListUrlBuilder.MODE_TAG, "artist:foo", "First single"),
                bookmark(ListUrlBuilder.MODE_TAG, "artist:foo$", "Later single"));
        List<BookmarkTagMarkerPolicy.Match> result = policy.match(
                tags("female:bar", "artist:foo", "artist:foo"));
        assertEquals(Arrays.asList("artist:foo", "female:bar"), tagNames(result));
        assertEquals("First single", result.get(0).bookmarkName);
    }

    @Test public void keepsBookmarkOrderInsteadOfGalleryTagOrder() {
        BookmarkTagMarkerPolicy policy = compile(false,
                bookmark(ListUrlBuilder.MODE_TAG, "female:bar", "B"),
                bookmark(ListUrlBuilder.MODE_TAG, "artist:foo", "A"));
        assertEquals(Arrays.asList("female:bar", "artist:foo"),
                tagNames(policy.match(tags("artist:foo", "female:bar"))));
    }

    @Test public void normalizesQuotesDollarSuffixWhitespaceAndCase() {
        BookmarkTagMarkerPolicy policy = compile(false,
                bookmark(ListUrlBuilder.MODE_NORMAL,
                        "ARTIST:\"Example   Artist\"$ female:\"Bar Baz\"", "Combination"));
        assertEquals(Arrays.asList("artist:example artist", "female:bar baz"),
                tagNames(policy.match(tags(" Artist:EXAMPLE   Artist ", "female:bar baz"))));
    }

    @Test public void tagModeKeepsUnquotedMultiwordValueWhole() {
        QuickSearch bookmark = bookmark(ListUrlBuilder.MODE_TAG, "artist:example artist", "A");
        assertEquals(1, compile(false, bookmark).match(tags("artist:example artist")).size());
        assertEquals(1, compile(true, bookmark).match(
                tags("artist:example artist", "language:chinese")).size());
    }

    @Test public void automaticChineseIsAGateButNeverAnExtraMarker() {
        QuickSearch bookmark = bookmark(ListUrlBuilder.MODE_TAG, "artist:foo", "A");
        BookmarkTagMarkerPolicy policy = compile(true, bookmark);
        assertEquals(Arrays.asList("artist:foo"),
                tagNames(policy.match(tags("artist:foo", "language:chinese"))));
        assertTrue(policy.match(tags("artist:foo", "language:english")).isEmpty());
        assertTrue(policy.match(tags("artist:foo")).isEmpty());
        assertEquals(1, compile(false, bookmark).match(tags("artist:foo")).size());
    }

    @Test public void explicitLanguageOverridesChineseAndRemainsAMarker() {
        BookmarkTagMarkerPolicy policy = compile(true,
                bookmark(ListUrlBuilder.MODE_NORMAL, "artist:foo l:english$", "English"));
        assertEquals(Arrays.asList("artist:foo", "language:english"),
                tagNames(policy.match(tags("artist:foo", "language:english"))));
        assertTrue(policy.match(tags("artist:foo", "language:chinese")).isEmpty());
        assertEquals(Arrays.asList("language:english"),
                tagNames(policy.match(tags("language:english"))));
    }

    @Test public void languageTagBookmarkDoesNotGetAnImplicitChineseConstraint() {
        BookmarkTagMarkerPolicy policy = compile(true,
                bookmark(ListUrlBuilder.MODE_TAG, "language:english", "English"));
        assertEquals("English", policy.match(tags("language:english")).get(0).bookmarkName);
    }

    @Test public void extraFiltersAndNegativeTagsDoNotHidePositiveTagHints() {
        QuickSearch bookmark = bookmark(ListUrlBuilder.MODE_FILTER,
                "artist:foo -female:bar", "A");
        bookmark.category = 0;
        bookmark.minRating = 5;
        bookmark.pageFrom = 100;
        bookmark.pageTo = 101;
        assertEquals(Arrays.asList("artist:foo"),
                tagNames(compile(false, bookmark).match(tags("artist:foo", "female:bar"))));
        assertTrue(compile(false, bookmark).match(tags("female:bar")).isEmpty());
    }

    @Test public void ignoresUnsupportedQueriesInsteadOfGuessingFromWords() {
        String[] queries = {"ordinary title", "artist:foo title", "artist:foo OR female:bar",
                "artist:foo*", "artist:foo?", "~artist:foo", "f:bar", "artist:\"foo",
                "uploader:someone", "artist:foo uploader:someone", "-uploader:someone"};
        for (String query : queries) {
            assertTrue(query, compile(false,
                    bookmark(ListUrlBuilder.MODE_NORMAL, query, "Unsupported"))
                    .match(tags("artist:foo", "female:bar")).isEmpty());
        }
        assertTrue(compile(false,
                bookmark(ListUrlBuilder.MODE_UPLOADER, "someone", "Uploader"),
                bookmark(ListUrlBuilder.MODE_WHATS_HOT, "artist:foo", "Hot"))
                .match(tags("artist:foo")).isEmpty());
    }

    @Test public void missingMetadataAndEmptyBookmarksHaveNoMarkers() {
        BookmarkTagMarkerPolicy policy = compile(false,
                bookmark(ListUrlBuilder.MODE_TAG, "artist:foo", "A"));
        assertTrue(policy.match(null).isEmpty());
        assertTrue(policy.match(tags()).isEmpty());
        assertTrue(policy.match(tags((String) null, "malformed")).isEmpty());
        assertTrue(BookmarkTagMarkerPolicy.compile(null, true)
                .match(tags("artist:foo")).isEmpty());
        assertTrue(compile(false, null,
                bookmark(ListUrlBuilder.MODE_NORMAL, "-artist:foo", "Negative"))
                .match(tags("artist:foo")).isEmpty());
    }

    @Test public void absentSavedNameFallsBackToTagAndSnapshotDoesNotMutate() {
        QuickSearch bookmark = bookmark(ListUrlBuilder.MODE_TAG, "artist:foo", "  ");
        BookmarkTagMarkerPolicy policy = compile(false, bookmark);
        bookmark.name = "Changed";
        bookmark.keyword = "artist:bar";
        List<BookmarkTagMarkerPolicy.Match> result = policy.match(tags("artist:foo"));
        assertEquals(1, result.size());
        assertNull(result.get(0).bookmarkName);
        assertTrue(policy.match(tags("artist:bar")).isEmpty());
    }

    @Test public void namePreferenceAppliesOnlyToLanguageEligibleBookmarks() {
        BookmarkTagMarkerPolicy policy = compile(true,
                bookmark(ListUrlBuilder.MODE_NORMAL, "artist:foo language:english", "Both"),
                bookmark(ListUrlBuilder.MODE_TAG, "artist:foo", "Chinese only"));
        List<BookmarkTagMarkerPolicy.Match> result = policy.match(
                tags("artist:foo", "language:english"));
        assertNull(result.get(0).bookmarkName);
    }

    @Test public void savedStandardTagNameIsTranslatedWithoutChangingSnapshot() {
        BookmarkTagMarkerPolicy.Match match = compile(false,
                bookmark(ListUrlBuilder.MODE_TAG, "female:bar", "female:bar"))
                .match(tags("female:bar")).get(0);
        assertEquals("女性：译名", match.displayLabel(true, tag -> {
            assertEquals("female:bar", tag);
            return "女性：译名";
        }));
        assertEquals("female:bar", match.bookmarkName);
        assertEquals("female:bar", match.tag);
    }

    @Test public void savedTagAliasesQuotesAndCaseUseCanonicalLookup() {
        BookmarkTagMarkerPolicy.Match match = compile(false,
                bookmark(ListUrlBuilder.MODE_TAG, "language:english", "L:\"English\"$"))
                .match(tags("language:english")).get(0);
        assertEquals("语言：英语", match.displayLabel(true, tag -> {
            assertEquals("language:english", tag);
            return "语言：英语";
        }));
    }

    @Test public void customTranslatedAndUnrelatedNamesArePreserved() {
        for (String name : Arrays.asList("My collection", "女性：译名", "female:other")) {
            BookmarkTagMarkerPolicy.Match match = compile(false,
                    bookmark(ListUrlBuilder.MODE_TAG, "female:bar", name))
                    .match(tags("female:bar")).get(0);
            assertEquals(name, match.displayLabel(true, tag -> {
                throw new AssertionError("Custom names must not be sent to the tag dictionary");
            }));
        }
    }

    @Test public void disabledTranslationsPreserveSavedNameWithoutLookup() {
        BookmarkTagMarkerPolicy.Match match = compile(false,
                bookmark(ListUrlBuilder.MODE_TAG, "female:bar", "FEMALE:Bar$"))
                .match(tags("female:bar")).get(0);
        assertEquals("FEMALE:Bar$", match.displayLabel(false, tag -> {
            throw new AssertionError("Disabled translations must not query the dictionary");
        }));
    }

    @Test public void missingTranslationPreservesOriginalSavedSpelling() {
        BookmarkTagMarkerPolicy.Match match = compile(false,
                bookmark(ListUrlBuilder.MODE_TAG, "female:bar", "FEMALE:Bar$"))
                .match(tags("female:bar")).get(0);
        assertEquals("FEMALE:Bar$", match.displayLabel(true, tag -> null));
        assertEquals("FEMALE:Bar$", match.displayLabel(true, tag -> ""));
        assertEquals("FEMALE:Bar$", match.displayLabel(true, tag -> tag));
    }

    @Test public void combinationLabelsStillFollowTranslationSwitchAndFallback() {
        BookmarkTagMarkerPolicy.Match match = compile(false,
                bookmark(ListUrlBuilder.MODE_NORMAL, "female:bar artist:foo", "Both"))
                .match(tags("female:bar")).get(0);
        assertEquals("女性：译名", match.displayLabel(true, tag -> "女性：译名"));
        assertEquals("female:bar", match.displayLabel(true, tag -> null));
        assertEquals("female:bar", match.displayLabel(false, tag -> {
            throw new AssertionError("Disabled translations must not query the dictionary");
        }));
    }

    @Test public void partiallyTranslatedSavedNameUsesOriginalTagDictionaryKey() {
        BookmarkTagMarkerPolicy.Match match = compile(false,
                bookmark(ListUrlBuilder.MODE_TAG, "female:focus handjob", "女性:focus handjob"))
                .match(tags("female:focus handjob")).get(0);
        assertEquals("女性:高比例手交", match.displayLabel(true,
                focusHandjobTranslator("女性")));
        assertEquals("女性:focus handjob", match.bookmarkName);
        assertEquals("female:focus handjob", match.tag);
    }

    @Test public void partiallyTranslatedNamesSupportFullWidthColonQuotesAndCase() {
        BookmarkTagMarkerPolicy.Match match = compile(false,
                bookmark(ListUrlBuilder.MODE_TAG, "female:focus handjob",
                        "女性 ： \"FOCUS   HANDJOB\"$"))
                .match(tags("female:focus handjob")).get(0);
        assertEquals("女性:高比例手交", match.displayLabel(true,
                focusHandjobTranslator("女性")));
    }

    @Test public void customNamespaceAndDifferentTagValueAreNotReplaced() {
        for (String name : Arrays.asList("收藏:focus handjob", "女性:other", "女性：高比例手交")) {
            BookmarkTagMarkerPolicy.Match match = compile(false,
                    bookmark(ListUrlBuilder.MODE_TAG, "female:focus handjob", name))
                    .match(tags("female:focus handjob")).get(0);
            assertEquals(name, match.displayLabel(true, new BookmarkTagMarkerPolicy.TagTranslator() {
                @Override public String translate(String tag) {
                    throw new AssertionError("Custom titles must be preserved");
                }

                @Override public String translateNamespace(String namespace) {
                    return "女性";
                }
            }));
        }
    }

    @Test public void partiallyTranslatedNameWaitsForAvailableNamespaceDictionary() {
        BookmarkTagMarkerPolicy.Match match = compile(false,
                bookmark(ListUrlBuilder.MODE_TAG, "female:focus handjob", "女性:focus handjob"))
                .match(tags("female:focus handjob")).get(0);
        assertEquals("女性:focus handjob", match.displayLabel(true,
                focusHandjobTranslator(null)));
        assertEquals("女性:高比例手交", match.displayLabel(true,
                focusHandjobTranslator("女性")));
    }

    @Test public void disabledTranslationsDoNotLookUpPartialNames() {
        BookmarkTagMarkerPolicy.Match match = compile(false,
                bookmark(ListUrlBuilder.MODE_TAG, "female:focus handjob", "女性:focus handjob"))
                .match(tags("female:focus handjob")).get(0);
        assertEquals("女性:focus handjob", match.displayLabel(false,
                new BookmarkTagMarkerPolicy.TagTranslator() {
                    @Override public String translate(String tag) {
                        throw new AssertionError("Disabled translations must not query tags");
                    }

                    @Override public String translateNamespace(String namespace) {
                        throw new AssertionError("Disabled translations must not query namespaces");
                    }
                }));
    }

    private static BookmarkTagMarkerPolicy.TagTranslator focusHandjobTranslator(String namespaceName) {
        return new BookmarkTagMarkerPolicy.TagTranslator() {
            @Override public String translate(String tag) {
                assertEquals("female:focus handjob", tag);
                return "女性:高比例手交";
            }

            @Override public String translateNamespace(String namespace) {
                assertEquals("female", namespace);
                return namespaceName;
            }
        };
    }

    private static BookmarkTagMarkerPolicy compile(boolean chinese, QuickSearch... bookmarks) {
        return BookmarkTagMarkerPolicy.compile(Arrays.asList(bookmarks), chinese);
    }

    private static QuickSearch bookmark(int mode, String query, String name) {
        QuickSearch bookmark = new QuickSearch();
        bookmark.mode = mode;
        bookmark.keyword = query;
        bookmark.name = name;
        return bookmark;
    }

    private static String[] tags(String... tags) {
        return tags;
    }

    private static List<String> tagNames(List<BookmarkTagMarkerPolicy.Match> matches) {
        return matches.stream().map(match -> match.tag).collect(Collectors.toList());
    }
}
