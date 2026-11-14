package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable, fixed-Chinese update contract for saved searches. */
public final class BookmarkUpdatePolicy {
    private static final Pattern POSITIVE_LANGUAGE = Pattern.compile(
            "(?i)(?<![-~])(?:language|lang|l)\\s*:\\s*\"?([^\"\\s$]+)");

    private BookmarkUpdatePolicy() {}

    public static Result resolve(QuickSearch search) {
        Result validation = validate(search);
        if (!validation.supported) return validation;
        ListUrlBuilder builder = new ListUrlBuilder();
        builder.set(search);
        String url = builder.build(true);
        return new Result(true, "", url, validation.signature);
    }

    /** Validates the saved-query contract without resolving a host-dependent URL. */
    public static Result validate(QuickSearch search) {
        if (search == null || search.id == null) {
            return Result.unsupported("invalid bookmark", "");
        }
        String rawSignature = querySignature(search);
        if (!isSupportedMode(search.mode)) {
            return Result.unsupported("unsupported mode", rawSignature);
        }
        Matcher matcher = POSITIVE_LANGUAGE.matcher(search.keyword == null ? "" : search.keyword);
        if (matcher.find() && !"chinese".equals(matcher.group(1).toLowerCase(Locale.ROOT))) {
            return Result.unsupported("language conflict", rawSignature);
        }
        return new Result(true, "", "", rawSignature);
    }

    static String querySignature(QuickSearch search) {
        if (search == null) return "";
        String input = search.mode + "|" + search.category + "|" + safe(search.keyword)
                + "|" + search.advanceSearch + "|" + search.minRating + "|"
                + search.pageFrom + "|" + search.pageTo;
        return QuerySignatureFactory.create(input, true);
    }

    private static boolean isSupportedMode(int mode) {
        return mode == ListUrlBuilder.MODE_NORMAL || mode == ListUrlBuilder.MODE_TAG
                || mode == ListUrlBuilder.MODE_UPLOADER || mode == ListUrlBuilder.MODE_FILTER;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class Result {
        public final boolean supported;
        public final String error;
        public final String url;
        public final String signature;

        Result(boolean supported, String error, String url, String signature) {
            this.supported = supported;
            this.error = error;
            this.url = url;
            this.signature = signature;
        }

        static Result unsupported(String error, String signature) {
            return new Result(false, error, "", signature);
        }
    }
}
