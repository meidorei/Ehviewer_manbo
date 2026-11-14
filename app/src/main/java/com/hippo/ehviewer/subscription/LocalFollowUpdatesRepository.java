package com.hippo.ehviewer.subscription;

import android.database.Cursor;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.client.data.GalleryInfo;

import org.greenrobot.greendao.database.Database;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local history of successfully discovered follow updates, independent of unread counters. */
public final class LocalFollowUpdatesRepository {
    public static final int PAGE_SIZE = 50;
    public static final int CAPACITY = 1000;
    private static final LocalFollowUpdatesRepository INSTANCE = new LocalFollowUpdatesRepository();

    static final String INSERT_SQL =
            "INSERT OR IGNORE INTO LOCAL_FOLLOW_UPDATES " +
                    "(GID,FIRST_SEEN_AT,READ_AT,GALLERY_JSON,MATCHED_TAGS_JSON) VALUES(?,?,0,?,?)";
    static final String UPDATE_SQL =
            "UPDATE LOCAL_FOLLOW_UPDATES SET GALLERY_JSON=?,MATCHED_TAGS_JSON=? WHERE GID=?";
    static final String MARK_READ_SQL =
            "UPDATE LOCAL_FOLLOW_UPDATES SET READ_AT=? WHERE GID=? AND READ_AT=0";
    static final String PRUNE_SQL =
            "DELETE FROM LOCAL_FOLLOW_UPDATES WHERE GID NOT IN " +
                    "(SELECT GID FROM LOCAL_FOLLOW_UPDATES " +
                    "ORDER BY FIRST_SEEN_AT DESC,GID DESC LIMIT ?)";
    static final String LIST_SQL =
            "SELECT GALLERY_JSON,MATCHED_TAGS_JSON,FIRST_SEEN_AT,READ_AT " +
                    "FROM LOCAL_FOLLOW_UPDATES WHERE (?='0' OR READ_AT=0) " +
                    "ORDER BY FIRST_SEEN_AT DESC,GID DESC LIMIT ? OFFSET ?";
    static final String COUNT_SQL =
            "SELECT COUNT(*) FROM LOCAL_FOLLOW_UPDATES WHERE (?='0' OR READ_AT=0)";

    private LocalFollowUpdatesRepository() {}

    public static LocalFollowUpdatesRepository getInstance() { return INSTANCE; }

    public static final class Entry {
        public final GalleryInfo gallery;
        public final List<String> matchedTags;
        public final long firstSeenAt;
        public final long readAt;

        private Entry(GalleryInfo gallery, List<String> matchedTags, long firstSeenAt, long readAt) {
            this.gallery = gallery;
            this.matchedTags = matchedTags;
            this.firstSeenAt = firstSeenAt;
            this.readAt = readAt;
        }
    }

    public List<Entry> listUpdates(boolean unreadOnly, int offset, int limit) {
        if (offset < 0 || limit < 0) throw new IllegalArgumentException("Invalid history page");
        if (limit == 0) return Collections.emptyList();
        List<Entry> entries = new ArrayList<>();
        try (Cursor cursor = EhDB.getDatabase().rawQuery(LIST_SQL, new String[]{
                unreadOnly ? "1" : "0", Integer.toString(Math.min(limit, CAPACITY)),
                Integer.toString(offset)})) {
            while (cursor.moveToNext()) {
                entries.add(new Entry(LocalFollowUpdateCodec.decodeGallery(cursor.getString(0)),
                        LocalFollowUpdateCodec.decodeTags(cursor.getString(1)),
                        cursor.getLong(2), cursor.getLong(3)));
            }
        }
        return Collections.unmodifiableList(entries);
    }

    public int countUpdates(boolean unreadOnly) {
        try (Cursor cursor = EhDB.getDatabase().rawQuery(COUNT_SQL,
                new String[]{unreadOnly ? "1" : "0"})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** Called only after gallery details have successfully been displayed. */
    public void markReadIfExists(long gid) {
        if (gid <= 0) return;
        EhDB.getDatabase().execSQL(MARK_READ_SQL, new Object[]{System.currentTimeMillis(), gid});
    }

    /** The caller owns the same transaction that commits the scan's checkpoint and counters. */
    void recordNew(Database db, String tag, List<GalleryInfo> galleries, long detectedAt) {
        if (galleries.isEmpty()) return;
        for (GalleryInfo gallery : galleries) {
            String tags = "[]";
            try (Cursor cursor = db.rawQuery(
                    "SELECT MATCHED_TAGS_JSON FROM LOCAL_FOLLOW_UPDATES WHERE GID=?",
                    new String[]{Long.toString(gallery.gid)})) {
                if (cursor.moveToFirst()) tags = cursor.getString(0);
            }
            String mergedTags = LocalFollowUpdateCodec.mergeTag(tags, tag);
            String snapshot = LocalFollowUpdateCodec.encodeGallery(gallery);
            db.execSQL(INSERT_SQL, new Object[]{gallery.gid, detectedAt, snapshot, mergedTags});
            // Never replace: a duplicate must preserve its first discovery and read state.
            db.execSQL(UPDATE_SQL, new Object[]{snapshot, mergedTags, gallery.gid});
        }
        db.execSQL(PRUNE_SQL, new Object[]{CAPACITY});
    }

    /** Uses the scan's existing delta, so baseline and same-second boundary rules stay identical. */
    static List<GalleryInfo> selectNewGalleries(Collection<Long> newGids,
                                               List<GalleryInfo> galleries) {
        if (newGids == null || newGids.isEmpty() || galleries == null || galleries.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> wanted = new LinkedHashSet<>(newGids);
        Map<Long, GalleryInfo> selected = new LinkedHashMap<>();
        for (GalleryInfo gallery : galleries) {
            if (gallery != null && wanted.contains(gallery.gid)) {
                selected.putIfAbsent(gallery.gid, gallery);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(selected.values()));
    }
}
