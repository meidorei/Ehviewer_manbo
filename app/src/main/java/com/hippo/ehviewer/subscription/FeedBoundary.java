package com.hippo.ehviewer.subscription;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class FeedBoundary {
    public static final FeedBoundary EMPTY = new FeedBoundary(0, Collections.emptySet());

    public final long time;
    public final Set<Long> gids;

    public FeedBoundary(long time, Set<Long> gids) {
        this.time = Math.max(0, time);
        this.gids = Collections.unmodifiableSet(new LinkedHashSet<>(gids));
    }

    public boolean isEmpty() { return time == 0; }

    /** Converts the persisted Unix-seconds boundary to an Android UI timestamp. */
    public long timeMillis() { return time == 0 ? 0 : time * 1000L; }

    public boolean isNew(long postedTimestamp, long gid) {
        return postedTimestamp > 0 && !isEmpty()
                && (postedTimestamp > time || postedTimestamp == time && !gids.contains(gid));
    }

    public boolean isFirstOld(long postedTimestamp, long gid) {
        return postedTimestamp > 0 && !isEmpty()
                && (postedTimestamp < time || postedTimestamp == time && gids.contains(gid));
    }

    /** A historical page start alone is not evidence that the manual marker is here. */
    public boolean isHomeMarkerBefore(long postedTimestamp, long gid,
                                      long previousTimestamp, long previousGid) {
        if (!isFirstOld(postedTimestamp, gid)) return false;
        if (previousTimestamp > 0) return isNew(previousTimestamp, previousGid);
        return postedTimestamp == time && gids.contains(gid);
    }
}
