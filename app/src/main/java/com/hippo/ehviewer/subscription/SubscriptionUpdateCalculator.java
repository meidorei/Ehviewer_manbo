package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure aggregation logic; networking and persistence deliberately live elsewhere. */
public final class SubscriptionUpdateCalculator {
    public static final class Outcome {
        public final boolean complete;
        public final boolean baselineOnly;
        public final FeedBoundary newBoundary;
        public final Map<String, TagUpdateState> states;
        public final String error;

        Outcome(boolean complete, boolean baselineOnly, FeedBoundary boundary,
                Map<String, TagUpdateState> states, String error) {
            this.complete = complete;
            this.baselineOnly = baselineOnly;
            this.newBoundary = boundary;
            this.states = states;
            this.error = error;
        }
    }

    private SubscriptionUpdateCalculator() {}

    public static Outcome calculate(FeedBoundary oldBoundary, List<GalleryInfo> galleries,
                                    Set<String> subscribedTags) {
        return calculate(oldBoundary, galleries, subscribedTags, false);
    }

    public static Outcome calculate(FeedBoundary oldBoundary, List<GalleryInfo> galleries,
                                    Set<String> subscribedTags, boolean coverageProven) {
        long checkedAt = System.currentTimeMillis();
        Map<String, Integer> counts = new HashMap<>();
        for (String tag : subscribedTags) counts.put(SubscriptionRepository.normalizeTagName(tag), 0);
        if (galleries.isEmpty()) {
            return new Outcome(false, oldBoundary.isEmpty(), FeedBoundary.EMPTY,
                    Collections.emptyMap(), "empty response");
        }

        long newest = 0;
        long previousTimestamp = Long.MAX_VALUE;
        Set<Long> newestGids = new HashSet<>();
        Set<Long> seen = new HashSet<>();
        boolean foundBoundary = oldBoundary.isEmpty() || coverageProven;
        for (GalleryInfo gallery : galleries) {
            if (!seen.add(gallery.gid)) continue;
            if (gallery.postedTimestamp <= 0 || gallery.simpleTags == null) {
                return new Outcome(false, false, FeedBoundary.EMPTY,
                        Collections.emptyMap(), "incomplete gdata");
            }
            if (gallery.postedTimestamp > previousTimestamp) {
                return new Outcome(false, false, FeedBoundary.EMPTY,
                        Collections.emptyMap(), "feed is not newest-first");
            }
            previousTimestamp = gallery.postedTimestamp;
            if (gallery.postedTimestamp > newest) {
                newest = gallery.postedTimestamp;
                newestGids.clear();
                newestGids.add(gallery.gid);
            } else if (gallery.postedTimestamp == newest) {
                newestGids.add(gallery.gid);
            }
            if (!oldBoundary.isEmpty() && oldBoundary.isFirstOld(gallery.postedTimestamp, gallery.gid)) {
                foundBoundary = true;
                if (gallery.postedTimestamp < oldBoundary.time) break;
            }
            if (oldBoundary.isNewForUpdate(gallery.postedTimestamp, gallery.gid)) {
                for (String raw : gallery.simpleTags) {
                    String tag = SubscriptionRepository.normalizeTagName(raw);
                    if (counts.containsKey(tag)) counts.put(tag, counts.get(tag) + 1);
                }
            }
        }

        FeedBoundary next = new FeedBoundary(newest, newestGids);
        Map<String, TagUpdateState> states = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            states.put(entry.getKey(), new TagUpdateState(entry.getKey(), entry.getValue(),
                    TagUpdateState.State.EXACT, checkedAt));
        }
        return new Outcome(foundBoundary, oldBoundary.isEmpty(), next,
                Collections.unmodifiableMap(states), foundBoundary ? "" : "boundary not reached");
    }

    /** Adds exact scan deltas to unread counts. A baseline deliberately resets all tags to zero. */
    public static Map<String, TagUpdateState> mergeUnread(
            Map<String, TagUpdateState> existing,
            Map<String, TagUpdateState> deltas,
            boolean baselineOnly) {
        Map<String, TagUpdateState> merged = new LinkedHashMap<>();
        for (Map.Entry<String, TagUpdateState> entry : deltas.entrySet()) {
            TagUpdateState delta = entry.getValue();
            TagUpdateState old = existing.get(entry.getKey());
            int count = baselineOnly ? 0 : old == null ? delta.count : old.count + delta.count;
            boolean capped = !baselineOnly && ((old != null && old.state == TagUpdateState.State.LOWER_BOUND)
                    || delta.state == TagUpdateState.State.LOWER_BOUND
                    || count > TagUpdateState.DISPLAY_CAP);
            merged.put(entry.getKey(), new TagUpdateState(entry.getKey(), count,
                    capped ? TagUpdateState.State.LOWER_BOUND : TagUpdateState.State.EXACT,
                    delta.checkedAt));
        }
        return Collections.unmodifiableMap(merged);
    }
}
