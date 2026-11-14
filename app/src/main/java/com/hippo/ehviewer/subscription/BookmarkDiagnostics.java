package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Local, read-only diagnostics for saved bookmark queries. */
public final class BookmarkDiagnostics {
    private BookmarkDiagnostics() {}

    public static Result analyze(List<QuickSearch> bookmarks) {
        List<QuickSearch> source = bookmarks == null
                ? Collections.emptyList() : bookmarks;
        Map<DuplicateKey, List<QuickSearch>> grouped = new LinkedHashMap<>();
        List<FallbackItem> fallbacks = new ArrayList<>();
        for (QuickSearch search : source) {
            if (search == null) continue;
            CanonicalQuery canonical = canonicalize(search);
            DuplicateKey key = new DuplicateKey(search, canonical);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(search);

            BookmarkUpdatePolicy.Result policy = BookmarkUpdatePolicy.validate(search);
            if (!policy.supported) continue;
            BookmarkGlobalMatcher.Result matcher = BookmarkGlobalMatcher.compile(search);
            if (!matcher.exact) {
                fallbacks.add(new FallbackItem(search, matcher.reason));
            }
        }

        List<DuplicateGroup> duplicates = new ArrayList<>();
        int duplicateBookmarks = 0;
        for (Map.Entry<DuplicateKey, List<QuickSearch>> entry : grouped.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            duplicates.add(new DuplicateGroup(
                    entry.getKey().canonicalQuery, entry.getValue()));
            duplicateBookmarks += entry.getValue().size();
        }
        return new Result(source.size(), duplicateBookmarks, duplicates, fallbacks);
    }

    static String canonicalKeyword(QuickSearch search) {
        return canonicalize(search).value;
    }

    private static CanonicalQuery canonicalize(QuickSearch search) {
        String keyword = search.keyword == null ? "" : search.keyword;
        if (search.mode == ListUrlBuilder.MODE_TAG) {
            String tag = BookmarkQueryNormalizer.normalizeStandaloneTag(keyword);
            return tag == null
                    ? new CanonicalQuery(
                            BookmarkQueryNormalizer.conservativeKeyword(keyword), false)
                    : new CanonicalQuery(tag, true);
        }
        if (search.mode == ListUrlBuilder.MODE_UPLOADER) {
            return new CanonicalQuery(BookmarkQueryNormalizer.cleanValue(keyword)
                    .toLowerCase(Locale.ROOT), false);
        }
        if (search.mode == ListUrlBuilder.MODE_NORMAL
                || search.mode == ListUrlBuilder.MODE_FILTER) {
            BookmarkQueryNormalizer.ParseResult parsed =
                    BookmarkQueryNormalizer.parseExactQuery(keyword);
            if (parsed.exact) {
                return new CanonicalQuery(
                        BookmarkQueryNormalizer.canonicalExactQuery(parsed.tokens),
                        search.mode == ListUrlBuilder.MODE_NORMAL
                                && isSinglePositiveStandardTag(parsed.tokens));
            }
        }
        return new CanonicalQuery(
                BookmarkQueryNormalizer.conservativeKeyword(keyword), false);
    }

    private static boolean isSinglePositiveStandardTag(
            List<BookmarkQueryNormalizer.QueryToken> tokens) {
        if (tokens.size() != 1) return false;
        BookmarkQueryNormalizer.QueryToken token = tokens.get(0);
        return !token.negative && !"uploader".equals(token.namespace);
    }

    public static final class Result {
        public final int totalBookmarks;
        public final int duplicateBookmarks;
        public final List<DuplicateGroup> duplicateGroups;
        public final List<FallbackItem> fallbackItems;

        private Result(int totalBookmarks, int duplicateBookmarks,
                       List<DuplicateGroup> duplicateGroups,
                       List<FallbackItem> fallbackItems) {
            this.totalBookmarks = totalBookmarks;
            this.duplicateBookmarks = duplicateBookmarks;
            this.duplicateGroups = Collections.unmodifiableList(
                    new ArrayList<>(duplicateGroups));
            this.fallbackItems = Collections.unmodifiableList(new ArrayList<>(fallbackItems));
        }
    }

    public static final class DuplicateGroup {
        public final String canonicalQuery;
        public final List<QuickSearch> bookmarks;

        private DuplicateGroup(String canonicalQuery, List<QuickSearch> bookmarks) {
            this.canonicalQuery = canonicalQuery;
            this.bookmarks = Collections.unmodifiableList(new ArrayList<>(bookmarks));
        }
    }

    public static final class FallbackItem {
        public final QuickSearch bookmark;
        public final BookmarkGlobalMatcher.FallbackReason reason;

        private FallbackItem(QuickSearch bookmark,
                             BookmarkGlobalMatcher.FallbackReason reason) {
            this.bookmark = bookmark;
            this.reason = reason;
        }
    }

    private static final class CanonicalQuery {
        final String value;
        final boolean crossModeTagEquivalent;

        CanonicalQuery(String value, boolean crossModeTagEquivalent) {
            this.value = value;
            this.crossModeTagEquivalent = crossModeTagEquivalent;
        }
    }

    private static final class DuplicateKey {
        final int mode;
        final int category;
        final int advanceSearch;
        final int minRating;
        final int pageFrom;
        final int pageTo;
        final String canonicalQuery;

        DuplicateKey(QuickSearch search, CanonicalQuery canonical) {
            mode = canonical.crossModeTagEquivalent
                    ? ListUrlBuilder.MODE_TAG : search.mode;
            category = search.category;
            advanceSearch = search.advanceSearch;
            minRating = search.minRating;
            pageFrom = search.pageFrom;
            pageTo = search.pageTo;
            canonicalQuery = canonical.value;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof DuplicateKey)) return false;
            DuplicateKey that = (DuplicateKey) other;
            return mode == that.mode && category == that.category
                    && advanceSearch == that.advanceSearch
                    && minRating == that.minRating && pageFrom == that.pageFrom
                    && pageTo == that.pageTo
                    && Objects.equals(canonicalQuery, that.canonicalQuery);
        }

        @Override public int hashCode() {
            return Objects.hash(mode, category, advanceSearch, minRating,
                    pageFrom, pageTo, canonicalQuery);
        }
    }
}
