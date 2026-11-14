package com.hippo.ehviewer.reader;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.greenrobot.greendao.database.Database;

/** Additive schema owned by the local reading queue. */
public final class ReadingQueueSchema {
    public static final String TABLE = "READING_QUEUE";
    public static final String COLUMN_CURRENT_PAGE = "CURRENT_PAGE";
    public static final String COLUMN_TOTAL_PAGES = "TOTAL_PAGES";

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS \"READING_QUEUE\" (" +
                    "\"GID\" INTEGER PRIMARY KEY NOT NULL," +
                    "\"QUEUE_ORDER\" INTEGER NOT NULL," +
                    "\"CURRENT_PAGE\" INTEGER NOT NULL DEFAULT 0," +
                    "\"TOTAL_PAGES\" INTEGER NOT NULL DEFAULT 0)";
    private static final String CREATE_ORDER_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS \"IDX_READING_QUEUE_ORDER\" ON " +
                    "\"READING_QUEUE\" (\"QUEUE_ORDER\")";

    private ReadingQueueSchema() {}

    public static void createTables(Database db) {
        db.execSQL(CREATE_TABLE);
        db.execSQL(CREATE_ORDER_INDEX);
    }

    public static void createTables(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE);
        db.execSQL(CREATE_ORDER_INDEX);
    }

    public static void upgradeToV12(SQLiteDatabase db) {
        createTables(db);
    }

    public static void upgradeToV13(SQLiteDatabase db) {
        createTables(db);
        addColumnIfMissing(db, COLUMN_CURRENT_PAGE,
                "ALTER TABLE \"READING_QUEUE\" ADD COLUMN \"CURRENT_PAGE\" " +
                        "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, COLUMN_TOTAL_PAGES,
                "ALTER TABLE \"READING_QUEUE\" ADD COLUMN \"TOTAL_PAGES\" " +
                        "INTEGER NOT NULL DEFAULT 0");
    }

    private static void addColumnIfMissing(SQLiteDatabase db, String column, String sql) {
        if (!hasColumn(db, column)) {
            db.execSQL(sql);
        }
    }

    private static boolean hasColumn(SQLiteDatabase db, String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(\"READING_QUEUE\")", null)) {
            int nameIndex = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && column.equalsIgnoreCase(cursor.getString(nameIndex))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void dropTables(Database db) {
        db.execSQL("DROP TABLE IF EXISTS \"READING_QUEUE\"");
    }
}
