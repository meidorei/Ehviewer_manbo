package com.hippo.ehviewer.subscription;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/** Adds explicit precision to the existing GID column; old comma-separated values remain valid. */
public final class FeedBoundaryCodec {
    private static final String PREFIX = "@fb1:";

    private FeedBoundaryCodec() {}

    public static String encode(FeedBoundary boundary) {
        StringBuilder result = new StringBuilder(PREFIX).append(boundary.precision.name());
        for (Long gid : new TreeSet<>(boundary.gids)) result.append(',').append(gid);
        return result.toString();
    }

    public static FeedBoundary decode(long time, String value) {
        if (time <= 0) return FeedBoundary.EMPTY;
        // Legacy storage did not record provenance. Only an explicit precision marker is trusted;
        // display code can refine old anchors after their actual API timestamps are verified.
        FeedBoundary.Precision precision = FeedBoundary.Precision.UNKNOWN;
        Set<Long> gids = new LinkedHashSet<>();
        if (value != null) {
            String[] parts = value.split(",");
            for (String part : parts) {
                if (part.startsWith(PREFIX)) {
                    try {
                        precision = FeedBoundary.Precision.valueOf(part.substring(PREFIX.length()));
                    } catch (IllegalArgumentException ignored) {
                        precision = FeedBoundary.Precision.UNKNOWN;
                    }
                } else {
                    try { gids.add(Long.parseLong(part)); } catch (NumberFormatException ignored) {}
                }
            }
        }
        return new FeedBoundary(time, gids, precision);
    }
}
