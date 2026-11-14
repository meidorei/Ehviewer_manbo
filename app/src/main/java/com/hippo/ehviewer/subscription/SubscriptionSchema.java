package com.hippo.ehviewer.subscription;

import android.database.sqlite.SQLiteDatabase;

import org.greenrobot.greendao.database.Database;

/** Incremental schema owned by the follow-update feature. */
public final class SubscriptionSchema {
    static final String CREATE_FOLLOW_UPDATES_TABLE =
            "CREATE TABLE IF NOT EXISTS LOCAL_FOLLOW_UPDATES (" +
                    "GID INTEGER PRIMARY KEY NOT NULL," +
                    "FIRST_SEEN_AT INTEGER NOT NULL," +
                    "READ_AT INTEGER NOT NULL DEFAULT 0," +
                    "GALLERY_JSON TEXT NOT NULL," +
                    "MATCHED_TAGS_JSON TEXT NOT NULL)";
    static final String CREATE_FOLLOW_UPDATES_INDEX =
            "CREATE INDEX IF NOT EXISTS IDX_LOCAL_FOLLOW_UPDATES_ORDER " +
                    "ON LOCAL_FOLLOW_UPDATES(FIRST_SEEN_AT DESC,GID DESC)";
    private static final String[] SQL = {
            CREATE_FOLLOW_UPDATES_TABLE,
            CREATE_FOLLOW_UPDATES_INDEX,
            "CREATE TABLE IF NOT EXISTS \"LOCAL_FOLLOW_TAG\" (" +
                    "\"TAG_NAME\" TEXT PRIMARY KEY NOT NULL," +
                    "\"ADDED_AT\" INTEGER NOT NULL," +
                    "\"LAST_CHECKED_AT\" INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS \"SUBSCRIPTION_TAG_CACHE\" (" +
                    "\"_id\" INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "\"ACCOUNT_KEY\" TEXT NOT NULL," +
                    "\"TAG_NAME\" TEXT NOT NULL," +
                    "\"SERVER_TAG_ID\" TEXT NOT NULL DEFAULT ''," +
                    "\"WATCHED\" INTEGER NOT NULL," +
                    "\"HIDDEN\" INTEGER NOT NULL," +
                    "\"COLOR\" TEXT NOT NULL DEFAULT ''," +
                    "\"WEIGHT\" INTEGER NOT NULL DEFAULT 0," +
                    "\"FIRST_SEEN_AT\" INTEGER NOT NULL," +
                    "\"SYNCED_AT\" INTEGER NOT NULL," +
                    "UNIQUE(\"ACCOUNT_KEY\",\"TAG_NAME\"))",
            "CREATE INDEX IF NOT EXISTS \"IDX_SUBSCRIPTION_TAG_ACCOUNT\" ON " +
                    "\"SUBSCRIPTION_TAG_CACHE\" (\"ACCOUNT_KEY\")",
            "CREATE TABLE IF NOT EXISTS \"FEED_CHECKPOINT\" (" +
                    "\"_id\" INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "\"ACCOUNT_KEY\" TEXT NOT NULL," +
                    "\"SOURCE_TYPE\" TEXT NOT NULL," +
                    "\"SOURCE_KEY\" TEXT NOT NULL," +
                    "\"QUERY_SIGNATURE\" TEXT NOT NULL," +
                    "\"PREVIOUS_TIME\" INTEGER NOT NULL DEFAULT 0," +
                    "\"CURRENT_TIME\" INTEGER NOT NULL DEFAULT 0," +
                    "\"PREVIOUS_GIDS\" TEXT NOT NULL DEFAULT ''," +
                    "\"CURRENT_GIDS\" TEXT NOT NULL DEFAULT ''," +
                    "\"UPDATED_AT\" INTEGER NOT NULL," +
                    "UNIQUE(\"ACCOUNT_KEY\",\"SOURCE_TYPE\",\"SOURCE_KEY\",\"QUERY_SIGNATURE\"))",
            "CREATE TABLE IF NOT EXISTS \"SUBSCRIPTION_TAG_UPDATE_STATE\" (" +
                    "\"_id\" INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "\"ACCOUNT_KEY\" TEXT NOT NULL," +
                    "\"TAG_NAME\" TEXT NOT NULL," +
                    "\"QUERY_SIGNATURE\" TEXT NOT NULL," +
                    "\"COUNT\" INTEGER NOT NULL DEFAULT 0," +
                    "\"COUNT_STATE\" TEXT NOT NULL," +
                    "\"CHECKED_AT\" INTEGER NOT NULL," +
                    "UNIQUE(\"ACCOUNT_KEY\",\"TAG_NAME\",\"QUERY_SIGNATURE\"))",
            "CREATE TABLE IF NOT EXISTS \"LOCAL_UPDATE_STATE\" (" +
                    "\"SOURCE_TYPE\" TEXT NOT NULL," +
                    "\"SOURCE_KEY\" TEXT NOT NULL," +
                    "\"QUERY_SIGNATURE\" TEXT NOT NULL," +
                    "\"COUNT\" INTEGER NOT NULL DEFAULT 0," +
                    "\"COUNT_STATE\" TEXT NOT NULL DEFAULT 'EXACT'," +
                    "\"CHECKED_AT\" INTEGER NOT NULL DEFAULT 0," +
                    "\"ERROR\" TEXT NOT NULL DEFAULT ''," +
                    "PRIMARY KEY(\"SOURCE_TYPE\",\"SOURCE_KEY\",\"QUERY_SIGNATURE\"))",
            "CREATE TABLE IF NOT EXISTS \"LOCAL_UNREAD_GALLERY\" (" +
                    "\"SOURCE_TYPE\" TEXT NOT NULL," +
                    "\"SOURCE_KEY\" TEXT NOT NULL," +
                    "\"GID\" INTEGER NOT NULL," +
                    "\"DETECTED_AT\" INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY(\"SOURCE_TYPE\",\"SOURCE_KEY\",\"GID\"))",
            "CREATE TABLE IF NOT EXISTS \"LOCAL_GLOBAL_CURSOR\" (" +
                    "\"ACCOUNT_KEY\" TEXT NOT NULL," +
                    "\"JOB_TYPE\" TEXT NOT NULL," +
                    "\"QUERY_SIGNATURE\" TEXT NOT NULL," +
                    "\"CURRENT_TIME\" INTEGER NOT NULL DEFAULT 0," +
                    "\"CURRENT_GIDS\" TEXT NOT NULL DEFAULT ''," +
                    "\"UPDATED_AT\" INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY(\"ACCOUNT_KEY\",\"JOB_TYPE\",\"QUERY_SIGNATURE\"))",
            "CREATE TABLE IF NOT EXISTS \"LOCAL_REFRESH_JOB\" (" +
                    "\"_id\" INTEGER PRIMARY KEY CHECK(\"_id\"=1)," +
                    "\"JOB_TYPE\" TEXT NOT NULL," +
                    "\"METHOD\" TEXT NOT NULL," +
                    "\"STATUS\" TEXT NOT NULL," +
                    "\"PHASE\" TEXT NOT NULL DEFAULT 'PREPARING'," +
                    "\"REQUEST_INTERVAL_MS\" INTEGER NOT NULL DEFAULT 3200," +
                    "\"CURRENT_INDEX\" INTEGER NOT NULL DEFAULT 0," +
                    "\"TOTAL\" INTEGER NOT NULL DEFAULT 0," +
                    "\"PAGES\" INTEGER NOT NULL DEFAULT 0," +
                    "\"GALLERIES\" INTEGER NOT NULL DEFAULT 0," +
                    "\"CURRENT_KEY\" TEXT NOT NULL DEFAULT ''," +
                    "\"SOURCE_HOST\" TEXT NOT NULL DEFAULT ''," +
                    "\"FAILURES\" TEXT NOT NULL DEFAULT ''," +
                    "\"STARTED_AT\" INTEGER NOT NULL DEFAULT 0," +
                    "\"UPDATED_AT\" INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS \"LOCAL_REFRESH_META\" (" +
                    "\"META_KEY\" TEXT PRIMARY KEY NOT NULL," +
                    "\"META_VALUE\" TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS \"LOCAL_BASELINE_QUEUE\" (" +
                    "\"_id\" INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "\"BATCH_KEY\" TEXT NOT NULL," +
                    "\"SOURCE_TYPE\" TEXT NOT NULL," +
                    "\"SOURCE_KEY\" TEXT NOT NULL," +
                    "\"QUERY_SIGNATURE\" TEXT NOT NULL," +
                    "\"METHOD\" TEXT NOT NULL," +
                    "\"ADDED_AT\" INTEGER NOT NULL," +
                    "\"STATUS\" TEXT NOT NULL DEFAULT 'PENDING'," +
                    "\"ERROR\" TEXT NOT NULL DEFAULT ''," +
                    "UNIQUE(\"SOURCE_TYPE\",\"SOURCE_KEY\"))",
            "CREATE INDEX IF NOT EXISTS \"IDX_LOCAL_BASELINE_PENDING\" ON " +
                    "\"LOCAL_BASELINE_QUEUE\" (\"STATUS\",\"_id\")"
    };

    private static final String[] DROP_SQL = {
            "DROP TABLE IF EXISTS LOCAL_FOLLOW_UPDATES",
            "DROP TABLE IF EXISTS \"LOCAL_BASELINE_QUEUE\"",
            "DROP TABLE IF EXISTS \"LOCAL_REFRESH_META\"",
            "DROP TABLE IF EXISTS \"LOCAL_REFRESH_JOB\"",
            "DROP TABLE IF EXISTS \"LOCAL_GLOBAL_CURSOR\"",
            "DROP TABLE IF EXISTS \"LOCAL_UNREAD_GALLERY\"",
            "DROP TABLE IF EXISTS \"LOCAL_UPDATE_STATE\"",
            "DROP TABLE IF EXISTS \"SUBSCRIPTION_TAG_UPDATE_STATE\"",
            "DROP TABLE IF EXISTS \"FEED_CHECKPOINT\"",
            "DROP TABLE IF EXISTS \"SUBSCRIPTION_TAG_CACHE\"",
            "DROP TABLE IF EXISTS \"LOCAL_FOLLOW_TAG\""
    };

    private SubscriptionSchema() {}

    public static void createTables(Database db) {
        for (String sql : SQL) db.execSQL(sql);
    }

    public static void createTables(SQLiteDatabase db) {
        for (String sql : SQL) db.execSQL(sql);
    }

    public static void upgradeToV11(SQLiteDatabase db) {
        createTables(db);
        if (!hasColumn(db, "LOCAL_UNREAD_GALLERY", "DETECTED_AT")) {
            db.execSQL("ALTER TABLE \"LOCAL_UNREAD_GALLERY\" ADD COLUMN " +
                    "\"DETECTED_AT\" INTEGER NOT NULL DEFAULT 0");
        }
        if (!hasColumn(db, "LOCAL_REFRESH_JOB", "PHASE")) {
            db.execSQL("ALTER TABLE \"LOCAL_REFRESH_JOB\" ADD COLUMN " +
                    "\"PHASE\" TEXT NOT NULL DEFAULT 'PREPARING'");
        }
        if (!hasColumn(db, "LOCAL_REFRESH_JOB", "REQUEST_INTERVAL_MS")) {
            db.execSQL("ALTER TABLE \"LOCAL_REFRESH_JOB\" ADD COLUMN " +
                    "\"REQUEST_INTERVAL_MS\" INTEGER NOT NULL DEFAULT 3200");
        }
    }

    public static void upgradeToV15(SQLiteDatabase db) {
        // History starts empty: legacy unread rows have neither tokens nor gallery snapshots.
        db.execSQL(CREATE_FOLLOW_UPDATES_TABLE);
        db.execSQL(CREATE_FOLLOW_UPDATES_INDEX);
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (android.database.Cursor cursor = db.rawQuery(
                "PRAGMA table_info(\"" + table + "\")", null)) {
            int nameIndex = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                if (column.equalsIgnoreCase(cursor.getString(nameIndex))) return true;
            }
        }
        return false;
    }

    public static void dropTables(Database db) {
        for (String sql : DROP_SQL) db.execSQL(sql);
    }
}
