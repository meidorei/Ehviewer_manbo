package com.hippo.ehviewer.subscription;

import android.database.Cursor;

import com.hippo.ehviewer.EhDB;

import org.greenrobot.greendao.database.Database;
import org.greenrobot.greendao.database.DatabaseStatement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.QuickSearch;

/** Device-global local follows. Server Watched tags never enter this repository. */
public final class LocalFollowRepository {
    public static final String SOURCE_FOLLOW = "LOCAL_FOLLOW";
    public static final String SOURCE_BOOKMARK = "QUICK_SEARCH";
    public static final String CHECKPOINT_FOLLOW = "LOCAL_FOLLOW_SYNC";
    public static final String CHECKPOINT_BOOKMARK = "BOOKMARK_SYNC";
    public static final String CHECKPOINT_FOLLOW_OPEN = "LOCAL_FOLLOW_OPEN";
    public static final String CHECKPOINT_BOOKMARK_OPEN = "BOOKMARK_OPEN";
    private static final String SHARED_OPEN_ACCOUNT = "shared";
    static final String READ_BOOKMARK_SYNC_BOUNDARY_SQL =
            "SELECT \"CURRENT_TIME\",CURRENT_GIDS FROM FEED_CHECKPOINT " +
                    "WHERE SOURCE_TYPE=? AND SOURCE_KEY=? AND QUERY_SIGNATURE=? " +
                    "AND \"CURRENT_TIME\">0 ORDER BY UPDATED_AT DESC LIMIT 1";
    public static final String FIXED_CHINESE_SIGNATURE =
            QuerySignatureFactory.create("language:chinese$", true);
    private static final LocalFollowRepository INSTANCE = new LocalFollowRepository();

    private LocalFollowRepository() {}

    public static LocalFollowRepository getInstance() {
        return INSTANCE;
    }

    public List<String> getAll() {
        return getAll(EhDB.getDatabase());
    }

    private List<String> getAll(Database db) {
        List<String> result = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
                "SELECT TAG_NAME FROM LOCAL_FOLLOW_TAG ORDER BY TAG_NAME", null)) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return Collections.unmodifiableList(result);
    }

    public Set<String> getSet() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(getAll()));
    }

    public boolean contains(String rawTag) {
        String tag = SubscriptionRepository.normalizeTagName(rawTag);
        if (tag.isEmpty()) return false;
        try (Cursor cursor = EhDB.getDatabase().rawQuery(
                "SELECT 1 FROM LOCAL_FOLLOW_TAG WHERE TAG_NAME=? LIMIT 1",
                new String[]{tag})) {
            return cursor.moveToFirst();
        }
    }

    public boolean add(String rawTag) {
        String tag = SubscriptionRepository.normalizeTagName(rawTag);
        if (!LocalFollowJson.isValidStandardTag(tag)) return false;
        Database db = EhDB.getDatabase();
        long now = System.currentTimeMillis();
        boolean inserted = false;
        db.beginTransaction();
        try {
            db.execSQL("INSERT INTO LOCAL_FOLLOW_TAG(TAG_NAME,ADDED_AT,LAST_CHECKED_AT) VALUES(?,?,0)",
                    new Object[]{tag, now});
            initializeFollowBaseline(tag, now, LocalBaselineQueue.METHOD_FOLLOW_TAG,
                    batchKey("follow", now));
            db.setTransactionSuccessful();
            inserted = true;
        } catch (RuntimeException duplicate) {
            inserted = false;
        } finally {
            db.endTransaction();
        }
        if (inserted) SubscriptionSnapshot.refreshFromDatabase();
        return inserted;
    }

    public void remove(String rawTag) {
        String tag = SubscriptionRepository.normalizeTagName(rawTag);
        if (tag.isEmpty()) return;
        Database db = EhDB.getDatabase();
        db.beginTransaction();
        try {
            deleteFollowData(db, tag);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        SubscriptionSnapshot.refreshFromDatabase();
    }

    static void deleteFollowData(Database db, String tag) {
        db.execSQL("DELETE FROM LOCAL_FOLLOW_TAG WHERE TAG_NAME=?", new Object[]{tag});
        db.execSQL("DELETE FROM LOCAL_UPDATE_STATE WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                new Object[]{SOURCE_FOLLOW, tag});
        db.execSQL("DELETE FROM LOCAL_UNREAD_GALLERY WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                new Object[]{SOURCE_FOLLOW, tag});
        db.execSQL("DELETE FROM FEED_CHECKPOINT WHERE " +
                    "(SOURCE_TYPE LIKE 'LOCAL_FOLLOW%' OR SOURCE_TYPE='SUBSCRIPTION_TAG_SEEN') " +
                    "AND SOURCE_KEY=?",
                new Object[]{tag});
        db.execSQL("DELETE FROM LOCAL_BASELINE_QUEUE WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                new Object[]{SOURCE_FOLLOW, tag});
    }

    public ImportResult importTags(Set<String> incoming, boolean replace) {
        Database db = EhDB.getDatabase();
        Set<String> existing = new LinkedHashSet<>(getAll());
        int before = existing.size();
        Set<String> added = new LinkedHashSet<>(incoming);
        added.removeAll(existing);
        db.beginTransaction();
        try {
            if (replace) {
                for (String old : existing) {
                    if (!incoming.contains(old)) {
                        deleteFollowData(db, old);
                    }
                }
            }
            long now = System.currentTimeMillis();
            String batch = batchKey("follow-import", now);
            for (String tag : added) {
                db.execSQL("INSERT INTO LOCAL_FOLLOW_TAG(TAG_NAME,ADDED_AT,LAST_CHECKED_AT) VALUES(?,?,0)",
                        new Object[]{tag, now});
                initializeFollowBaseline(tag, now, LocalBaselineQueue.METHOD_FOLLOW_GLOBAL,
                        batch);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        SubscriptionSnapshot.refreshFromDatabase();
        int after = getAll().size();
        return new ImportResult(before, after, replace, added.size());
    }

    /**
     * Initializes rows inserted by database restore without touching retained tags.
     * The caller may already own the surrounding database transaction.
     */
    public void initializeImportedFollowTags(Collection<String> tags, long addedAt) {
        if (tags == null || tags.isEmpty()) return;
        String batch = batchKey("follow-restore", addedAt);
        for (String raw : tags) {
            String tag = SubscriptionRepository.normalizeTagName(raw);
            if (!tag.isEmpty()) {
                initializeFollowBaseline(tag, addedAt,
                        LocalBaselineQueue.METHOD_FOLLOW_GLOBAL, batch);
            }
        }
    }

    /** Read the complete follow list and reset its floor in the same transaction. */
    int resetFollowBaselines(long resetAt) {
        return resetFollowBaselines(EhDB.getDatabase(), resetAt);
    }

    int resetFollowBaselines(Database db, long resetAt) {
        db.beginTransaction();
        try {
            List<String> tags = getAll(db);
            if (tags.isEmpty()) return 0;
            FeedBoundary floor = BaselineBoundaryPolicy.provisional(resetAt);
            SubscriptionRepository repository = SubscriptionRepository.getInstance();
            for (String tag : tags) {
                CheckpointKey key = FeedCheckpointKeys.followReset(tag, FIXED_CHINESE_SIGNATURE);
                FeedBoundary previous = repository.readStoredCheckpoint(db, key).current;
                FeedBoundary reset = LocalBaselineResetPolicy.newer(previous, floor);
                repository.establishCheckpoint(db, key, reset);
                advanceFollowOpenBoundary(db, tag, FIXED_CHINESE_SIGNATURE, reset);
                db.execSQL("DELETE FROM LOCAL_UNREAD_GALLERY WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                        new Object[]{SOURCE_FOLLOW, tag});
                db.execSQL("INSERT OR IGNORE INTO LOCAL_UPDATE_STATE " +
                                "(SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE) VALUES(?,?,?)",
                        new Object[]{SOURCE_FOLLOW, tag, FIXED_CHINESE_SIGNATURE});
                // Reset is a local read action, not a successful network check.
                db.execSQL("UPDATE LOCAL_UPDATE_STATE SET COUNT=0,COUNT_STATE='EXACT',ERROR='' " +
                                "WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                        new Object[]{SOURCE_FOLLOW, tag});
            }
            db.execSQL("DELETE FROM LOCAL_BASELINE_QUEUE WHERE SOURCE_TYPE=?",
                    new Object[]{SOURCE_FOLLOW});
            db.execSQL("DELETE FROM LOCAL_GLOBAL_CURSOR WHERE JOB_TYPE=?",
                    new Object[]{LocalGlobalCursorStore.TYPE_FOLLOW});
            db.setTransactionSuccessful();
            return tags.size();
        } finally {
            db.endTransaction();
        }
    }

    /** Called only while LocalUpdateService holds the exclusive maintenance gate. */
    int resetBookmarkBaselines(long resetAt) {
        // Keep the existing EhDB -> transaction lock order used by bookmark edits/imports.
        synchronized (EhDB.class) {
            return resetBookmarkBaselines(EhDB.getDatabase(), EhDB.getAllQuickSearch(), resetAt);
        }
    }

    int resetBookmarkBaselines(Database db, List<QuickSearch> bookmarks, long resetAt) {
        if (bookmarks.isEmpty()) return 0;
        FeedBoundary floor = BaselineBoundaryPolicy.provisional(resetAt);
        SubscriptionRepository repository = SubscriptionRepository.getInstance();
        db.beginTransaction();
        try {
            for (QuickSearch bookmark : bookmarks) {
                BookmarkUpdatePolicy.Result policy = BookmarkUpdatePolicy.validate(bookmark);
                CheckpointKey key = FeedCheckpointKeys.bookmarkReset(
                        Long.toString(bookmark.id), policy.signature);
                db.execSQL("DELETE FROM FEED_CHECKPOINT WHERE SOURCE_TYPE='BOOKMARK_RESET' " +
                                "AND SOURCE_KEY=? AND QUERY_SIGNATURE<>?",
                        new Object[]{key.sourceKey, key.querySignature});
                FeedBoundary previous = repository.readCheckpoint(db, key).current;
                repository.establishCheckpoint(db, key,
                        LocalBaselineResetPolicy.newer(previous, floor));
            }
            db.execSQL("DELETE FROM LOCAL_BASELINE_QUEUE WHERE SOURCE_TYPE=?",
                    new Object[]{SOURCE_BOOKMARK});
            db.execSQL("DELETE FROM LOCAL_GLOBAL_CURSOR WHERE JOB_TYPE=?",
                    new Object[]{LocalGlobalCursorStore.TYPE_BOOKMARK});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return bookmarks.size();
    }

    public void initializeBookmark(QuickSearch search, long addedAt) {
        BookmarkUpdatePolicy.Result policy = BookmarkUpdatePolicy.resolve(search);
        if (search == null || search.id == null || policy.signature.isEmpty()) return;
        String key = Long.toString(search.id);
        if (!policy.supported) {
            markError(SOURCE_BOOKMARK, key, policy.signature, policy.error);
            return;
        }
        writeProvisionalState(SOURCE_BOOKMARK, key, policy.signature, addedAt);
        LocalBaselineQueue.enqueue(batchKey("bookmark", addedAt), SOURCE_BOOKMARK, key,
                policy.signature, LocalBaselineQueue.METHOD_BOOKMARK, addedAt);
    }

    private void initializeFollowBaseline(String tag, long addedAt, String method,
                                          String batch) {
        writeProvisionalState(SOURCE_FOLLOW, tag, FIXED_CHINESE_SIGNATURE, addedAt);
        LocalBaselineQueue.enqueue(batch, SOURCE_FOLLOW, tag, FIXED_CHINESE_SIGNATURE,
                method, addedAt);
    }

    private static String batchKey(String prefix, long now) {
        return prefix + '-' + now + '-' + System.nanoTime();
    }

    public TagUpdateState readState(String sourceType, String sourceKey, String signature) {
        return readState(EhDB.getDatabase(), sourceType, sourceKey, signature);
    }

    TagUpdateState readState(Database db, String sourceType, String sourceKey, String signature) {
        try (Cursor cursor = db.rawQuery(
                "SELECT COUNT,COUNT_STATE,CHECKED_AT FROM LOCAL_UPDATE_STATE " +
                        "WHERE SOURCE_TYPE=? AND SOURCE_KEY=? AND QUERY_SIGNATURE=?",
                new String[]{sourceType, sourceKey, signature})) {
            if (!cursor.moveToFirst()) {
                return new TagUpdateState(sourceKey, 0, TagUpdateState.State.EXACT, 0);
            }
            return new TagUpdateState(sourceKey, cursor.getInt(0),
                    TagUpdateState.State.valueOf(cursor.getString(1)), cursor.getLong(2));
        }
    }

    public int countCapped(String sourceType) {
        try (Cursor cursor = EhDB.getDatabase().rawQuery(
                "SELECT COUNT(*) FROM LOCAL_UPDATE_STATE WHERE SOURCE_TYPE=? " +
                        "AND COUNT_STATE='LOWER_BOUND'",
                new String[]{sourceType})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public void writeState(String sourceType, String sourceKey, String signature,
                           int count, TagUpdateState.State state, String error) {
        writeState(EhDB.getDatabase(), sourceType, sourceKey, signature, count, state, error);
    }

    void writeState(Database db, String sourceType, String sourceKey, String signature,
                    int count, TagUpdateState.State state, String error) {
        writeStateAt(db, sourceType, sourceKey, signature, count, state, error,
                System.currentTimeMillis());
    }

    public void writeProvisionalState(String sourceType, String sourceKey, String signature,
                                      long addedAt) {
        writeStateAt(EhDB.getDatabase(), sourceType, sourceKey, signature,
                0, TagUpdateState.State.EXACT, "",
                addedAt);
    }

    private void writeStateAt(Database db, String sourceType, String sourceKey, String signature,
                              int count, TagUpdateState.State state, String error,
                              long checkedAt) {
        db.execSQL(
                "INSERT OR REPLACE INTO LOCAL_UPDATE_STATE " +
                        "(SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE,COUNT,COUNT_STATE,CHECKED_AT,ERROR) " +
                        "VALUES(?,?,?,?,?,?,?)",
                new Object[]{sourceType, sourceKey, signature, count, state.name(),
                        checkedAt, error == null ? "" : error});
    }

    public void clearState(String sourceType, String sourceKey) {
        clearStateAt(sourceType, sourceKey, Long.MAX_VALUE);
    }

    public void clearStateAt(String sourceType, String sourceKey, long snapshotAt) {
        Database db = EhDB.getDatabase();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM LOCAL_UNREAD_GALLERY WHERE SOURCE_TYPE=? AND SOURCE_KEY=? " +
                            "AND DETECTED_AT<=?",
                    new Object[]{sourceType, sourceKey, snapshotAt});
            int remaining = unreadCount(db, sourceType, sourceKey);
            db.execSQL("UPDATE LOCAL_UPDATE_STATE SET COUNT=?,COUNT_STATE='EXACT',ERROR='',CHECKED_AT=? " +
                            "WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                    new Object[]{remaining, System.currentTimeMillis(), sourceType, sourceKey});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Called inside the same transaction that saves the new home download. */
    public void clearArtistUnreadForDownload(Database db, long gid) {
        String matchingArtists = "SOURCE_TYPE=? AND SOURCE_KEY LIKE 'artist:%' AND GID=?";
        db.execSQL("UPDATE LOCAL_UPDATE_STATE SET COUNT=(" +
                        "SELECT COUNT(*) FROM LOCAL_UNREAD_GALLERY u " +
                        "WHERE u.SOURCE_TYPE=LOCAL_UPDATE_STATE.SOURCE_TYPE " +
                        "AND u.SOURCE_KEY=LOCAL_UPDATE_STATE.SOURCE_KEY AND u.GID<>?) " +
                        "WHERE SOURCE_TYPE=? AND QUERY_SIGNATURE=? AND SOURCE_KEY IN (" +
                        "SELECT SOURCE_KEY FROM LOCAL_UNREAD_GALLERY WHERE " + matchingArtists + ")",
                new Object[]{gid, SOURCE_FOLLOW, FIXED_CHINESE_SIGNATURE, SOURCE_FOLLOW, gid});
        db.execSQL("DELETE FROM LOCAL_UNREAD_GALLERY WHERE " + matchingArtists,
                new Object[]{SOURCE_FOLLOW, gid});
    }

    public long captureUnreadSnapshot(String sourceType, String sourceKey) {
        try (Cursor cursor = EhDB.getDatabase().rawQuery(
                "SELECT COALESCE(MAX(rowid),0) FROM LOCAL_UNREAD_GALLERY " +
                        "WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                new String[]{sourceType, sourceKey})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    public ArtistUnreadSnapshot captureArtistUnread(String tag) {
        long lastRowId = 0;
        Set<Long> gids = new LinkedHashSet<>();
        try (Cursor cursor = EhDB.getDatabase().rawQuery(
                "SELECT rowid,GID FROM LOCAL_UNREAD_GALLERY WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                new String[]{SOURCE_FOLLOW, tag})) {
            while (cursor.moveToNext()) {
                lastRowId = Math.max(lastRowId, cursor.getLong(0));
                gids.add(cursor.getLong(1));
            }
        }
        return new ArtistUnreadSnapshot(lastRowId, gids);
    }

    public FeedBoundary readOpenBoundary(String sourceType, String sourceKey,
                                         String signature) {
        String checkpointType = SOURCE_FOLLOW.equals(sourceType)
                ? CHECKPOINT_FOLLOW_OPEN : CHECKPOINT_BOOKMARK_OPEN;
        FeedCheckpoint checkpoint = SubscriptionRepository.getInstance().readCheckpoint(
                new CheckpointKey(SHARED_OPEN_ACCOUNT, checkpointType, sourceKey, signature));
        if (!checkpoint.current.isEmpty()) return checkpoint.current;

        // One-time compatibility with the pre-v11 local-seen namespaces.
        String legacyType = SOURCE_FOLLOW.equals(sourceType)
                ? "SUBSCRIPTION_TAG_SEEN" : "QUICK_SEARCH";
        FeedCheckpoint legacy = SubscriptionRepository.getInstance()
                .readLatestCheckpoint(legacyType, sourceKey);
        return legacy.current;
    }

    /** Keep late opens and older page tops from undoing a manual reset or a newer open. */
    void advanceFollowOpenBoundary(Database db, String sourceKey, String signature,
                                   FeedBoundary pageTop) {
        SubscriptionRepository repository = SubscriptionRepository.getInstance();
        CheckpointKey openKey = new CheckpointKey(SHARED_OPEN_ACCOUNT,
                CHECKPOINT_FOLLOW_OPEN, sourceKey, signature);
        FeedBoundary current = repository.readStoredCheckpoint(db, openKey).current;
        FeedBoundary reset = repository.readStoredCheckpoint(db,
                FeedCheckpointKeys.followReset(sourceKey, signature)).current;
        repository.advanceCheckpoint(db, openKey, LocalBaselineResetPolicy.newer(
                LocalBaselineResetPolicy.newer(pageTop, current), reset));
    }

    /**
     * Clears only unread rows present when the list was entered and records the next-entry
     * marker. The currently displayed page keeps the boundary loaded before this transaction.
     */
    public void completeSuccessfulOpen(String sourceType, String sourceKey, String signature,
                                       FeedBoundary pageTop, long unreadSnapshotRowId) {
        completeSuccessfulOpen(EhDB.getDatabase(), sourceType, sourceKey, signature,
                pageTop, unreadSnapshotRowId);
    }

    void completeSuccessfulOpen(Database db, String sourceType, String sourceKey, String signature,
                                FeedBoundary pageTop, long unreadSnapshotRowId) {
        boolean hasPageTop = pageTop != null && !pageTop.isEmpty();
        if (!hasPageTop && !SOURCE_BOOKMARK.equals(sourceType)) return;
        String checkpointType = SOURCE_FOLLOW.equals(sourceType)
                ? CHECKPOINT_FOLLOW_OPEN : CHECKPOINT_BOOKMARK_OPEN;
        db.beginTransaction();
        try {
            if (hasPageTop) {
                if (SOURCE_FOLLOW.equals(sourceType)) {
                    advanceFollowOpenBoundary(db, sourceKey, signature, pageTop);
                } else {
                    SubscriptionRepository.getInstance().advanceCheckpoint(db,
                            new CheckpointKey(SHARED_OPEN_ACCOUNT, checkpointType,
                                    sourceKey, signature), pageTop);
                }
            }
            if (SOURCE_BOOKMARK.equals(sourceType)) {
                Set<Long> openedGids = readUnreadSnapshotGids(
                        db, sourceType, sourceKey, unreadSnapshotRowId);
                Map<String, Set<Long>> unreadByBookmark = readBookmarkUnreadGids(db);
                Map<String, Integer> remainingByBookmark =
                        BookmarkUnreadClearPolicy.remainingCounts(
                                sourceType, openedGids, unreadByBookmark);
                for (Long gid : openedGids) {
                    db.execSQL("DELETE FROM LOCAL_UNREAD_GALLERY " +
                                    "WHERE SOURCE_TYPE=? AND GID=?",
                            new Object[]{SOURCE_BOOKMARK, gid});
                }
                for (Map.Entry<String, Integer> entry : remainingByBookmark.entrySet()) {
                    if (sourceKey.equals(entry.getKey())) continue;
                    BookmarkUpdateRow affectedState = readBookmarkUpdateRow(
                            db, entry.getKey());
                    FeedBoundary synchronizedTop = affectedState == null
                            ? FeedBoundary.EMPTY
                            : readLatestBookmarkSyncBoundary(
                                    db, entry.getKey(), affectedState.signature);
                    CheckpointKey openKey = affectedState == null ? null
                            : new CheckpointKey(SHARED_OPEN_ACCOUNT,
                                    CHECKPOINT_BOOKMARK_OPEN, entry.getKey(),
                                    affectedState.signature);
                    FeedBoundary currentOpen = openKey == null
                            ? FeedBoundary.EMPTY
                            : SubscriptionRepository.getInstance()
                                    .readCheckpoint(db, openKey).current;
                    FeedBoundary boundaryToAdvance =
                            BookmarkUnreadClearPolicy.boundaryToAdvance(
                                    sourceKey, entry.getKey(),
                                    affectedState == null ? 0 : affectedState.count,
                                    entry.getValue(), affectedState == null
                                            ? TagUpdateState.State.UNKNOWN
                                            : affectedState.state,
                                    currentOpen, synchronizedTop);
                    // Preserve the other bookmark's state, error and checked time. In
                    // particular, a lower-bound state must remain 20+ after shared clearing.
                    db.execSQL("UPDATE LOCAL_UPDATE_STATE SET COUNT=? " +
                                    "WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                            new Object[]{entry.getValue(), SOURCE_BOOKMARK, entry.getKey()});
                    if (!boundaryToAdvance.isEmpty()) {
                        SubscriptionRepository.getInstance().advanceCheckpoint(
                                db, openKey, boundaryToAdvance);
                    }
                }
            } else {
                db.execSQL("DELETE FROM LOCAL_UNREAD_GALLERY " +
                                "WHERE SOURCE_TYPE=? AND SOURCE_KEY=? AND rowid<=?",
                        new Object[]{sourceType, sourceKey, unreadSnapshotRowId});
            }
            int remaining = unreadCount(db, sourceType, sourceKey);
            db.execSQL("UPDATE LOCAL_UPDATE_STATE SET COUNT=?,COUNT_STATE='EXACT',ERROR='',CHECKED_AT=? " +
                            "WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                    new Object[]{remaining, System.currentTimeMillis(), sourceType, sourceKey});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static Set<Long> readUnreadSnapshotGids(
            Database db, String sourceType, String sourceKey, long snapshotRowId) {
        Set<Long> result = new LinkedHashSet<>();
        try (Cursor cursor = db.rawQuery(
                "SELECT GID FROM LOCAL_UNREAD_GALLERY " +
                        "WHERE SOURCE_TYPE=? AND SOURCE_KEY=? AND rowid<=?",
                new String[]{sourceType, sourceKey, Long.toString(snapshotRowId)})) {
            while (cursor.moveToNext()) result.add(cursor.getLong(0));
        }
        return result;
    }

    private static Map<String, Set<Long>> readBookmarkUnreadGids(Database db) {
        Map<String, Set<Long>> result = new LinkedHashMap<>();
        try (Cursor cursor = db.rawQuery(
                "SELECT SOURCE_KEY,GID FROM LOCAL_UNREAD_GALLERY WHERE SOURCE_TYPE=?",
                new String[]{SOURCE_BOOKMARK})) {
            while (cursor.moveToNext()) {
                result.computeIfAbsent(cursor.getString(0), ignored -> new LinkedHashSet<>())
                        .add(cursor.getLong(1));
            }
        }
        return result;
    }

    private static BookmarkUpdateRow readBookmarkUpdateRow(
            Database db, String sourceKey) {
        try (Cursor cursor = db.rawQuery(
                "SELECT QUERY_SIGNATURE,COUNT,COUNT_STATE FROM LOCAL_UPDATE_STATE " +
                        "WHERE SOURCE_TYPE=? AND SOURCE_KEY=? " +
                        "ORDER BY CHECKED_AT DESC LIMIT 1",
                new String[]{SOURCE_BOOKMARK, sourceKey})) {
            if (!cursor.moveToFirst()) return null;
            return new BookmarkUpdateRow(cursor.getString(0), cursor.getInt(1),
                    TagUpdateState.State.valueOf(cursor.getString(2)));
        }
    }

    private static FeedBoundary readLatestBookmarkSyncBoundary(
            Database db, String sourceKey, String signature) {
        try (Cursor cursor = db.rawQuery(
                READ_BOOKMARK_SYNC_BOUNDARY_SQL,
                new String[]{CHECKPOINT_BOOKMARK, sourceKey, signature})) {
            if (!cursor.moveToFirst()) return FeedBoundary.EMPTY;
            return FeedBoundaryCodec.decode(cursor.getLong(0), cursor.getString(1));
        }
    }

    private static int unreadCount(Database db, String sourceType, String sourceKey) {
        try (Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM LOCAL_UNREAD_GALLERY WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                new String[]{sourceType, sourceKey})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private static final class BookmarkUpdateRow {
        final String signature;
        final int count;
        final TagUpdateState.State state;

        BookmarkUpdateRow(String signature, int count, TagUpdateState.State state) {
            this.signature = signature;
            this.count = count;
            this.state = state;
        }
    }

    public void deleteBookmarkState(long id) {
        deleteBookmarkState(EhDB.getDatabase(), id);
    }

    void deleteBookmarkState(Database db, long id) {
        String key = Long.toString(id);
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM LOCAL_UPDATE_STATE WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                    new Object[]{SOURCE_BOOKMARK, key});
            db.execSQL("DELETE FROM LOCAL_UNREAD_GALLERY WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                    new Object[]{SOURCE_BOOKMARK, key});
            db.execSQL("DELETE FROM FEED_CHECKPOINT WHERE SOURCE_TYPE IN " +
                            "('BOOKMARK_SYNC','BOOKMARK_OPEN','BOOKMARK_RESET','QUICK_SEARCH') AND SOURCE_KEY=?",
                    new Object[]{key});
            db.execSQL("DELETE FROM LOCAL_BASELINE_QUEUE WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                    new Object[]{SOURCE_BOOKMARK, key});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * A bookmark rename keeps its signature and state. Any query change gets a clean
     * zero baseline and cannot inherit unread GIDs or cursors from the former query.
     */
    public void prepareBookmarkSignature(String sourceKey, String signature) {
        if (sourceKey == null || signature == null || signature.isEmpty()) return;
        prepareBookmarkSignature(EhDB.getDatabase(), sourceKey, signature);
    }

    void prepareBookmarkSignature(Database db, String sourceKey, String signature) {
        boolean changed;
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM LOCAL_UPDATE_STATE WHERE SOURCE_TYPE=? AND SOURCE_KEY=? " +
                        "AND QUERY_SIGNATURE<>? LIMIT 1",
                new String[]{SOURCE_BOOKMARK, sourceKey, signature})) {
            changed = cursor.moveToFirst();
        }
        if (!changed) {
            try (Cursor cursor = db.rawQuery(
                    "SELECT 1 FROM FEED_CHECKPOINT WHERE SOURCE_TYPE='BOOKMARK_RESET' " +
                            "AND SOURCE_KEY=? AND QUERY_SIGNATURE<>? LIMIT 1",
                    new String[]{sourceKey, signature})) {
                changed = cursor.moveToFirst();
            }
        }
        if (!changed) return;
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM LOCAL_UPDATE_STATE WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                    new Object[]{SOURCE_BOOKMARK, sourceKey});
            db.execSQL("DELETE FROM LOCAL_UNREAD_GALLERY WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                    new Object[]{SOURCE_BOOKMARK, sourceKey});
            db.execSQL("DELETE FROM FEED_CHECKPOINT WHERE SOURCE_TYPE=? " +
                            "AND SOURCE_KEY=?",
                    new Object[]{CHECKPOINT_BOOKMARK, sourceKey});
            db.execSQL("DELETE FROM FEED_CHECKPOINT WHERE SOURCE_TYPE=? " +
                            "AND SOURCE_KEY=?",
                    new Object[]{CHECKPOINT_BOOKMARK_OPEN, sourceKey});
            db.execSQL("DELETE FROM FEED_CHECKPOINT WHERE SOURCE_TYPE='BOOKMARK_RESET' " +
                            "AND SOURCE_KEY=? AND QUERY_SIGNATURE<>?",
                    new Object[]{sourceKey, signature});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Commits one newest-first page atomically. Missing boundaries become a capped 20+ result,
     * while a first source visit only establishes a zero baseline.
     */
    public TagUpdateState commitPage(String sourceType, String sourceKey, String signature,
                                     CheckpointKey checkpointKey, List<GalleryInfo> galleries,
                                     boolean forceCapped) {
        return commitPage(sourceType, sourceKey, signature, checkpointKey, galleries,
                forceCapped, false);
    }

    public TagUpdateState commitPage(String sourceType, String sourceKey, String signature,
                                     CheckpointKey checkpointKey, List<GalleryInfo> galleries,
                                     boolean forceCapped, boolean boundaryProven) {
        if (galleries == null || galleries.isEmpty()) {
            throw new IllegalArgumentException("empty gallery page");
        }
        GlobalScanPolicy.validatePageOrder(galleries, Long.MAX_VALUE);
        return commitPrepared(sourceType, sourceKey, signature, checkpointKey,
                boundaryOf(galleries), galleries, forceCapped, boundaryProven);
    }

    /**
     * Commits an exact per-item fallback page while keeping its checkpoint aligned with the
     * shared global scan. A non-empty first page still has to prove the old item boundary; if it
     * cannot, the result remains a safe 20+ lower bound. An empty successful response proves
     * that the query currently has no matching galleries.
     */
    public TagUpdateState commitSynchronizedPage(
            String sourceType, String sourceKey, String signature,
            CheckpointKey checkpointKey, FeedBoundary sharedTop,
            List<GalleryInfo> galleries) {
        if (sharedTop == null || sharedTop.isEmpty()) {
            throw new IllegalArgumentException("empty shared scan boundary");
        }
        List<GalleryInfo> safe =
                galleries == null ? Collections.emptyList() : galleries;
        FeedBoundary commitTop = GlobalScanPolicy.fallbackCommitBoundary(
                boundaryOf(safe), sharedTop);
        return commitPrepared(sourceType, sourceKey, signature, checkpointKey, commitTop,
                safe, false, safe.isEmpty());
    }

    /**
     * Commits matches found in a shared chronological scan while advancing the item's cursor to
     * the common scan top. The match list may be empty when the old boundary was still covered.
     */
    public TagUpdateState commitGlobalScan(
            String sourceType, String sourceKey, String signature,
            CheckpointKey checkpointKey, FeedBoundary scanTop,
            List<GalleryInfo> matchingGalleries) {
        if (scanTop == null || scanTop.isEmpty()) {
            throw new IllegalArgumentException("empty global scan boundary");
        }
        return commitPrepared(sourceType, sourceKey, signature, checkpointKey, scanTop,
                matchingGalleries == null ? Collections.emptyList() : matchingGalleries,
                false, true);
    }

    TagUpdateState commitBookmarkScan(
            String sourceKey, String signature, CheckpointKey checkpointKey,
            FeedBoundary synchronizedTop, BookmarkScanResult scan) {
        if (scan == null) throw new IllegalArgumentException("null bookmark scan");
        if (scan.top.isEmpty()
                && (synchronizedTop == null || synchronizedTop.isEmpty())) {
            markCheckedWithoutChanges(SOURCE_BOOKMARK, sourceKey, signature);
            return readState(SOURCE_BOOKMARK, sourceKey, signature);
        }
        FeedBoundary commitTop = GlobalScanPolicy.fallbackCommitBoundary(
                scan.top, synchronizedTop);
        return commitPreparedGids(SOURCE_BOOKMARK, sourceKey, signature, checkpointKey,
                commitTop, scan.newGids, false, scan.boundaryProven);
    }

    private TagUpdateState commitPrepared(
            String sourceType, String sourceKey, String signature,
            CheckpointKey checkpointKey, FeedBoundary newest,
            List<GalleryInfo> matchingGalleries, boolean forceCapped,
            boolean boundaryProven) {
        FeedCheckpoint old = SubscriptionRepository.getInstance().readCheckpoint(checkpointKey);
        boolean baseline = old.current.isEmpty();
        boolean reached = GlobalScanPolicy.coversCursor(old.current, matchingGalleries, !boundaryProven);
        List<Long> newGids = collectNewGids(old.current, matchingGalleries);
        boolean capped = !baseline && (forceCapped || !reached);
        return commitDelta(sourceType, sourceKey, signature, checkpointKey,
                newest, newGids, baseline, capped,
                SOURCE_FOLLOW.equals(sourceType) && !baseline
                        ? LocalFollowUpdatesRepository.selectNewGalleries(newGids, matchingGalleries)
                        : Collections.emptyList());
    }

    private TagUpdateState commitPreparedGids(
            String sourceType, String sourceKey, String signature,
            CheckpointKey checkpointKey, FeedBoundary newest,
            Collection<Long> newGids, boolean forceCapped,
            boolean boundaryProven) {
        FeedCheckpoint old = SubscriptionRepository.getInstance().readCheckpoint(checkpointKey);
        boolean baseline = old.current.isEmpty();
        boolean capped = !baseline && (forceCapped || !boundaryProven);
        return commitDelta(sourceType, sourceKey, signature, checkpointKey,
                newest, newGids, baseline, capped, Collections.emptyList());
    }

    private TagUpdateState commitDelta(
            String sourceType, String sourceKey, String signature,
            CheckpointKey checkpointKey, FeedBoundary newest,
            Collection<Long> newGids, boolean baseline, boolean capped,
            List<GalleryInfo> newFollowGalleries) {
        Database db = EhDB.getDatabase();
        DatabaseStatement insert = null;
        db.beginTransaction();
        try {
            SubscriptionRepository.getInstance().advanceCheckpoint(db, checkpointKey, newest);
            if (!baseline && newGids != null && !newGids.isEmpty()) {
                // An open list clears by rowid. SQLite normally reuses the last deleted rowid.
                // Start above legacy rows and keep the high-water mark even if every row is read.
                long detectedAt = System.currentTimeMillis();
                // History retains every discovered gallery, independently of the 21-row counter cap.
                LocalFollowUpdatesRepository.getInstance().recordNew(
                        db, sourceKey, newFollowGalleries, detectedAt);
                long lastRowId;
                try (Cursor cursor = db.rawQuery("SELECT MAX(" +
                        "COALESCE((SELECT MAX(rowid) FROM LOCAL_UNREAD_GALLERY),0)," +
                        "COALESCE((SELECT CAST(META_VALUE AS INTEGER) FROM LOCAL_REFRESH_META " +
                        "WHERE META_KEY='unread_last_rowid'),0))", null)) {
                    cursor.moveToFirst();
                    lastRowId = Math.max(cursor.getLong(0), detectedAt);
                }
                insert = db.compileStatement("INSERT OR IGNORE INTO LOCAL_UNREAD_GALLERY" +
                        "(SOURCE_TYPE,SOURCE_KEY,GID,DETECTED_AT,rowid) VALUES(?,?,?,?,?)");
                for (Long gid : newGids) {
                    if (gid == null) continue;
                    insert.clearBindings();
                    insert.bindString(1, sourceType);
                    insert.bindString(2, sourceKey);
                    insert.bindLong(3, gid);
                    insert.bindLong(4, detectedAt);
                    insert.bindLong(5, ++lastRowId);
                    insert.executeInsert();
                }
                db.execSQL("INSERT OR REPLACE INTO LOCAL_REFRESH_META(META_KEY,META_VALUE) " +
                        "VALUES('unread_last_rowid',?)", new Object[]{lastRowId});
            }
            if (!baseline) {
                if (UnreadRetentionPolicy.maxRows(sourceType)
                        == UnreadRetentionPolicy.LOCAL_FOLLOW_LIMIT) {
                    db.execSQL("DELETE FROM LOCAL_UNREAD_GALLERY WHERE SOURCE_TYPE=? AND SOURCE_KEY=? " +
                                    "AND GID NOT IN (SELECT GID FROM LOCAL_UNREAD_GALLERY " +
                                    "WHERE SOURCE_TYPE=? AND SOURCE_KEY=? " +
                                    "ORDER BY DETECTED_AT DESC,GID DESC LIMIT 21)",
                            new Object[]{sourceType, sourceKey, sourceType, sourceKey});
                }
            }
            int count = 0;
            try (Cursor cursor = db.rawQuery(
                    "SELECT COUNT(*) FROM LOCAL_UNREAD_GALLERY WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                    new String[]{sourceType, sourceKey})) {
                if (cursor.moveToFirst()) count = cursor.getInt(0);
            }
            TagUpdateState current = readState(db, sourceType, sourceKey, signature);
            capped = capped || current.state == TagUpdateState.State.LOWER_BOUND
                    || count > TagUpdateState.DISPLAY_CAP;
            writeState(db, sourceType, sourceKey, signature,
                    baseline ? current.count : count,
                    capped ? TagUpdateState.State.LOWER_BOUND : TagUpdateState.State.EXACT, "");
            db.execSQL("UPDATE LOCAL_FOLLOW_TAG SET LAST_CHECKED_AT=? WHERE TAG_NAME=?",
                    new Object[]{System.currentTimeMillis(), sourceKey});
            db.setTransactionSuccessful();
        } finally {
            try {
                if (insert != null) insert.close();
            } finally {
                db.endTransaction();
            }
        }
        return readState(sourceType, sourceKey, signature);
    }

    static List<Long> collectNewGids(FeedBoundary oldBoundary,
                                     List<GalleryInfo> galleries) {
        if (oldBoundary == null || oldBoundary.isEmpty()
                || galleries == null || galleries.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (GalleryInfo gallery : galleries) {
            if (gallery == null || gallery.postedTimestamp <= 0) {
                throw new IllegalArgumentException("gallery timestamp unavailable");
            }
            if (gallery.postedTimestamp < oldBoundary.time) break;
            if (oldBoundary.isNewForUpdate(gallery.postedTimestamp, gallery.gid)) {
                result.add(gallery.gid);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    public void establishBaseline(String sourceType, String sourceKey, String signature,
                                  CheckpointKey checkpointKey, FeedBoundary boundary) {
        if (boundary == null || boundary.isEmpty()) return;
        Database db = EhDB.getDatabase();
        db.beginTransaction();
        try {
            SubscriptionRepository.getInstance().establishCheckpoint(db, checkpointKey, boundary);
            TagUpdateState current = readState(db, sourceType, sourceKey, signature);
            if (current.checkedAt == 0) {
                writeState(db, sourceType, sourceKey, signature, 0,
                        TagUpdateState.State.EXACT, "");
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void ensureProvisionalCheckpoint(String sourceType, String sourceKey,
                                            String signature, CheckpointKey checkpointKey,
                                            long addedAt) {
        FeedCheckpoint checkpoint = SubscriptionRepository.getInstance()
                .readCheckpoint(checkpointKey);
        if (checkpoint.current.isEmpty()) {
            establishBaseline(sourceType, sourceKey, signature, checkpointKey,
                    BaselineBoundaryPolicy.provisional(addedAt));
        }
    }

    public void markError(String sourceType, String sourceKey, String signature, String error) {
        updateCheckStatus(sourceType, sourceKey, signature, error, false);
    }

    public void markCheckedWithoutChanges(String sourceType, String sourceKey, String signature) {
        updateCheckStatus(sourceType, sourceKey, signature, "", true);
    }

    private void updateCheckStatus(String sourceType, String sourceKey, String signature,
                                   String error, boolean success) {
        Database db = EhDB.getDatabase();
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO LOCAL_UPDATE_STATE(SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE) " +
                    "VALUES(?,?,?)", new Object[]{sourceType, sourceKey, signature});
            db.execSQL("UPDATE LOCAL_UPDATE_STATE SET ERROR=?,CHECKED_AT=" +
                            "CASE WHEN ?=1 OR CHECKED_AT=0 THEN ? ELSE CHECKED_AT END " +
                            "WHERE SOURCE_TYPE=? AND SOURCE_KEY=? AND QUERY_SIGNATURE=?",
                    new Object[]{error == null ? "" : error, success ? 1 : 0,
                            System.currentTimeMillis(), sourceType, sourceKey, signature});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public static FeedBoundary boundaryOf(List<GalleryInfo> galleries) {
        if (galleries == null || galleries.isEmpty()) return FeedBoundary.EMPTY;
        long newest = galleries.get(0).postedTimestamp;
        FeedBoundary.Precision precision = galleries.get(0).postedTimestampPrecision;
        Set<Long> gids = new LinkedHashSet<>();
        for (GalleryInfo gallery : galleries) {
            if (FeedMarkerPolicy.minute(gallery.postedTimestamp) != FeedMarkerPolicy.minute(newest)) break;
            if (gallery.postedTimestamp == newest) gids.add(gallery.gid);
            if (gallery.postedTimestampPrecision.ordinal() < precision.ordinal()) {
                precision = gallery.postedTimestampPrecision;
            }
        }
        return newest <= 0 ? FeedBoundary.EMPTY : new FeedBoundary(newest, gids, precision);
    }

    public static final class ImportResult {
        public final int before;
        public final int after;
        public final boolean replaced;
        public final int added;

        ImportResult(int before, int after, boolean replaced, int added) {
            this.before = before;
            this.after = after;
            this.replaced = replaced;
            this.added = added;
        }
    }
}
