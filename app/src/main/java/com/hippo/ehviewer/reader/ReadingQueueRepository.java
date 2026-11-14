package com.hippo.ehviewer.reader;

import android.database.Cursor;

import com.hippo.ehviewer.EhDB;

import org.greenrobot.greendao.database.Database;

import java.util.ArrayList;
import java.util.List;

/** Durable, ordered GID storage for locally read downloads. */
public final class ReadingQueueRepository {
    private static final ReadingQueueRepository INSTANCE = new ReadingQueueRepository();

    private ReadingQueueRepository() {}

    public static ReadingQueueRepository getInstance() {
        return INSTANCE;
    }

    public synchronized void markRead(long gid, int currentPage, int totalPages) {
        ReadingQueueSnapshot.Progress progress =
                ReadingQueueSnapshot.normalizeProgress(currentPage, totalPages);
        Database db = EhDB.getDatabase();
        db.beginTransaction();
        try {
            long nextOrder = 1L;
            try (Cursor cursor = db.rawQuery(
                    "SELECT COALESCE(MAX(QUEUE_ORDER),0) FROM READING_QUEUE", null)) {
                if (cursor.moveToFirst()) {
                    long current = cursor.getLong(0);
                    if (current == Long.MAX_VALUE) {
                        compactOrder(db);
                        current = count(db);
                    }
                    nextOrder = current + 1L;
                }
            }
            db.execSQL("INSERT OR REPLACE INTO READING_QUEUE(" +
                            "GID,QUEUE_ORDER,CURRENT_PAGE,TOTAL_PAGES) VALUES(?,?,?,?)",
                    new Object[]{gid, nextOrder, progress.currentPage, progress.totalPages});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void updateProgress(long gid, int currentPage, int totalPages) {
        ReadingQueueSnapshot.Progress progress =
                ReadingQueueSnapshot.normalizeProgress(currentPage, totalPages);
        EhDB.getDatabase().execSQL("UPDATE READING_QUEUE SET CURRENT_PAGE=?,TOTAL_PAGES=? " +
                        "WHERE GID=?",
                new Object[]{progress.currentPage, progress.totalPages, gid});
    }

    public synchronized ReadingQueueSnapshot getSnapshot() {
        List<ReadingQueueSnapshot.Entry> entries = new ArrayList<>();
        try (Cursor cursor = EhDB.getDatabase().rawQuery(
                "SELECT GID,CURRENT_PAGE,TOTAL_PAGES FROM READING_QUEUE " +
                        "ORDER BY QUEUE_ORDER DESC", null)) {
            while (cursor.moveToNext()) {
                entries.add(new ReadingQueueSnapshot.Entry(cursor.getLong(0),
                        cursor.getInt(1), cursor.getInt(2)));
            }
        }
        return ReadingQueueSnapshot.from(entries);
    }

    public synchronized List<Long> getNewestFirst() {
        List<Long> result = new ArrayList<>();
        try (Cursor cursor = EhDB.getDatabase().rawQuery(
                "SELECT GID FROM READING_QUEUE ORDER BY QUEUE_ORDER DESC", null)) {
            while (cursor.moveToNext()) {
                result.add(cursor.getLong(0));
            }
        }
        return result;
    }

    public synchronized int getCount() {
        return count(EhDB.getDatabase());
    }

    public synchronized void remove(long gid) {
        EhDB.getDatabase().execSQL("DELETE FROM READING_QUEUE WHERE GID=?",
                new Object[]{gid});
    }

    public synchronized boolean isOverflowCandidate(long gid, int capacity) {
        List<Long> candidates = ReadingQueuePolicy.oldestOverflow(getNewestFirst(), capacity);
        return candidates.contains(gid);
    }

    private static int count(Database db) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM READING_QUEUE", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private static void compactOrder(Database db) {
        List<Long> oldestFirst = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
                "SELECT GID FROM READING_QUEUE ORDER BY QUEUE_ORDER ASC", null)) {
            while (cursor.moveToNext()) {
                oldestFirst.add(cursor.getLong(0));
            }
        }
        long order = 1L;
        for (Long gid : oldestFirst) {
            db.execSQL("UPDATE READING_QUEUE SET QUEUE_ORDER=? WHERE GID=?",
                    new Object[]{order++, gid});
        }
    }
}
