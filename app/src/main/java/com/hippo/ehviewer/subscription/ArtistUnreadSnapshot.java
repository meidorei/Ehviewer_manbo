package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The artist's unread batch as it was when the list was opened. */
public record ArtistUnreadSnapshot(long lastRowId, Set<Long> gids) {
    public ArtistUnreadSnapshot {
        gids = Collections.unmodifiableSet(new LinkedHashSet<>(gids));
    }

    public int lastMatch(List<GalleryInfo> galleries) {
        if (!gids.isEmpty()) {
            for (int i = galleries.size() - 1; i >= 0; i--) {
                if (gids.contains(galleries.get(i).gid)) return i;
            }
        }
        return -1;
    }
}
