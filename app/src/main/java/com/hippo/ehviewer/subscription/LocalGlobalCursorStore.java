package com.hippo.ehviewer.subscription;

import android.content.Context;
import android.database.Cursor;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.client.EhCookieStore;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.IgneousUtils;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Durable newest-first cursor shared by one update type within an account/site source. */
public final class LocalGlobalCursorStore {
    public static final String TYPE_FOLLOW = "FOLLOW";
    public static final String TYPE_BOOKMARK = "BOOKMARK";
    static final String READ_SQL =
            "SELECT \"CURRENT_TIME\",\"CURRENT_GIDS\" FROM \"LOCAL_GLOBAL_CURSOR\" " +
                    "WHERE \"ACCOUNT_KEY\"=? AND \"JOB_TYPE\"=? AND \"QUERY_SIGNATURE\"=?";

    private LocalGlobalCursorStore() {}

    public static FeedBoundary read(String accountKey, String jobType, String signature) {
        try (Cursor cursor = EhDB.getDatabase().rawQuery(
                READ_SQL,
                new String[]{accountKey, jobType, signature})) {
            if (!cursor.moveToFirst()) return FeedBoundary.EMPTY;
            return FeedBoundaryCodec.decode(cursor.getLong(0), cursor.getString(1));
        }
    }

    /** Reads the cursor that a new global update will use for the current account and site. */
    public static FeedBoundary readCurrent(Context context, String jobType) {
        EhCookieStore store = EhApplication.getEhCookieStore(context);
        String host = IgneousUtils.isUsableIgneous(store.getIgneous())
                ? EhUrl.HOST_EX : EhUrl.HOST_E;
        String source = SubscriptionRepository.getInstance().getAccountKey() + "|" + host;
        return read(source, jobType, LocalFollowRepository.FIXED_CHINESE_SIGNATURE);
    }

    public static void write(String accountKey, String jobType, String signature,
                             FeedBoundary boundary) {
        if (boundary == null || boundary.isEmpty()) return;
        EhDB.getDatabase().execSQL(
                "INSERT OR REPLACE INTO \"LOCAL_GLOBAL_CURSOR\"" +
                        "(\"ACCOUNT_KEY\",\"JOB_TYPE\",\"QUERY_SIGNATURE\",\"CURRENT_TIME\"," +
                        "\"CURRENT_GIDS\",\"UPDATED_AT\") " +
                        "VALUES(?,?,?,?,?,?)",
                new Object[]{accountKey, jobType, signature, boundary.time,
                        FeedBoundaryCodec.encode(boundary),
                        System.currentTimeMillis()});
    }

    /** Uses the oldest existing item cursor for the one-time v10-to-v11 transition. */
    public static FeedBoundary oldest(Map<?, FeedCheckpoint> checkpoints) {
        long oldest = Long.MAX_VALUE;
        FeedBoundary.Precision precision = FeedBoundary.Precision.SECOND;
        Set<Long> gids = new LinkedHashSet<>();
        for (FeedCheckpoint checkpoint : checkpoints.values()) {
            if (checkpoint == null || checkpoint.current.isEmpty()) continue;
            if (checkpoint.current.time < oldest) {
                oldest = checkpoint.current.time;
                gids.clear();
                gids.addAll(checkpoint.current.gids);
                precision = checkpoint.current.precision;
            } else if (checkpoint.current.time == oldest) {
                gids.addAll(checkpoint.current.gids);
                if (checkpoint.current.precision.ordinal() < precision.ordinal()) {
                    precision = checkpoint.current.precision;
                }
            }
        }
        return oldest == Long.MAX_VALUE ? FeedBoundary.EMPTY : new FeedBoundary(oldest, gids, precision);
    }

    public static void deleteForItemRemoval(String jobType) {
        EhDB.getDatabase().execSQL(
                "DELETE FROM LOCAL_GLOBAL_CURSOR WHERE JOB_TYPE=?",
                new Object[]{jobType});
    }
}
