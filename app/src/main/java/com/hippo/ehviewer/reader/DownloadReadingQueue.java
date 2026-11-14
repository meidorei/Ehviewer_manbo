package com.hippo.ehviewer.reader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

public final class DownloadReadingQueue {

    public interface Availability {
        boolean isAvailable(long gid) throws Exception;
    }

    public interface CandidateLookup {
        DownloadInfo get(long gid);
    }

    private DownloadReadingQueue() {
    }

    @NonNull
    public static long[] snapshot(@Nullable List<? extends GalleryInfo> list) {
        if (list == null || list.isEmpty()) {
            return new long[0];
        }
        long[] gids = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            gids[i] = list.get(i).gid;
        }
        return gids;
    }

    /** Call on the manager's owning thread; workers receive detached file-check metadata. */
    public static Map<Long, DownloadInfo> snapshotCandidates(long[] gids, int afterIndex,
            CandidateLookup lookup) {
        Map<Long, DownloadInfo> result = new HashMap<>();
        if (gids == null) {
            return result;
        }
        for (int i = Math.max(0, afterIndex + 1); i < gids.length; i++) {
            DownloadInfo info = lookup.get(gids[i]);
            if (info != null) {
                DownloadInfo copy = new DownloadInfo(info);
                copy.archiveUri = info.archiveUri;
                result.put(gids[i], copy);
            }
        }
        return result;
    }

    public static int indexOf(@Nullable long[] gids, long gid) {
        if (gids == null) {
            return -1;
        }
        for (int i = 0; i < gids.length; i++) {
            if (gids[i] == gid) {
                return i;
            }
        }
        return -1;
    }

    public static int nextIndex(@Nullable long[] gids, int currentIndex) {
        if (gids == null || currentIndex < -1 || currentIndex + 1 >= gids.length) {
            return -1;
        }
        return currentIndex + 1;
    }

    public static int findNextAvailableIndex(@Nullable long[] gids, int currentIndex,
            @NonNull Availability availability) {
        for (int i = nextIndex(gids, currentIndex);
                gids != null && i >= 0 && i < gids.length;
                i++) {
            try {
                if (availability.isAvailable(gids[i])) {
                    return i;
                }
            } catch (Exception ignored) {
            }
        }
        return -1;
    }
}
