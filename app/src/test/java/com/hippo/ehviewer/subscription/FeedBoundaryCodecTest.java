package com.hippo.ehviewer.subscription;

import static org.junit.Assert.*;

import com.hippo.ehviewer.client.data.GalleryInfo;
import org.junit.Test;
import java.util.Set;

public class FeedBoundaryCodecTest {
    @Test public void newFormatPreservesEveryPrecisionIncludingExactSecondZero() {
        for (FeedBoundary.Precision precision : FeedBoundary.Precision.values()) {
            FeedBoundary original = new FeedBoundary(120, Set.of(2L, 1L), precision);
            String encoded = FeedBoundaryCodec.encode(original);
            FeedBoundary restored = FeedBoundaryCodec.decode(original.time, encoded);
            assertEquals(original.time, restored.time);
            assertEquals(original.gids, restored.gids);
            assertEquals(precision, restored.precision);
            // Old clients safely ignore the version header while still reading all GIDs.
            assertEquals(original.gids, SubscriptionRepository.parseGids(encoded));
        }
    }

    @Test public void everyLegacyTimestampIsUncertainUntilItsAnchorsAreVerified() {
        FeedBoundary minute = FeedBoundaryCodec.decode(120, "2,1,bad");
        assertEquals(Set.of(1L, 2L), minute.gids);
        assertEquals(FeedBoundary.Precision.UNKNOWN, minute.precision);
        assertEquals(FeedBoundary.Precision.UNKNOWN, FeedBoundaryCodec.decode(125, "1").precision);
        assertEquals(FeedBoundary.Precision.UNKNOWN, FeedBoundaryCodec.decode(125, null).precision);
        assertEquals(FeedBoundary.Precision.SECOND,
                FeedBoundaryCodec.decode(125, "@fb1:SECOND,1").precision);
        assertTrue(FeedBoundaryCodec.decode(0, null).isEmpty());
        assertEquals(FeedBoundary.Precision.UNKNOWN,
                FeedBoundaryCodec.decode(120, "@fb1:future,1").precision);
    }

    @Test public void mergingBoundaryKnowledgeDoesNotInventPrecision() {
        FeedBoundary exact = new FeedBoundary(120, Set.of(1L));
        FeedBoundary old = FeedBoundaryCodec.decode(120, "2");
        FeedBoundary merged = LocalBaselineResetPolicy.newer(exact, old);
        assertEquals(Set.of(1L, 2L), merged.gids);
        assertEquals(FeedBoundary.Precision.UNKNOWN, merged.precision);
    }

    @Test public void galleryJsonAndCopiesPreservePrecisionWithoutChangingDisplayedDate() {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = 1;
        gallery.posted = "2026-11-11 12:00";
        gallery.postedTimestamp = 120;
        gallery.postedTimestampPrecision = FeedBoundary.Precision.SECOND;
        GalleryInfo restored = GalleryInfo.galleryInfoFromJson(gallery.toJson());
        assertEquals(FeedBoundary.Precision.SECOND, restored.postedTimestampPrecision);
        assertEquals(gallery.posted, restored.posted);
        assertEquals(FeedBoundary.Precision.SECOND, gallery.getDownloadInfo(null).postedTimestampPrecision);
        com.alibaba.fastjson.JSONObject legacy = gallery.toJson();
        legacy.remove("postedTimestampPrecision");
        assertEquals(FeedBoundary.Precision.UNKNOWN,
                GalleryInfo.galleryInfoFromJson(legacy).postedTimestampPrecision);
    }

    @Test public void galleryCsvPreservesPrecisionAndStillReadsLegacyTimestampColumn() {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = 1;
        gallery.simpleTags = new String[0];
        gallery.postedTimestamp = 120;
        gallery.postedTimestampPrecision = FeedBoundary.Precision.SECOND;
        String csv = gallery.toCSV();
        GalleryInfo restored = GalleryInfo.fromCSV(csv);
        assertNotNull(restored);
        assertEquals(120, restored.postedTimestamp);
        assertEquals(FeedBoundary.Precision.SECOND, restored.postedTimestampPrecision);
        String legacy = csv.substring(0, csv.lastIndexOf(',')) + "\n";
        restored = GalleryInfo.fromCSV(legacy);
        assertNotNull(restored);
        assertEquals(120, restored.postedTimestamp);
        assertEquals(FeedBoundary.Precision.UNKNOWN, restored.postedTimestampPrecision);
    }
}
