package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@Ignore("Legacy Robolectric 4.2 cannot run on the project's Java 21 toolchain; requires an instrumented v7 fixture")
public class SubscriptionSchemaTest {
    private SQLiteDatabase db;

    @Before public void setUp() { db = SQLiteDatabase.create(null); }
    @After public void tearDown() { db.close(); }

    @Test public void additiveMigrationPreservesExistingRowsAndIsIdempotent() {
        db.execSQL("CREATE TABLE QUICK_SEARCH (_id INTEGER PRIMARY KEY, NAME TEXT)");
        db.execSQL("INSERT INTO QUICK_SEARCH VALUES (7, 'kept')");
        SubscriptionSchema.createTables(db);
        SubscriptionSchema.createTables(db);
        try (Cursor cursor = db.rawQuery("SELECT NAME FROM QUICK_SEARCH WHERE _id=7", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("kept", cursor.getString(0));
        }
    }

    @Test(expected = android.database.sqlite.SQLiteConstraintException.class)
    public void checkpointCompositeKeyIsUnique() {
        SubscriptionSchema.createTables(db);
        String sql = "INSERT INTO FEED_CHECKPOINT " +
                "(ACCOUNT_KEY,SOURCE_TYPE,SOURCE_KEY,QUERY_SIGNATURE,UPDATED_AT) VALUES ('a','HOME','home','q',1)";
        db.execSQL(sql);
        db.execSQL(sql);
    }
}
