package com.hippo.ehviewer.subscription;

import android.database.Cursor;
import android.text.TextUtils;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.userTag.UserTag;

import org.greenrobot.greendao.database.Database;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Single serialized access point for all subscription persistence. */
public final class SubscriptionRepository {
    private static final SubscriptionRepository INSTANCE = new SubscriptionRepository();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "subscription-db");
        thread.setDaemon(true);
        return thread;
    });

    private SubscriptionRepository() {}
    public static SubscriptionRepository getInstance() { return INSTANCE; }
    public <T> Future<T> submit(Callable<T> task) { return executor.submit(task); }
    public Future<?> execute(Runnable task) { return executor.submit(task); }

    public String getAccountKey() {
        if (!Settings.isLogin()) return "guest";
        String displayName = Settings.getDisplayName();
        return TextUtils.isEmpty(displayName) ? "user:" + Settings.getUserID() : "user:" + displayName;
    }

    public static String normalizeTagName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public void replaceTagSnapshot(String accountKey, List<UserTag> tags) {
        Database db = EhDB.getDatabase();
        long now = System.currentTimeMillis();
        Map<String, Long> firstSeen = new HashMap<>();
        try (Cursor cursor = db.rawQuery("SELECT TAG_NAME,FIRST_SEEN_AT FROM SUBSCRIPTION_TAG_CACHE WHERE ACCOUNT_KEY=?",
                new String[]{accountKey})) {
            while (cursor.moveToNext()) firstSeen.put(cursor.getString(0), cursor.getLong(1));
        }
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM SUBSCRIPTION_TAG_CACHE WHERE ACCOUNT_KEY=?", new Object[]{accountKey});
            for (UserTag tag : tags) {
                String name = normalizeTagName(tag.tagName);
                if (name.isEmpty()) continue;
                db.execSQL("INSERT INTO SUBSCRIPTION_TAG_CACHE " +
                                "(ACCOUNT_KEY,TAG_NAME,SERVER_TAG_ID,WATCHED,HIDDEN,COLOR,WEIGHT,FIRST_SEEN_AT,SYNCED_AT) " +
                                "VALUES (?,?,?,?,?,?,?,?,?)",
                        new Object[]{accountKey, name, safe(tag.userTagId), tag.watched ? 1 : 0,
                                tag.hidden ? 1 : 0, safe(tag.color), tag.tagWeight,
                                firstSeen.containsKey(name) ? firstSeen.get(name) : now, now});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public Set<String> loadSubscribedTagSet(String accountKey) {
        Set<String> result = new HashSet<>();
        try (Cursor cursor = EhDB.getDatabase().rawQuery(
                "SELECT TAG_NAME FROM SUBSCRIPTION_TAG_CACHE WHERE ACCOUNT_KEY=? AND WATCHED=1 AND HIDDEN=0",
                new String[]{accountKey})) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return Collections.unmodifiableSet(result);
    }

    public FeedCheckpoint readCheckpoint(CheckpointKey key) {
        return readCheckpoint(EhDB.getDatabase(), key);
    }

    FeedCheckpoint readCheckpoint(Database db, CheckpointKey key) {
        FeedCheckpoint saved = readStoredCheckpoint(db, key);
        CheckpointKey resetKey;
        if (LocalFollowRepository.CHECKPOINT_BOOKMARK.equals(key.sourceType)) {
            resetKey = FeedCheckpointKeys.bookmarkReset(key.sourceKey, key.querySignature);
        } else if (LocalFollowRepository.CHECKPOINT_FOLLOW.equals(key.sourceType)) {
            resetKey = FeedCheckpointKeys.followReset(key.sourceKey, key.querySignature);
        } else {
            return saved;
        }
        FeedBoundary floor = readStoredCheckpoint(db, resetKey).current;
        return new FeedCheckpoint(saved.previous,
                LocalBaselineResetPolicy.newer(saved.current, floor), saved.updatedAt);
    }

    /** Stored content boundaries, without the manual scan floor used by update readers. */
    FeedCheckpoint readStoredCheckpoint(Database db, CheckpointKey key) {
        try (Cursor cursor = db.rawQuery(
                "SELECT PREVIOUS_TIME,\"CURRENT_TIME\",PREVIOUS_GIDS,CURRENT_GIDS,UPDATED_AT " +
                        "FROM FEED_CHECKPOINT WHERE ACCOUNT_KEY=? AND SOURCE_TYPE=? AND SOURCE_KEY=? AND QUERY_SIGNATURE=?",
                args(key))) {
            return readCheckpoint(cursor);
        }
    }

    /**
     * Returns the latest successfully committed cursor for a shared local source, regardless of
     * the account/site context that performed the check.
     */
    public FeedCheckpoint readLatestCheckpoint(String sourceType, String sourceKey) {
        try (Cursor cursor = EhDB.getDatabase().rawQuery(
                "SELECT PREVIOUS_TIME,\"CURRENT_TIME\",PREVIOUS_GIDS,CURRENT_GIDS,UPDATED_AT " +
                        "FROM FEED_CHECKPOINT WHERE SOURCE_TYPE=? AND SOURCE_KEY=? " +
                        "ORDER BY UPDATED_AT DESC LIMIT 1",
                new String[]{sourceType, sourceKey})) {
            return readCheckpoint(cursor);
        }
    }

    private static FeedCheckpoint readCheckpoint(Cursor cursor) {
        if (!cursor.moveToFirst()) {
            return new FeedCheckpoint(FeedBoundary.EMPTY, FeedBoundary.EMPTY, 0);
        }
        return new FeedCheckpoint(
                new FeedBoundary(cursor.getLong(0), parseGids(cursor.getString(2))),
                new FeedBoundary(cursor.getLong(1), parseGids(cursor.getString(3))),
                cursor.getLong(4));
    }

    public void advanceCheckpoint(CheckpointKey key, FeedBoundary boundary) {
        if (boundary == null || boundary.time == 0) return;
        advanceCheckpoint(EhDB.getDatabase(), key, boundary);
    }

    void advanceCheckpoint(Database db, CheckpointKey key, FeedBoundary boundary) {
        if (boundary == null || boundary.time == 0) return;
        FeedCheckpoint old = readStoredCheckpoint(db, key);
        // Keep same-second knowledge, but allow a provisional timestamp to be refined.
        // The manual reset floor remains separate and is applied only when scanning.
        if ((LocalFollowRepository.CHECKPOINT_BOOKMARK.equals(key.sourceType)
                || LocalFollowRepository.CHECKPOINT_FOLLOW.equals(key.sourceType))
                && boundary.time == old.current.time) {
            boundary = LocalBaselineResetPolicy.newer(boundary, old.current);
        }
        upsertCheckpoint(db, key, old.current, boundary);
    }

    /** Refines an initial/provisional baseline without manufacturing a previous sync boundary. */
    public void establishCheckpoint(CheckpointKey key, FeedBoundary boundary) {
        if (boundary == null || boundary.time == 0) return;
        establishCheckpoint(EhDB.getDatabase(), key, boundary);
    }

    void establishCheckpoint(Database db, CheckpointKey key, FeedBoundary boundary) {
        if (boundary == null || boundary.time == 0) return;
        FeedCheckpoint old = readStoredCheckpoint(db, key);
        // Keep same-second knowledge, but allow a provisional timestamp to be refined.
        // The manual reset floor remains separate and is applied only when scanning.
        if ((LocalFollowRepository.CHECKPOINT_BOOKMARK.equals(key.sourceType)
                || LocalFollowRepository.CHECKPOINT_FOLLOW.equals(key.sourceType))
                && boundary.time == old.current.time) {
            boundary = LocalBaselineResetPolicy.newer(boundary, old.current);
        }
        upsertCheckpoint(db, key, old.previous, boundary);
    }

    public FeedBoundary readHomeManualBoundary() {
        return readCheckpoint(FeedCheckpointKeys.homeManual()).current;
    }

    /** Replaces the device-wide homepage marker without creating an automatic history step. */
    public void replaceHomeManualBoundary(FeedBoundary boundary) {
        if (boundary == null || boundary.isEmpty()) return;
        upsertCheckpoint(EhDB.getDatabase(), FeedCheckpointKeys.homeManual(),
                FeedBoundary.EMPTY, boundary);
    }

    private static void upsertCheckpoint(Database db, CheckpointKey key,
                                         FeedBoundary previous, FeedBoundary current) {
        db.execSQL("INSERT OR REPLACE INTO FEED_CHECKPOINT " +
                        "(ACCOUNT_KEY,SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE,PREVIOUS_TIME,CURRENT_TIME,PREVIOUS_GIDS,CURRENT_GIDS,UPDATED_AT) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)",
                new Object[]{key.accountKey, key.sourceType, key.sourceKey, key.querySignature,
                        previous.time, current.time, serializeGids(previous.gids),
                        serializeGids(current.gids), System.currentTimeMillis()});
    }

    public Map<String, TagUpdateState> readTagCounts(String accountKey, String signature) {
        Map<String, TagUpdateState> result = new HashMap<>();
        try (Cursor cursor = EhDB.getDatabase().rawQuery(
                "SELECT TAG_NAME,COUNT,COUNT_STATE,CHECKED_AT FROM SUBSCRIPTION_TAG_UPDATE_STATE " +
                        "WHERE ACCOUNT_KEY=? AND QUERY_SIGNATURE=?", new String[]{accountKey, signature})) {
            while (cursor.moveToNext()) {
                TagUpdateState value = new TagUpdateState(cursor.getString(0), cursor.getInt(1),
                        TagUpdateState.State.valueOf(cursor.getString(2)), cursor.getLong(3));
                result.put(value.tagName, value);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public void replaceTagCounts(String accountKey, String signature,
                                 Map<String, TagUpdateState> states) {
        Database db = EhDB.getDatabase();
        db.execSQL("DELETE FROM SUBSCRIPTION_TAG_UPDATE_STATE WHERE ACCOUNT_KEY=? AND QUERY_SIGNATURE=?",
                new Object[]{accountKey, signature});
        for (TagUpdateState state : states.values()) {
            db.execSQL("INSERT INTO SUBSCRIPTION_TAG_UPDATE_STATE " +
                            "(ACCOUNT_KEY,TAG_NAME,QUERY_SIGNATURE,COUNT,COUNT_STATE,CHECKED_AT) VALUES (?,?,?,?,?,?)",
                    new Object[]{accountKey, state.tagName, signature, state.count,
                            state.state.name(), state.checkedAt});
        }
    }

    /** Atomically advances the aggregate sync boundary and accumulates exact unread deltas. */
    public void commitAggregateUpdate(CheckpointKey key, FeedBoundary boundary,
                                      Map<String, TagUpdateState> deltas,
                                      boolean baselineOnly) {
        if (boundary == null || boundary.time == 0) return;
        Database db = EhDB.getDatabase();
        db.beginTransaction();
        try {
            FeedCheckpoint old = readCheckpoint(key);
            upsertCheckpoint(db, key, old.current, boundary);
            Map<String, TagUpdateState> existing = readTagCounts(key.accountKey, key.querySignature);
            replaceTagCounts(key.accountKey, key.querySignature,
                    SubscriptionUpdateCalculator.mergeUnread(existing, deltas, baselineOnly));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Advances a local seen boundary and clears only the unread count that existed when the
     * feed was entered. A newer concurrent sync has a later CHECKED_AT and is preserved.
     */
    public void commitSeenBoundary(CheckpointKey key, FeedBoundary boundary,
                                   String tagName, long enteredAt) {
        if (boundary == null || boundary.time == 0) return;
        Database db = EhDB.getDatabase();
        db.beginTransaction();
        try {
            FeedCheckpoint old = readCheckpoint(key);
            upsertCheckpoint(db, key, old.current, boundary);
            String normalized = normalizeTagName(tagName);
            if (!normalized.isEmpty()) {
                db.execSQL("UPDATE SUBSCRIPTION_TAG_UPDATE_STATE " +
                                "SET COUNT=0,COUNT_STATE=?,CHECKED_AT=? " +
                                "WHERE ACCOUNT_KEY=? AND TAG_NAME=? AND QUERY_SIGNATURE=? AND CHECKED_AT<=?",
                        new Object[]{TagUpdateState.State.EXACT.name(), System.currentTimeMillis(),
                                key.accountKey, normalized, key.querySignature, enteredAt});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void deleteQuickSearchCheckpoints(String accountKey, long quickSearchId) {
        EhDB.getDatabase().execSQL(
                "DELETE FROM FEED_CHECKPOINT WHERE ACCOUNT_KEY=? AND SOURCE_TYPE='QUICK_SEARCH' AND SOURCE_KEY=?",
                new Object[]{accountKey, Long.toString(quickSearchId)});
    }

    public static String serializeGids(Set<Long> gids) {
        StringBuilder out = new StringBuilder();
        for (Long gid : new TreeSet<>(gids)) {
            if (out.length() > 0) out.append(',');
            out.append(gid);
        }
        return out.toString();
    }

    public static Set<Long> parseGids(String value) {
        if (value == null || value.isEmpty()) return Collections.emptySet();
        Set<Long> result = new TreeSet<>();
        for (String part : value.split(",")) {
            try { result.add(Long.parseLong(part)); } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String[] args(CheckpointKey key) {
        return new String[]{key.accountKey, key.sourceType, key.sourceKey, key.querySignature};
    }
}
