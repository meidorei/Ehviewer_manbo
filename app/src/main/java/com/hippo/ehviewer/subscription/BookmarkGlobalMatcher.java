package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative local evaluator for bookmark queries during a shared global Chinese scan.
 * Any expression that cannot be reproduced exactly from GalleryInfo is rejected for fallback.
 */
public final class BookmarkGlobalMatcher {
    public static Result compile(QuickSearch search) {
        if (search == null) return Result.fallback(FallbackReason.INVALID_BOOKMARK);
        List<String> positiveTags = new ArrayList<>();
        List<String> negativeTags = new ArrayList<>();
        List<String> uploaders = new ArrayList<>();

        if (search.mode == ListUrlBuilder.MODE_TAG) {
            String tag = BookmarkQueryNormalizer.normalizeStandaloneTag(search.keyword);
            if (tag == null) return Result.fallback(FallbackReason.TAG_QUERY_NOT_EXACT);
            positiveTags.add(tag);
        } else if (search.mode == ListUrlBuilder.MODE_UPLOADER) {
            String uploader = BookmarkQueryNormalizer.cleanValue(search.keyword);
            if (uploader.isEmpty()) return Result.fallback(FallbackReason.EMPTY_UPLOADER);
            uploaders.add(uploader);
        } else if (search.mode == ListUrlBuilder.MODE_NORMAL
                || search.mode == ListUrlBuilder.MODE_FILTER) {
            BookmarkQueryNormalizer.ParseResult parsed =
                    BookmarkQueryNormalizer.parseExactQuery(search.keyword);
            if (!parsed.exact) return Result.fallback(parsed.reason);
            for (BookmarkQueryNormalizer.QueryToken token : parsed.tokens) {
                if ("uploader".equals(token.namespace)) {
                    uploaders.add(token.value);
                } else {
                    String tag = SubscriptionRepository.normalizeTagName(
                            token.namespace + ':' + token.value);
                    (token.negative ? negativeTags : positiveTags).add(tag);
                }
            }
        } else {
            return Result.fallback(FallbackReason.UNSUPPORTED_MODE);
        }

        return Result.exact(new BookmarkGlobalMatcher(
                search.category, search.minRating, search.pageFrom, search.pageTo,
                positiveTags, negativeTags, uploaders));
    }

    private final int categories;
    private final int minRating;
    private final int pageFrom;
    private final int pageTo;
    private final List<String> positiveTags;
    private final List<String> negativeTags;
    private final List<String> uploaders;

    private BookmarkGlobalMatcher(int categories, int minRating, int pageFrom, int pageTo,
                                  List<String> positiveTags, List<String> negativeTags,
                                  List<String> uploaders) {
        this.categories = categories;
        this.minRating = minRating;
        this.pageFrom = pageFrom;
        this.pageTo = pageTo;
        this.positiveTags = Collections.unmodifiableList(new ArrayList<>(positiveTags));
        this.negativeTags = Collections.unmodifiableList(new ArrayList<>(negativeTags));
        this.uploaders = Collections.unmodifiableList(new ArrayList<>(uploaders));
    }

    public boolean matches(GalleryInfo gallery) {
        if (gallery == null) return false;
        if (categories != EhUtils.NONE
                && (categories == 0 || (gallery.category & categories) == 0)) {
            return false;
        }
        if (minRating >= 0 && gallery.rating < minRating) return false;
        if (pageFrom >= 0 && gallery.pages < pageFrom) return false;
        if (pageTo >= 0 && gallery.pages > pageTo) return false;
        for (String uploader : uploaders) {
            if (gallery.uploader == null || !uploader.equalsIgnoreCase(gallery.uploader.trim())) {
                return false;
            }
        }
        if (!positiveTags.isEmpty() || !negativeTags.isEmpty()) {
            if (gallery.simpleTags == null) return false;
            Set<String> tags = new HashSet<>();
            for (String raw : gallery.simpleTags) {
                tags.add(SubscriptionRepository.normalizeTagName(raw));
            }
            if (!tags.containsAll(positiveTags)) return false;
            for (String tag : negativeTags) {
                if (tags.contains(tag)) return false;
            }
        }
        return true;
    }

    public enum FallbackReason {
        NONE,
        INVALID_BOOKMARK,
        TAG_QUERY_NOT_EXACT,
        EMPTY_UPLOADER,
        COMPLEX_OPERATOR,
        FULL_TEXT_KEYWORD,
        FUZZY_EXPRESSION,
        NEGATIVE_UPLOADER,
        UNSUPPORTED_FIELD,
        UNSUPPORTED_MODE
    }

    public static final class Result {
        public final boolean exact;
        public final FallbackReason reason;
        public final BookmarkGlobalMatcher matcher;

        private Result(boolean exact, FallbackReason reason, BookmarkGlobalMatcher matcher) {
            this.exact = exact;
            this.reason = reason;
            this.matcher = matcher;
        }

        private static Result exact(BookmarkGlobalMatcher matcher) {
            return new Result(true, FallbackReason.NONE, matcher);
        }

        private static Result fallback(FallbackReason reason) {
            return new Result(false, reason, null);
        }
    }
}
