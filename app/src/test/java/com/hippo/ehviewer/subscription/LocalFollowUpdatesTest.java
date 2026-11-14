package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.GalleryInfo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LocalFollowUpdatesTest {
    @Test public void gallerySnapshotPreservesDisplayAndNavigationData() {
        GalleryInfo gallery = gallery(42, 1700000000L);
        gallery.token = "ab123";
        gallery.title = "中文, \"标题\"\n下一行";
        gallery.titleJpn = "原題";
        gallery.thumb = "https://example.invalid/cover.jpg";
        gallery.category = 8;
        gallery.posted = "2026-11-10";
        gallery.uploader = "uploader";
        gallery.rating = 4.5f;
        gallery.rated = true;
        gallery.simpleLanguage = "ZH";
        gallery.pages = 72;
        gallery.thumbWidth = 120;
        gallery.thumbHeight = 180;

        GalleryInfo restored = LocalFollowUpdateCodec.decodeGallery(
                LocalFollowUpdateCodec.encodeGallery(gallery));
        assertEquals(gallery.gid, restored.gid);
        assertEquals(gallery.token, restored.token);
        assertEquals(gallery.title, restored.title);
        assertEquals(gallery.titleJpn, restored.titleJpn);
        assertEquals(gallery.thumb, restored.thumb);
        assertEquals(gallery.category, restored.category);
        assertEquals(gallery.posted, restored.posted);
        assertEquals(gallery.postedTimestamp, restored.postedTimestamp);
        assertEquals(gallery.uploader, restored.uploader);
        assertEquals(gallery.rating, restored.rating, 0);
        assertEquals(gallery.rated, restored.rated);
        assertEquals(gallery.simpleLanguage, restored.simpleLanguage);
        assertEquals(gallery.pages, restored.pages);
        assertEquals(gallery.thumbWidth, restored.thumbWidth);
        assertEquals(gallery.thumbHeight, restored.thumbHeight);
        assertEquals(-2, restored.favoriteSlot);
    }

    @Test public void optionalSnapshotFieldsRemainOptional() {
        GalleryInfo restored = LocalFollowUpdateCodec.decodeGallery(
                LocalFollowUpdateCodec.encodeGallery(gallery(42, 123)));
        assertNull(restored.titleJpn);
        assertNull(restored.simpleLanguage);
        assertFalse(restored.rated);
    }

    @Test public void matchingTagsMergeWithoutDuplicatesInStableOrder() {
        String snapshot = LocalFollowUpdateCodec.mergeTag("[]", "group:中文组");
        snapshot = LocalFollowUpdateCodec.mergeTag(snapshot, " Artist:Example ");
        snapshot = LocalFollowUpdateCodec.mergeTag(snapshot, "artist:example");
        assertEquals(Arrays.asList("artist:example", "group:中文组"),
                LocalFollowUpdateCodec.decodeTags(snapshot));
    }

    @Test public void snapshotsUseExactScanDeltaIncludingNewGidInBoundarySecond() {
        GalleryInfo newer = gallery(80, 101);
        GalleryInfo sameSecondNew = gallery(61, 100);
        List<GalleryInfo> input = Arrays.asList(newer, gallery(60, 100),
                sameSecondNew, gallery(61, 100), gallery(10, 99));
        List<Long> delta = LocalFollowRepository.collectNewGids(
                new FeedBoundary(100, Collections.singleton(60L)), input);
        List<GalleryInfo> snapshots = LocalFollowUpdatesRepository.selectNewGalleries(delta, input);
        assertEquals(2, snapshots.size());
        assertSame(newer, snapshots.get(0));
        assertSame(sameSecondNew, snapshots.get(1));
    }

    @Test public void baselineNeverProducesHistory() {
        List<GalleryInfo> input = Collections.singletonList(gallery(42, 100));
        assertTrue(LocalFollowUpdatesRepository.selectNewGalleries(
                LocalFollowRepository.collectNewGids(FeedBoundary.EMPTY, input), input).isEmpty());
    }

    @Test public void historyReceivesAllNewGalleriesBeforeUnreadRetention() {
        List<GalleryInfo> input = new ArrayList<>();
        for (long gid = 80; gid >= 30; gid--) input.add(gallery(gid, 100));
        List<Long> delta = LocalFollowRepository.collectNewGids(
                new FeedBoundary(99, Collections.singleton(1L)), input);
        assertEquals(51, LocalFollowUpdatesRepository.selectNewGalleries(delta, input).size());
        assertEquals(21, UnreadRetentionPolicy.maxRows(LocalFollowRepository.SOURCE_FOLLOW));
        assertEquals(UnreadRetentionPolicy.UNLIMITED,
                UnreadRetentionPolicy.maxRows(LocalFollowRepository.SOURCE_BOOKMARK));
    }

    private static GalleryInfo gallery(long gid, long posted) {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = gid;
        gallery.token = "token" + gid;
        gallery.postedTimestamp = posted;
        return gallery;
    }
}
