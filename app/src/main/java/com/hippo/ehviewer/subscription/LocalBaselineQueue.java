package com.hippo.ehviewer.subscription;

import android.database.Cursor;

import com.hippo.ehviewer.EhDB;

import org.greenrobot.greendao.database.Database;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Durable queue for automatic follow and bookmark baseline refinement. */
public final class LocalBaselineQueue {
    public static final String METHOD_FOLLOW_GLOBAL = "FOLLOW_GLOBAL";
    public static final String METHOD_FOLLOW_TAG = "FOLLOW_TAG";
    public static final String METHOD_BOOKMARK = "BOOKMARK";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FALLBACK = "FALLBACK";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private LocalBaselineQueue() {}

    public static void enqueue(String batchKey, String sourceType, String sourceKey,
                               String signature, String method, long addedAt) {
        Database db = EhDB.getDatabase();
        db.execSQL("DELETE FROM LOCAL_BASELINE_QUEUE WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                new Object[]{sourceType, sourceKey});
        db.execSQL("INSERT INTO LOCAL_BASELINE_QUEUE" +
                        "(BATCH_KEY,SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE,METHOD,ADDED_AT," +
                        "STATUS,ERROR) VALUES(?,?,?,?,?,?,'PENDING','')",
                new Object[]{batchKey, sourceType, sourceKey, signature, method, addedAt});
    }

    public static List<Item> pending() {
        List<Item> result = new ArrayList<>();
        try (Cursor cursor = EhDB.getDatabase().rawQuery(
                "SELECT _id,BATCH_KEY,SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE,METHOD," +
                        "ADDED_AT,STATUS,ERROR FROM LOCAL_BASELINE_QUEUE " +
                        "WHERE STATUS IN ('PENDING','RUNNING') ORDER BY _id", null)) {
            while (cursor.moveToNext()) {
                result.add(new Item(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4),
                        cursor.getString(5), cursor.getLong(6), cursor.getString(7),
                        cursor.getString(8)));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static boolean hasPending() {
        try (Cursor cursor = EhDB.getDatabase().rawQuery(
                "SELECT 1 FROM LOCAL_BASELINE_QUEUE " +
                        "WHERE STATUS IN ('PENDING','RUNNING') LIMIT 1", null)) {
            return cursor.moveToFirst();
        }
    }

    public static void markRunning(List<Item> items) {
        for (Item item : items) update(item.id, STATUS_RUNNING, "");
    }

    public static void markDone(List<Item> items) {
        for (Item item : items) update(item.id, STATUS_DONE, "");
    }

    public static void markFallback(List<Item> items, String error) {
        for (Item item : items) update(item.id, STATUS_FALLBACK, error);
    }

    public static void resetRunning() {
        EhDB.getDatabase().execSQL(
                "UPDATE LOCAL_BASELINE_QUEUE SET STATUS='PENDING' WHERE STATUS='RUNNING'");
    }

    public static void cancelOutstanding() {
        EhDB.getDatabase().execSQL(
                "UPDATE LOCAL_BASELINE_QUEUE SET STATUS='CANCELLED' " +
                        "WHERE STATUS IN ('PENDING','RUNNING')");
    }

    public static void delete(String sourceType, String sourceKey) {
        EhDB.getDatabase().execSQL(
                "DELETE FROM LOCAL_BASELINE_QUEUE WHERE SOURCE_TYPE=? AND SOURCE_KEY=?",
                new Object[]{sourceType, sourceKey});
    }

    private static void update(long id, String status, String error) {
        EhDB.getDatabase().execSQL(
                "UPDATE LOCAL_BASELINE_QUEUE SET STATUS=?,ERROR=? WHERE _id=?",
                new Object[]{status, error == null ? "" : error, id});
    }

    public static final class Item {
        public final long id;
        public final String batchKey;
        public final String sourceType;
        public final String sourceKey;
        public final String signature;
        public final String method;
        public final long addedAt;
        public final String status;
        public final String error;

        Item(long id, String batchKey, String sourceType, String sourceKey,
             String signature, String method, long addedAt, String status, String error) {
            this.id = id;
            this.batchKey = batchKey;
            this.sourceType = sourceType;
            this.sourceKey = sourceKey;
            this.signature = signature;
            this.method = method;
            this.addedAt = addedAt;
            this.status = status;
            this.error = error;
        }
    }
}
