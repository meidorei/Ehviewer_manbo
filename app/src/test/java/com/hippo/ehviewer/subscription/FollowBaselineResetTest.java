package com.hippo.ehviewer.subscription;

import static org.junit.Assert.*;
import com.alibaba.fastjson.JSON;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.subscription.BookmarkBaselineResetTest.Fixture;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class FollowBaselineResetTest {
    private final LocalFollowRepository local = LocalFollowRepository.getInstance();
    private final SubscriptionRepository repository = SubscriptionRepository.getInstance();
    private static final String TAG = "artist:example";
    private static final String SIGNATURE = LocalFollowRepository.FIXED_CHINESE_SIGNATURE;

    @Test public void emptyListMakesNoWritesAndAlwaysEndsSnapshotTransaction() {
        Fixture fixture = new Fixture();
        assertEquals(0, local.resetFollowBaselines(fixture.db, 100_000));
        assertTrue(fixture.writes.isEmpty());
        assertEquals(1, fixture.begun);
        assertEquals(1, fixture.ended);
    }

    @Test public void resetIsSharedAcrossAccountsHostsAndReopeningButPreservesOtherBoundaries() {
        Fixture fixture = fixture();
        for (String type : Arrays.asList("LOCAL_FOLLOW_SYNC", "LOCAL_FOLLOW_OPEN", "BOOKMARK_SYNC",
                "BOOKMARK_OPEN", "SUBSCRIPTION_AGGREGATE", "HOME_MANUAL")) {
            fixture.seed(key("guest|e", type), 20, 30, "1", "2");
        }
        Map<List<String>, Object[]> before = new LinkedHashMap<>(fixture.rows);
        assertEquals(1, local.resetFollowBaselines(fixture.db, 100_999));
        for (List<String> saved : before.keySet()) assertArrayEquals(before.get(saved), fixture.rows.get(saved));
        Fixture reopened = new Fixture();
        reopened.rows.putAll(fixture.rows);
        for (String account : Arrays.asList("guest|e", "guest|ex", "user:a|e", "user:b|ex")) {
            assertEquals(101, repository.readCheckpoint(reopened.db,
                    key(account, "LOCAL_FOLLOW_SYNC")).current.time);
        }
        for (String type : Arrays.asList("LOCAL_FOLLOW_OPEN", "BOOKMARK_SYNC", "BOOKMARK_OPEN",
                "SUBSCRIPTION_AGGREGATE", "HOME_MANUAL")) {
            assertEquals(30, repository.readCheckpoint(reopened.db, key("guest|e", type)).current.time);
        }
        assertEquals(10, repository.readStoredCheckpoint(reopened.db,
                key("guest|e", "LOCAL_FOLLOW_SYNC")).updatedAt);
    }

    @Test public void repeatedResetAndClockRollbackKeepNewestFloor() {
        Fixture fixture = fixture();
        local.resetFollowBaselines(fixture.db, 100_000);
        local.resetFollowBaselines(fixture.db, 200_000);
        local.resetFollowBaselines(fixture.db, 150_000);
        assertEquals(1, fixture.rows.size());
        assertEquals(201, repository.readCheckpoint(fixture.db,
                key("guest|e", "LOCAL_FOLLOW_SYNC")).current.time);
    }

    @Test public void successfulScanAndRefinementStoreActualContentOnly() {
        Fixture fixture = fixture();
        CheckpointKey sync = key("guest|e", "LOCAL_FOLLOW_SYNC");
        fixture.seed(sync, 20, 30, "1", "2");
        local.resetFollowBaselines(fixture.db, 100_000);
        repository.advanceCheckpoint(fixture.db, sync, boundary(90, 3));
        FeedCheckpoint stored = repository.readStoredCheckpoint(fixture.db, sync);
        assertEquals(30, stored.previous.time);
        assertEquals(90, stored.current.time);
        assertEquals(101, repository.readCheckpoint(fixture.db, sync).current.time);
        repository.establishCheckpoint(fixture.db, sync, boundary(80, 4));
        assertEquals(80, repository.readStoredCheckpoint(fixture.db, sync).current.time);
        assertEquals(30, repository.readStoredCheckpoint(fixture.db, sync).previous.time);
        assertEquals(101, repository.readCheckpoint(fixture.db, sync).current.time);
        repository.advanceCheckpoint(fixture.db, sync, boundary(110, 5));
        repository.advanceCheckpoint(fixture.db, sync, boundary(110, 6));
        assertEquals(new LinkedHashSet<>(Arrays.asList(6L, 5L)),
                repository.readCheckpoint(fixture.db, sync).current.gids);
    }

    @Test public void tagPageAndGlobalFallbackUseFloorWithoutChangingRetention() {
        Fixture fixture = fixture();
        local.resetFollowBaselines(fixture.db, 100_000);
        FeedCheckpoint checkpoint = repository.readCheckpoint(fixture.db,
                key("guest|e", "LOCAL_FOLLOW_SYNC"));
        List<GalleryInfo> page = Arrays.asList(gallery(102, 4), gallery(101, 3), gallery(100, 2));
        assertEquals(Arrays.asList(4L, 3L), LocalFollowRepository.collectNewGids(checkpoint.current, page));
        FeedBoundary cursor = LocalGlobalCursorStore.oldest(Collections.singletonMap(TAG, checkpoint));
        assertTrue(GlobalScanPolicy.hasPassedCursor(cursor, page));
        assertEquals(101, LocalBaselineResetPolicy.newer(boundary(90, 1), cursor).time);
        assertEquals(21, UnreadRetentionPolicy.maxRows(LocalFollowRepository.SOURCE_FOLLOW));
    }

    @Test public void failureAfterFirstMarkerRollsBackAndEndsTransaction() {
        Fixture fixture = fixture();
        fixture.tags.add("group:example");
        fixture.failAtWrite = 2;
        try {
            local.resetFollowBaselines(fixture.db, 100_000);
            fail("write failure ignored");
        } catch (IllegalStateException expected) {
            assertEquals("injected write failure", expected.getMessage());
        }
        assertTrue(fixture.rows.isEmpty());
        assertEquals(0, fixture.committed);
        assertEquals(1, fixture.ended);
    }

    @Test public void deletionUsedByReplacementImportClearsRemovedTagOnlyAndReaddStartsFresh() {
        Fixture fixture = fixture();
        fixture.tags.add("group:kept");
        local.resetFollowBaselines(fixture.db, 100_000);
        LocalFollowRepository.deleteFollowData(fixture.db, TAG);
        assertEquals(Collections.singletonList("group:kept"), fixture.tags);
        assertTrue(repository.readCheckpoint(fixture.db, key("guest|e", "LOCAL_FOLLOW_SYNC")).current.isEmpty());
        assertEquals(101, repository.readCheckpoint(fixture.db,
                FeedCheckpointKeys.followReset("group:kept", SIGNATURE)).current.time);
        fixture.tags.add(TAG);
        repository.establishCheckpoint(fixture.db, key("guest|e", "LOCAL_FOLLOW_SYNC"),
                BaselineBoundaryPolicy.provisional(200_000));
        assertEquals(201, repository.readCheckpoint(fixture.db,
                key("guest|e", "LOCAL_FOLLOW_SYNC")).current.time);
        assertTrue(repository.readStoredCheckpoint(fixture.db,
                FeedCheckpointKeys.followReset(TAG, SIGNATURE)).current.isEmpty());
    }

    @Test public void resetKeyNormalizesTagsAndKeepsQuerySignatureIsolation() {
        CheckpointKey key = FeedCheckpointKeys.followReset(" Artist:Example  ", SIGNATURE);
        assertEquals(TAG, key.sourceKey);
        assertEquals("shared", key.accountKey);
        Fixture fixture = fixture();
        local.resetFollowBaselines(fixture.db, 100_000);
        assertTrue(repository.readCheckpoint(fixture.db,
                new CheckpointKey("guest|e", "LOCAL_FOLLOW_SYNC", TAG, "other-query")).current.isEmpty());
    }

    @Test public void exportsProductionResetAndRemovalSqlForRealSqliteReplay() throws Exception {
        Fixture fixture = fixture();
        fixture.tags.add("group:kept");
        assertEquals(2, local.resetFollowBaselines(fixture.db, 100_000));
        assertEquals(4, fixture.writes.size());
        Map<String, Object> trace = new LinkedHashMap<>();
        Field field = SubscriptionSchema.class.getDeclaredField("SQL");
        field.setAccessible(true);
        trace.put("schema", field.get(null));
        trace.put("writes", new ArrayList<>(fixture.writes));
        fixture.writes.clear();
        LocalFollowRepository.deleteFollowData(fixture.db, TAG);
        trace.put("removal", fixture.writes);
        Path target = Paths.get("build", "reports", "follow-reset", "sql-trace.json");
        Files.createDirectories(target.getParent());
        Files.write(target, JSON.toJSONString(trace).getBytes(StandardCharsets.UTF_8));
    }

    private static Fixture fixture() { Fixture f = new Fixture(); f.tags.add(TAG); return f; }
    private static CheckpointKey key(String account, String type) {
        return new CheckpointKey(account, type, TAG, SIGNATURE);
    }
    private static FeedBoundary boundary(long time, long gid) {
        return new FeedBoundary(time, Collections.singleton(gid));
    }
    private static GalleryInfo gallery(long time, long gid) {
        GalleryInfo result = new GalleryInfo(); result.postedTimestamp = time; result.gid = gid; return result;
    }
}
