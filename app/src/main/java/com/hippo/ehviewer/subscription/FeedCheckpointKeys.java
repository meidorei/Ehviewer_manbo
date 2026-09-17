package com.hippo.ehviewer.subscription;

/** Namespaces sync and local-seen checkpoints without changing the existing table schema. */
public final class FeedCheckpointKeys {
    private static final String SHARED_ACCOUNT = "shared";
    private static final String HOME_MANUAL_TYPE = "HOME_MANUAL";

    private FeedCheckpointKeys() {}

    /** One manually controlled homepage marker shared by every account and site context. */
    public static CheckpointKey homeManual() {
        return new CheckpointKey(SHARED_ACCOUNT, HOME_MANUAL_TYPE, "home", "");
    }

    public static CheckpointKey bookmarkReset(String sourceKey, String signature) {
        return new CheckpointKey(SHARED_ACCOUNT, "BOOKMARK_RESET", sourceKey, signature);
    }

    public static CheckpointKey followReset(String tag, String signature) {
        return new CheckpointKey(SHARED_ACCOUNT, "LOCAL_FOLLOW_RESET",
                SubscriptionRepository.normalizeTagName(tag), signature);
    }

    public static CheckpointKey seen(String accountKey, FeedSourceContext context) {
        String type;
        if (context.type == FeedSourceContext.Type.SUBSCRIPTION_AGGREGATE) {
            type = "SUBSCRIPTION_AGGREGATE_SEEN";
        } else if (context.type == FeedSourceContext.Type.SUBSCRIPTION_TAG) {
            type = "SUBSCRIPTION_TAG_SEEN";
        } else {
            type = context.type.name();
        }
        String source = context.type == FeedSourceContext.Type.SUBSCRIPTION_TAG
                ? SubscriptionRepository.normalizeTagName(context.sourceKey) : context.sourceKey;
        return new CheckpointKey(accountKey, type, source, context.querySignature);
    }

    public static CheckpointKey subscriptionSync(String accountKey, FeedSourceContext context) {
        return new CheckpointKey(accountKey, FeedSourceContext.Type.SUBSCRIPTION_AGGREGATE.name(),
                "watched", context.querySignature);
    }
}
