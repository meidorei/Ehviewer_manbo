package com.hippo.ehviewer.client;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.exception.CancelledException;
import com.hippo.ehviewer.subscription.FeedBoundary;
import com.hippo.ehviewer.subscription.FeedMarkerPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Optional display metadata, isolated from the strict metadata contract of update scans. */
public final class FeedTimestampSupplement {
    public record Request(FeedBoundary boundary, boolean requireTop) {
        public Request {
            boundary = boundary == null ? FeedBoundary.EMPTY : boundary;
        }
    }

    public record Result(FeedBoundary requestedBoundary, FeedBoundary displayBoundary,
                         FeedBoundary reliableTop) {}

    interface Loader { void load(List<GalleryInfo> missing) throws Throwable; }

    private FeedTimestampSupplement() {}

    static Result fill(Request request, List<GalleryInfo> galleries, Loader loader) throws Throwable {
        if (request == null) return null;
        long newestMinute = 0;
        if (request.requireTop()) {
            for (GalleryInfo gallery : galleries) {
                newestMinute = Math.max(newestMinute, FeedMarkerPolicy.minute(gallery.postedTimestamp));
            }
        }
        List<GalleryInfo> originals = new ArrayList<>();
        List<GalleryInfo> copies = new ArrayList<>();
        for (GalleryInfo gallery : galleries) {
            if (gallery.postedTimestampPrecision == FeedBoundary.Precision.SECOND) continue;
            long minute = FeedMarkerPolicy.minute(gallery.postedTimestamp);
            boolean top = request.requireTop() && minute == newestMinute;
            boolean anchor = !request.boundary().isEmpty()
                    && (minute == FeedMarkerPolicy.minute(request.boundary().time)
                    || request.boundary().gids.contains(gallery.gid));
            if (!top && !anchor) continue;
            if (gallery.gid <= 0 || gallery.token == null || gallery.token.isEmpty()) continue;
            GalleryInfo copy = new GalleryInfo();
            copy.gid = gallery.gid;
            copy.token = gallery.token;
            originals.add(gallery);
            copies.add(copy);
        }
        if (!copies.isEmpty()) {
            boolean loaded = false;
            try {
                loader.load(copies);
                loaded = true;
            } catch (CancelledException | InterruptedException | CancellationException e) {
                throw e;
            } catch (Exception ignored) {
                // Never publish an API parser's partial mutations after a failed batch.
            }
            if (loaded) {
                for (int i = 0; i < copies.size(); i++) {
                    GalleryInfo copy = copies.get(i);
                    GalleryInfo original = originals.get(i);
                    if (copy.postedTimestamp <= 0
                            || copy.postedTimestampPrecision != FeedBoundary.Precision.SECOND) continue;
                    // A replaced/reposted gallery is not a safe refinement of this HTML snapshot.
                    if (original.postedTimestamp > 0 && FeedMarkerPolicy.minute(copy.postedTimestamp)
                            != FeedMarkerPolicy.minute(original.postedTimestamp)) continue;
                    original.postedTimestamp = copy.postedTimestamp;
                    original.postedTimestampPrecision = FeedBoundary.Precision.SECOND;
                }
            }
        }
        return new Result(request.boundary(),
                FeedMarkerPolicy.resolveLegacyBoundary(request.boundary(), galleries),
                request.requireTop() ? FeedMarkerPolicy.reliableTop(galleries) : FeedBoundary.EMPTY);
    }
}
