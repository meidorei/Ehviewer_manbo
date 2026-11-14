package com.hippo.ehviewer.dao;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/** Additive migration shared by app upgrades and imported database upgrades. */
public final class GalleryTagsSchema {
    private GalleryTagsSchema() {}

    public static void upgradeToV14(SQLiteDatabase db) {
        // Upstream schema 8 backups may already contain LOCATION.
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(\"Gallery_Tags\")", null)) {
            int nameIndex = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) {
                if ("LOCATION".equalsIgnoreCase(cursor.getString(nameIndex))) {
                    return;
                }
            }
        }
        db.execSQL("ALTER TABLE \"Gallery_Tags\" ADD COLUMN \"LOCATION\" TEXT");
    }
}
