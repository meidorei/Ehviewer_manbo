package com.hippo.ehviewer.subscription;

/** Decides when a successfully loaded local source may clear its entry-time unread snapshot. */
public final class LocalUnreadOpenPolicy {
    private LocalUnreadOpenPolicy() {}

    public static boolean shouldComplete(FeedSourceContext.Type type, boolean emptyResult) {
        if (type == FeedSourceContext.Type.QUICK_SEARCH) return true;
        return type == FeedSourceContext.Type.SUBSCRIPTION_TAG && !emptyResult;
    }
}
