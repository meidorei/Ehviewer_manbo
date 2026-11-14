package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Display-only comparisons. Update scans continue to use their strict second-based boundary. */
public final class FeedMarkerPolicy {
    public enum Relation { NEW, OLD, UNCERTAIN }

    private FeedMarkerPolicy() {}

    public static Relation classify(FeedBoundary boundary, GalleryInfo gallery) {
        if (boundary == null || boundary.isEmpty() || gallery == null
                || gallery.postedTimestamp <= 0) return Relation.UNCERTAIN;
        long time = gallery.postedTimestamp;
        boolean exactItem = gallery.postedTimestampPrecision == FeedBoundary.Precision.SECOND;
        boolean exactBoundary = boundary.precision == FeedBoundary.Precision.SECOND;
        if (exactItem && exactBoundary) {
            return boundary.isNew(time, gallery.gid) ? Relation.NEW : Relation.OLD;
        }
        long itemStart = exactItem ? time : minute(time);
        long itemEnd = exactItem ? time : minute(time) + 59;
        long boundaryStart = exactBoundary ? boundary.time : minute(boundary.time);
        long boundaryEnd = exactBoundary ? boundary.time : minute(boundary.time) + 59;
        if (itemEnd < boundaryStart) return Relation.OLD;
        if (itemStart > boundaryEnd) return Relation.NEW;
        // A known anchor remains seen even when its former HTML minute is later refined.
        if (boundary.gids.contains(gallery.gid)
                && minute(time) == minute(boundary.time)) return Relation.OLD;
        return Relation.UNCERTAIN;
    }

    public static boolean isMarkerBefore(FeedBoundary boundary, GalleryInfo item,
                                         GalleryInfo previous, boolean home) {
        if (classify(boundary, item) != Relation.OLD) return false;
        if (previous != null) return classify(boundary, previous) == Relation.NEW;
        return !home || boundary.gids.contains(item.gid);
    }

    /** A minute-only candidate could be newer than an already precise item in the same minute. */
    public static FeedBoundary reliableTop(List<GalleryInfo> galleries) {
        if (galleries == null || galleries.isEmpty()) return FeedBoundary.EMPTY;
        long latestMinute = 0;
        for (GalleryInfo gallery : galleries) {
            if (gallery == null || gallery.postedTimestamp <= 0) return FeedBoundary.EMPTY;
            latestMinute = Math.max(latestMinute, minute(gallery.postedTimestamp));
        }
        long newest = 0;
        Set<Long> gids = new LinkedHashSet<>();
        for (GalleryInfo gallery : galleries) {
            if (minute(gallery.postedTimestamp) != latestMinute) continue;
            if (gallery.postedTimestampPrecision != FeedBoundary.Precision.SECOND) {
                return FeedBoundary.EMPTY;
            }
            if (gallery.postedTimestamp > newest) {
                newest = gallery.postedTimestamp;
                gids.clear();
            }
            if (gallery.postedTimestamp == newest) gids.add(gallery.gid);
        }
        return newest > 0 ? new FeedBoundary(newest, gids) : FeedBoundary.EMPTY;
    }

    /** Refines only a complete legacy anchor set; never guesses from an arbitrary minute peer. */
    public static FeedBoundary resolveLegacyBoundary(FeedBoundary boundary,
                                                       List<GalleryInfo> galleries) {
        if (boundary == null || boundary.isEmpty()) return FeedBoundary.EMPTY;
        if (boundary.precision == FeedBoundary.Precision.SECOND || boundary.gids.isEmpty()) {
            return boundary;
        }
        Set<Long> missing = new LinkedHashSet<>(boundary.gids);
        Set<Long> newestGids = new LinkedHashSet<>();
        long newest = 0;
        for (GalleryInfo gallery : galleries) {
            if (!missing.contains(gallery.gid)) continue;
            if (gallery.postedTimestampPrecision != FeedBoundary.Precision.SECOND
                    || minute(gallery.postedTimestamp) != minute(boundary.time)) return boundary;
            missing.remove(gallery.gid);
            if (gallery.postedTimestamp > newest) {
                newest = gallery.postedTimestamp;
                newestGids.clear();
            }
            if (gallery.postedTimestamp == newest) newestGids.add(gallery.gid);
        }
        return missing.isEmpty() && newest > 0 ? new FeedBoundary(newest, newestGids) : boundary;
    }

    public static long minute(long time) { return time / 60 * 60; }
}
