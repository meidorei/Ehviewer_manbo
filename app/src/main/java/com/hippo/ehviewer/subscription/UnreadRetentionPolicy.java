package com.hippo.ehviewer.subscription;

/** Storage retention rules for unread gallery relationships. */
final class UnreadRetentionPolicy {
    static final int UNLIMITED = -1;
    static final int LOCAL_FOLLOW_LIMIT = 21;

    private UnreadRetentionPolicy() {}

    static int maxRows(String sourceType) {
        return LocalFollowRepository.SOURCE_BOOKMARK.equals(sourceType)
                ? UNLIMITED : LOCAL_FOLLOW_LIMIT;
    }
}
