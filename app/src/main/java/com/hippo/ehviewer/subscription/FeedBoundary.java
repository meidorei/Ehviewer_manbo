package com.hippo.ehviewer.subscription;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class FeedBoundary {
    public enum Precision { UNKNOWN, MINUTE, SECOND }

    public static final FeedBoundary EMPTY = new FeedBoundary(0, Collections.emptySet());

    public final long time;
    public final Set<Long> gids;
    public final Precision precision;

    public FeedBoundary(long time, Set<Long> gids) {
        this(time, gids, Precision.SECOND);
    }

    public FeedBoundary(long time, Set<Long> gids, Precision precision) {
        this.time = Math.max(0, time);
        this.gids = Collections.unmodifiableSet(new LinkedHashSet<>(gids));
        this.precision = precision == null ? Precision.UNKNOWN : precision;
    }

    public boolean isEmpty() { return time == 0; }

    /** Converts the persisted Unix-seconds boundary to an Android UI timestamp. */
    public long timeMillis() { return time == 0 ? 0 : time * 1000L; }

    public boolean isNew(long postedTimestamp, long gid) {
        return postedTimestamp > 0 && !isEmpty()
                && (postedTimestamp > time || postedTimestamp == time && !gids.contains(gid));
    }

    /** Older scans could store HTML minute precision for galleries already seen at the top. */
    boolean isNewForUpdate(long postedTimestamp, long gid) {
        if (time % 60 == 0 && postedTimestamp / 60 == time / 60 && gids.contains(gid)) return false;
        return isNew(postedTimestamp, gid);
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
