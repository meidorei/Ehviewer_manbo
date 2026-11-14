package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.data.userTag.UserTag;
import com.hippo.ehviewer.client.data.userTag.UserTagList;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local immutable O(1) tag lookup used by detail rendering. */
public final class SubscriptionSnapshot {
    private static volatile Set<String> subscribed = Collections.emptySet();
    private static final AtomicLong VERSION = new AtomicLong();

    private SubscriptionSnapshot() {}

    public static long refreshFromDatabase() {
        return replace(LocalFollowRepository.getInstance().getSet());
    }

    public static long replace(Set<String> tags) {
        Set<String> next = new HashSet<>();
        if (tags != null) {
            for (String tag : tags) {
                String normalized = SubscriptionRepository.normalizeTagName(tag);
                if (!normalized.isEmpty()) next.add(normalized);
            }
        }
        subscribed = Collections.unmodifiableSet(next);
        return VERSION.incrementAndGet();
    }

    /**
     * Server Watched snapshots are deliberately ignored for highlighting. Kept as a source
     * compatible bridge while the server subscription screens continue to refresh themselves.
     */
    public static long replace(UserTagList list) {
        return refreshFromDatabase();
    }

    public static boolean contains(String rawTag) {
        return subscribed.contains(SubscriptionRepository.normalizeTagName(rawTag));
    }

    public static long version() { return VERSION.get(); }
}
