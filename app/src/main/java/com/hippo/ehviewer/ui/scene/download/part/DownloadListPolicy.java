package com.hippo.ehviewer.ui.scene.download.part;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.ArrayList;
import java.util.List;

/** Copies filtered snapshots while retaining their order and excluding removed GIDs. */
public final class DownloadListPolicy {
    public interface Availability {
        boolean isAvailable(long gid);
    }

    private DownloadListPolicy() {}

    public static <T extends GalleryInfo> List<T> retainAvailable(List<T> source,
            Availability available) {
        List<T> result = new ArrayList<>();
        if (source != null) {
            for (T info : source) {
                if (info != null && available.isAvailable(info.gid)) {
                    result.add(info);
                }
            }
        }
        return result;
    }
}
