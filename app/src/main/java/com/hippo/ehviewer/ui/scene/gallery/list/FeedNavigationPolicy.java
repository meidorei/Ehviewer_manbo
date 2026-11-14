package com.hippo.ehviewer.ui.scene.gallery.list;

import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.subscription.FeedBoundary;
import com.hippo.ehviewer.subscription.FeedSourceContext;
import com.hippo.ehviewer.subscription.LocalUnreadOpenPolicy;
import com.hippo.ehviewer.subscription.SubscriptionRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Keeps a query transition and its unread/marker identity together. */
final class FeedNavigationPolicy {
    private FeedNavigationPolicy() {}

    static Entry tag(String tag, boolean followed, boolean combine, List<String> filters) {
        if (followed) {
            String normalized = SubscriptionRepository.normalizeTagName(tag);
            return new Entry(FeedSourceContext.Type.SUBSCRIPTION_TAG, normalized,
                    normalized, ListUrlBuilder.MODE_TAG, false, Collections.emptyList());
        }
        List<String> selected = new ArrayList<>();
        if (combine) {
            selected.addAll(filters);
            // Keep the existing multi-tag query syntax; it is a temporary search only.
            String[] parts = tag.split(":");
            String value = parts.length == 2 ? parts[1] : parts[0];
            if (!selected.contains(value)) selected.add(value);
        }
        return new Entry(FeedSourceContext.Type.TEMP_SEARCH, "",
                combine ? String.join("  ", selected) : tag,
                combine ? ListUrlBuilder.MODE_FILTER : ListUrlBuilder.MODE_TAG,
                combine, selected);
    }

    static boolean canResetHome(FeedSourceContext source, ListUrlBuilder query) {
        return source != null && source.type == FeedSourceContext.Type.HOME && query != null
                && query.getMode() == ListUrlBuilder.MODE_NORMAL
                && (query.getKeyword() == null || query.getKeyword().trim().isEmpty())
                && query.getCategory() == EhUtils.NONE && query.getAdvanceSearch() == -1
                && query.getMinRating() == -1 && query.getPageFrom() == -1 && query.getPageTo() == -1;
    }

    static final class Entry {
        final FeedSourceContext.Type type;
        final String sourceKey;
        final String keyword;
        final int mode;
        final boolean combine;
        final List<String> filters;

        Entry(FeedSourceContext.Type type, String sourceKey, String keyword, int mode,
              boolean combine, List<String> filters) {
            this.type = type;
            this.sourceKey = sourceKey;
            this.keyword = keyword;
            this.mode = mode;
            this.combine = combine;
            this.filters = Collections.unmodifiableList(new ArrayList<>(filters));
        }
    }

    /** Entry-owned state: pending work blocks duplicate requests but is never saved as committed. */
    static final class OpenCompletion {
        private FeedSourceContext source;
        private boolean pending;
        private boolean committed;

        void reset(FeedSourceContext source, boolean restoredCommitted) {
            this.source = source;
            pending = false;
            committed = restoredCommitted;
        }

        boolean isBusy() { return pending || committed; }

        boolean savedCommitted() { return committed; }

        boolean begin(FeedSourceContext source) {
            if (source == null || source != this.source || isBusy()) return false;
            pending = true;
            return true;
        }

        void finish(FeedSourceContext source, boolean success) {
            if (source != this.source || !pending) return;
            pending = false;
            committed = success;
        }
    }

    /** Captured when a network request is dispatched, never reconstructed from a later page. */
    static final class Request {
        final Object owner;
        final FeedSourceContext source;
        final FeedBoundary markerBoundary;
        final long unreadRowId;
        final boolean firstPage;

        Request(Object owner, FeedSourceContext source, FeedBoundary markerBoundary, long unreadRowId,
                boolean firstPage) {
            this.owner = owner;
            this.source = source;
            this.markerBoundary = markerBoundary;
            this.unreadRowId = unreadRowId;
            this.firstPage = firstPage;
        }

        boolean isCurrent(Object currentOwner, FeedSourceContext current) {
            return owner == currentOwner && belongsTo(current);
        }

        // Once a response is accepted, its database commit belongs to the entry, not its View.
        boolean belongsTo(FeedSourceContext current) { return source == current; }

        boolean canComplete(Object currentOwner, FeedSourceContext current, boolean completionBusy,
                            boolean emptyResult, boolean reliableTop) {
            return source != null && isCurrent(currentOwner, current) && firstPage && !completionBusy
                    && LocalUnreadOpenPolicy.shouldComplete(source.type, emptyResult)
                    && (emptyResult || reliableTop);
        }
    }
}
