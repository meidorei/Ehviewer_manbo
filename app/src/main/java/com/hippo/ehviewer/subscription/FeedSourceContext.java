package com.hippo.ehviewer.subscription;

public final class FeedSourceContext {
    public enum Type { HOME, SUBSCRIPTION_AGGREGATE, SUBSCRIPTION_TAG, QUICK_SEARCH, TEMP_SEARCH }

    public final Type type;
    public final String sourceKey;
    public final String querySignature;

    public FeedSourceContext(Type type, String sourceKey, String querySignature) {
        this.type = type;
        this.sourceKey = sourceKey == null ? "" : sourceKey;
        this.querySignature = querySignature == null ? "" : querySignature;
    }

    public boolean persistsCheckpoint() {
        return type == Type.HOME || type == Type.SUBSCRIPTION_AGGREGATE || type == Type.QUICK_SEARCH;
    }

    public boolean showsMarker() {
        return type == Type.HOME
                || type == Type.SUBSCRIPTION_AGGREGATE
                || type == Type.SUBSCRIPTION_TAG
                || type == Type.QUICK_SEARCH;
    }

    public boolean isFollowedArtist() {
        return type == Type.SUBSCRIPTION_TAG && sourceKey.startsWith("artist:");
    }

    public boolean usesFixedChineseQuery() {
        return type == Type.SUBSCRIPTION_TAG || type == Type.QUICK_SEARCH;
    }
}
