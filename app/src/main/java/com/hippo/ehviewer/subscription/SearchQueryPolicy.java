package com.hippo.ehviewer.subscription;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.ListUrlBuilder;

/** Stable query contract shared by search and follow-update code. */
public final class SearchQueryPolicy {
    public static final class Result {
        public final String originalQuery;
        public final String effectiveQuery;
        public final boolean chineseActuallyApplied;

        Result(String originalQuery, String effectiveQuery, boolean applied) {
            this.originalQuery = originalQuery;
            this.effectiveQuery = effectiveQuery;
            this.chineseActuallyApplied = applied;
        }
    }

    private SearchQueryPolicy() {}

    public static Result resolve(@Nullable String original, int mode, boolean enabled) {
        String raw = original == null ? "" : original;
        ListUrlBuilder builder = new ListUrlBuilder();
        builder.setMode(mode);
        builder.setKeyword(original);
        String effective = builder.getEffectiveKeyword(enabled);
        if (effective == null) effective = "";
        return new Result(raw, effective, enabled && !raw.equals(effective));
    }
}
