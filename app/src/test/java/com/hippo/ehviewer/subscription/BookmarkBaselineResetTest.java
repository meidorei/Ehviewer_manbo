package com.hippo.ehviewer.subscription;

import static org.junit.Assert.*;

import android.database.Cursor;
import com.alibaba.fastjson.JSON;
import com.hippo.ehviewer.dao.QuickSearch;
import org.greenrobot.greendao.database.Database;
import org.junit.Test;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Exercises the production repository against a checkpoint/transaction test double.
 * The emitted production SQL is also replayed on real SQLite by the companion tool. */
public class BookmarkBaselineResetTest {
    private final LocalFollowRepository local = LocalFollowRepository.getInstance();
    private final SubscriptionRepository repository = SubscriptionRepository.getInstance();

    @Test public void emptyBookmarkListDoesNotEvenBeginTransaction() {
        assertEquals(0, local.resetBookmarkBaselines(null, Collections.emptyList(), 100_000));
    }

    @Test public void resetPreservesStoredSyncOpenAndPreviousBoundaries() {
        Fixture fixture = new Fixture();
        QuickSearch bookmark = bookmark(7);
        String signature = BookmarkUpdatePolicy.validate(bookmark).signature;
        CheckpointKey sync = key("guest|e", "BOOKMARK_SYNC", "7", signature);
        CheckpointKey open = key("shared", "BOOKMARK_OPEN", "7", signature);
        fixture.seed(sync, 20, 30, "1", "2");
        fixture.seed(open, 10, 20, "3", "4");
        Map<List<String>, Object[]> before = new LinkedHashMap<>(fixture.rows);
        assertEquals(1, local.resetBookmarkBaselines(fixture.db, Arrays.asList(bookmark), 100_999));
        for (List<String> saved : before.keySet()) assertArrayEquals(before.get(saved), fixture.rows.get(saved));
        assertEquals(101, repository.readCheckpoint(fixture.db, sync).current.time);
        assertEquals(20, repository.readCheckpoint(fixture.db, sync).previous.time);
        assertEquals(20, repository.readCheckpoint(fixture.db, open).current.time);
        assertEquals(1, fixture.begun);
        assertEquals(1, fixture.committed);
    }

    @Test public void sharedResetAppliesToNewAccountsAndHostsButNotFollowsOrOtherQueries() {
        Fixture fixture = new Fixture();
        QuickSearch bookmark = bookmark(7);
        String signature = BookmarkUpdatePolicy.validate(bookmark).signature;
        local.resetBookmarkBaselines(fixture.db, Arrays.asList(bookmark), 100_000);
        for (String account : Arrays.asList("guest|e", "guest|ex", "user:a|e", "user:b|ex")) {
            FeedCheckpoint read = repository.readCheckpoint(fixture.db,
                    key(account, "BOOKMARK_SYNC", "7", signature));
            assertEquals(101, read.current.time);
            assertEquals(0, read.updatedAt);
            assertTrue(read.previous.isEmpty());
        }
        assertTrue(repository.readCheckpoint(fixture.db,
                key("guest|e", "LOCAL_FOLLOW_SYNC", "7", signature)).current.isEmpty());
        assertTrue(repository.readCheckpoint(fixture.db,
                key("guest|e", "BOOKMARK_SYNC", "7", "changed-query")).current.isEmpty());
        Fixture reopened = new Fixture();
        reopened.rows.putAll(fixture.rows);
        assertEquals(101, repository.readCheckpoint(reopened.db,
                key("guest|ex", "BOOKMARK_SYNC", "7", signature)).current.time);
    }

    @Test public void repeatedResetAndClockRollbackNeverMoveFloorBackward() {
        Fixture fixture = new Fixture();
        QuickSearch bookmark = bookmark(7);
        List<QuickSearch> bookmarks = Arrays.asList(bookmark);
        local.resetBookmarkBaselines(fixture.db, bookmarks, 100_000);
        local.resetBookmarkBaselines(fixture.db, bookmarks, 200_000);
        local.resetBookmarkBaselines(fixture.db, bookmarks, 150_000);
        assertEquals(1, fixture.rows.size());
        CheckpointKey reset = FeedCheckpointKeys.bookmarkReset("7",
                BookmarkUpdatePolicy.validate(bookmark).signature);
        assertEquals(201, repository.readCheckpoint(fixture.db, reset).current.time);
    }

    @Test public void olderScanTopCannotOverwriteResetOrKnownSameSecondGids() {
        Fixture fixture = new Fixture();
        QuickSearch bookmark = bookmark(7);
        String signature = BookmarkUpdatePolicy.validate(bookmark).signature;
        CheckpointKey sync = key("guest|e", "BOOKMARK_SYNC", "7", signature);
        local.resetBookmarkBaselines(fixture.db, Arrays.asList(bookmark), 100_000);
        repository.advanceCheckpoint(fixture.db, sync, new FeedBoundary(90, Collections.singleton(1L)));
        assertEquals(101, repository.readCheckpoint(fixture.db, sync).current.time);
        repository.advanceCheckpoint(fixture.db, sync, new FeedBoundary(101, Collections.singleton(2L)));
        repository.establishCheckpoint(fixture.db, sync, new FeedBoundary(101, Collections.singleton(3L)));
        assertEquals(new LinkedHashSet<>(Arrays.asList(3L, 2L)),
                repository.readCheckpoint(fixture.db, sync).current.gids);
    }

    @Test public void automaticBaselineRefinesProvisionalTimeForBookmarksAndFollows() {
        for (String type : Arrays.asList("BOOKMARK_SYNC", "LOCAL_FOLLOW_SYNC")) {
            Fixture fixture = new Fixture();
            CheckpointKey sync = key("guest|e", type, "7", "query");
            repository.establishCheckpoint(fixture.db, sync,
                    BaselineBoundaryPolicy.provisional(100_000));
            repository.establishCheckpoint(fixture.db, sync,
                    new FeedBoundary(90, new LinkedHashSet<>(Arrays.asList(1L, 2L))));
            FeedCheckpoint refined = repository.readCheckpoint(fixture.db, sync);
            assertEquals(90, refined.current.time);
            assertEquals(new LinkedHashSet<>(Arrays.asList(1L, 2L)), refined.current.gids);
            assertTrue(refined.previous.isEmpty());
        }
    }

    @Test public void successfulScanStoresRealTopAndPreviousWhileResetStillLimitsScanning() {
        Fixture fixture = new Fixture();
        QuickSearch bookmark = bookmark(7);
        String signature = BookmarkUpdatePolicy.validate(bookmark).signature;
        CheckpointKey sync = key("guest|e", "BOOKMARK_SYNC", "7", signature);
        fixture.seed(sync, 20, 30, "1", "2");
        local.resetBookmarkBaselines(fixture.db, Arrays.asList(bookmark), 100_000);
        repository.advanceCheckpoint(fixture.db, sync,
                new FeedBoundary(90, Collections.singleton(3L)));
        FeedCheckpoint stored = repository.readStoredCheckpoint(fixture.db, sync);
        assertEquals(30, stored.previous.time);
        assertEquals(Collections.singleton(2L), stored.previous.gids);
        assertEquals(90, stored.current.time);
        assertEquals(Collections.singleton(3L), stored.current.gids);
        assertEquals(101, repository.readCheckpoint(fixture.db, sync).current.time);
        assertEquals(101, repository.readCheckpoint(fixture.db,
                key("guest|ex", "BOOKMARK_SYNC", "7", signature)).current.time);

        // Linked unread clearing consumes the stored sync top, not the scan floor.
        FeedBoundary marker = BookmarkUnreadClearPolicy.boundaryToAdvance(
                "8", "7", 1, 0, TagUpdateState.State.EXACT,
                new FeedBoundary(20, Collections.singleton(1L)), stored.current);
        assertEquals(90, marker.time);
        assertEquals(Collections.singleton(3L), marker.gids);
        repository.advanceCheckpoint(fixture.db, sync,
                new FeedBoundary(110, Collections.singleton(4L)));
        assertEquals(90, repository.readStoredCheckpoint(fixture.db, sync).previous.time);
        assertEquals(110, repository.readCheckpoint(fixture.db, sync).current.time);
    }

    @Test public void refinementKeepsResetSeparateAndPreservesStoredPrevious() {
        Fixture fixture = new Fixture();
        QuickSearch bookmark = bookmark(7);
        String signature = BookmarkUpdatePolicy.validate(bookmark).signature;
        CheckpointKey sync = key("guest|e", "BOOKMARK_SYNC", "7", signature);
        fixture.seed(sync, 20, 101, "1", "");
        local.resetBookmarkBaselines(fixture.db, Arrays.asList(bookmark), 200_000);
        repository.establishCheckpoint(fixture.db, sync,
                new FeedBoundary(90, Collections.singleton(3L)));
        assertEquals(90, repository.readStoredCheckpoint(fixture.db, sync).current.time);
        assertEquals(20, repository.readStoredCheckpoint(fixture.db, sync).previous.time);
        assertEquals(201, repository.readCheckpoint(fixture.db, sync).current.time);
    }

    @Test public void failureAfterFirstWriteEndsTransactionWithoutCommitting() {
        Fixture fixture = new Fixture();
        fixture.failAtWrite = 3;
        try {
            local.resetBookmarkBaselines(fixture.db, Arrays.asList(bookmark(7), bookmark(8)), 100_000);
            fail("injected write failure was ignored");
        } catch (IllegalStateException expected) {
            assertEquals("injected write failure", expected.getMessage());
        }
        assertTrue(fixture.rows.isEmpty());
        assertEquals(1, fixture.begun);
        assertEquals(1, fixture.ended);
        assertEquals(0, fixture.committed);
    }

    @Test public void batchUsesOneFloorAndExportsProductionSqlForSqliteVerification() throws Exception {
        Fixture fixture = new Fixture();
        QuickSearch regular = bookmark(7);
        QuickSearch unsupported = bookmark(8);
        unsupported.mode = -999;
        assertFalse(BookmarkUpdatePolicy.validate(unsupported).supported);
        assertEquals(2, local.resetBookmarkBaselines(fixture.db,
                Arrays.asList(regular, unsupported), 100_000));
        assertEquals(2, fixture.rows.size());
        for (Object[] row : fixture.rows.values()) {
            assertEquals("shared", row[0]);
            assertEquals("BOOKMARK_RESET", row[1]);
            assertEquals(101L, row[5]);
        }
        assertEquals(6, fixture.writes.size());
        Field field = SubscriptionSchema.class.getDeclaredField("SQL");
        field.setAccessible(true);
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("schema", field.get(null));
        trace.put("writes", fixture.writes);
        Path target = Paths.get("build", "reports", "bookmark-reset", "sql-trace.json");
        Files.createDirectories(target.getParent());
        Files.write(target, JSON.toJSONString(trace).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void renameKeepsResetButQueryChangeAndDeletionCleanItUp() {
        Fixture fixture = new Fixture();
        QuickSearch bookmark = bookmark(7);
        String signature = BookmarkUpdatePolicy.validate(bookmark).signature;
        local.resetBookmarkBaselines(fixture.db, Arrays.asList(bookmark), 100_000);
        bookmark.name = "renamed";
        fixture.writes.clear();
        local.prepareBookmarkSignature(fixture.db, "7", BookmarkUpdatePolicy.validate(bookmark).signature);
        assertTrue(fixture.writes.isEmpty());
        assertEquals(1, fixture.rows.size());
        bookmark.keyword = "artist:changed";
        local.prepareBookmarkSignature(fixture.db, "7", BookmarkUpdatePolicy.validate(bookmark).signature);
        assertTrue(fixture.rows.isEmpty());
        local.resetBookmarkBaselines(fixture.db, Arrays.asList(bookmark), 200_000);
        local.deleteBookmarkState(fixture.db, 7);
        assertTrue(fixture.rows.isEmpty());
    }

    @Test public void resetAfterQueryEditReplacesStaleResetWithoutLosingNewFloor() {
        Fixture fixture = new Fixture();
        QuickSearch bookmark = bookmark(7);
        local.resetBookmarkBaselines(fixture.db, Arrays.asList(bookmark), 100_000);
        bookmark.keyword = "artist:changed";
        local.resetBookmarkBaselines(fixture.db, Arrays.asList(bookmark), 200_000);
        assertEquals(1, fixture.rows.size());
        fixture.oldStateSignature = true;
        String signature = BookmarkUpdatePolicy.validate(bookmark).signature;
        local.prepareBookmarkSignature(fixture.db, "7", signature);
        assertEquals(201, repository.readCheckpoint(fixture.db,
                FeedCheckpointKeys.bookmarkReset("7", signature)).current.time);
    }

    private static QuickSearch bookmark(long id) {
        QuickSearch search = new QuickSearch(id);
        search.keyword = "artist:example";
        return search;
    }
    private static CheckpointKey key(String account, String type, String id, String signature) {
        return new CheckpointKey(account, type, id, signature);
    }

    static final class Fixture implements InvocationHandler {
        final List<String> tags = new ArrayList<>();
        final Map<List<String>, Object[]> rows = new LinkedHashMap<>();
        final List<Map<String, Object>> writes = new ArrayList<>();
        final Database db = (Database) Proxy.newProxyInstance(Database.class.getClassLoader(),
                new Class<?>[]{Database.class}, this);
        Map<List<String>, Object[]> saved;
        boolean successful;
        boolean oldStateSignature;
        int begun, ended, committed, failAtWrite = -1;

        void seed(CheckpointKey key, long previous, long current, String previousGids, String currentGids) {
            rows.put(Arrays.asList(key.accountKey, key.sourceType, key.sourceKey, key.querySignature),
                    new Object[]{key.accountKey, key.sourceType, key.sourceKey, key.querySignature,
                            previous, current, previousGids, currentGids, 10L});
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "beginTransaction":
                    begun++;
                    saved = new LinkedHashMap<>(rows);
                    successful = false;
                    return null;
                case "setTransactionSuccessful": successful = true; return null;
                case "endTransaction":
                    ended++;
                    if (successful) committed++;
                    else { rows.clear(); rows.putAll(saved); }
                    return null;
                case "rawQuery":
                    String query = (String) args[0];
                    if (query.equals("SELECT TAG_NAME FROM LOCAL_FOLLOW_TAG ORDER BY TAG_NAME")) {
                        assertTrue("follow snapshot must be read inside transaction", begun > ended);
                        List<String> selectedTags = new ArrayList<>(tags);
                        Collections.sort(selectedTags);
                        int[] index = {-1};
                        return Proxy.newProxyInstance(Cursor.class.getClassLoader(),
                                new Class<?>[]{Cursor.class}, (cursor, call, params) -> {
                                    switch (call.getName()) {
                                        case "moveToNext": return ++index[0] < selectedTags.size();
                                        case "getString": return selectedTags.get(index[0]);
                                        case "close": return null;
                                        default: throw new AssertionError(call.getName());
                                    }
                                });
                    }
                    String[] queryArgs = (String[]) args[1];
                    Object[] selected;
                    if (query.startsWith("SELECT PREVIOUS_TIME,")) {
                        selected = rows.get(Arrays.asList(queryArgs));
                    } else if (query.startsWith("SELECT 1 FROM LOCAL_UPDATE_STATE")) {
                        selected = oldStateSignature ? new Object[0] : null;
                    } else if (query.startsWith("SELECT 1 FROM FEED_CHECKPOINT")) {
                        selected = rows.keySet().stream().anyMatch(k -> k.get(1).equals("BOOKMARK_RESET")
                                && k.get(2).equals(queryArgs[0]) && !k.get(3).equals(queryArgs[1]))
                                ? new Object[0] : null;
                    } else { throw new AssertionError(query); }
                    Object[] row = selected;
                    return Proxy.newProxyInstance(Cursor.class.getClassLoader(),
                            new Class<?>[]{Cursor.class}, (cursor, call, params) -> {
                                switch (call.getName()) {
                                    case "moveToFirst": return row != null;
                                    case "getLong": return ((Number) row[4 + (int) params[0]]).longValue();
                                    case "getString": return (String) row[4 + (int) params[0]];
                                    case "close": return null;
                                    default: throw new AssertionError(call.getName());
                                }
                            });
                case "execSQL":
                    Map<String, Object> write = new LinkedHashMap<>();
                    String sql = (String) args[0];
                    Object[] values = ((Object[]) args[1]).clone();
                    write.put("sql", sql);
                    write.put("args", values);
                    writes.add(write);
                    if (writes.size() == failAtWrite) throw new IllegalStateException("injected write failure");
                    if (sql.startsWith("INSERT OR REPLACE INTO FEED_CHECKPOINT")) {
                        rows.put(Arrays.asList((String) values[0], (String) values[1],
                                (String) values[2], (String) values[3]), values);
                    } else if (sql.startsWith("DELETE FROM FEED_CHECKPOINT")) {
                        if (sql.contains("QUERY_SIGNATURE<>?")) {
                            rows.keySet().removeIf(k -> k.get(1).equals("BOOKMARK_RESET")
                                    && k.get(2).equals(values[0]) && !k.get(3).equals(values[1]));
                        } else if (sql.contains("SOURCE_TYPE LIKE 'LOCAL_FOLLOW%'")) {
                            rows.keySet().removeIf(k -> k.get(2).equals(values[0])
                                    && (k.get(1).startsWith("LOCAL_FOLLOW")
                                    || k.get(1).equals("SUBSCRIPTION_TAG_SEEN")));
                        } else if (sql.contains("SOURCE_TYPE IN")) {
                            rows.keySet().removeIf(k -> k.get(2).equals(values[0])
                                    && Arrays.asList("BOOKMARK_SYNC", "BOOKMARK_OPEN", "BOOKMARK_RESET", "QUICK_SEARCH").contains(k.get(1)));
                        } else {
                            rows.keySet().removeIf(k -> k.get(1).equals(values[0]) && k.get(2).equals(values[1]));
                        }
                    } else if (sql.equals("DELETE FROM LOCAL_FOLLOW_TAG WHERE TAG_NAME=?")) {
                        tags.remove(values[0]);
                    } else {
                        assertTrue(sql.startsWith("DELETE FROM LOCAL_BASELINE_QUEUE WHERE SOURCE_TYPE=?")
                                || sql.equals("DELETE FROM LOCAL_GLOBAL_CURSOR WHERE JOB_TYPE=?")
                                || sql.startsWith("DELETE FROM LOCAL_UPDATE_STATE WHERE SOURCE_TYPE=?")
                                || sql.startsWith("DELETE FROM LOCAL_UNREAD_GALLERY WHERE SOURCE_TYPE=?"));
                    }
                    return null;
                default: throw new AssertionError(method.getName());
            }
        }
    }
}
