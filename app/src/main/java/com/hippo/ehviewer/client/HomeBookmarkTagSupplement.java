package com.hippo.ehviewer.client;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.exception.CancelledException;

import java.util.ArrayList;
import java.util.List;

/** Optional online-list metadata: failed enrichment must not discard a usable HTML list. */
final class HomeBookmarkTagSupplement {
    interface Loader {
        void load(List<GalleryInfo> missing) throws Throwable;
    }

    private HomeBookmarkTagSupplement() {}

    static boolean fillMissing(boolean enabled, List<GalleryInfo> galleries, Loader loader)
            throws Throwable {
        if (!enabled) return true;
        List<GalleryInfo> originals = new ArrayList<>();
        List<GalleryInfo> requests = new ArrayList<>();
        for (GalleryInfo gallery : galleries) {
            // An empty array is a complete API response, not missing metadata.
            if (gallery.simpleTags != null) continue;
            originals.add(gallery);
            GalleryInfo request = new GalleryInfo();
            request.gid = gallery.gid;
            request.token = gallery.token;
            requests.add(request);
        }
        if (requests.isEmpty()) return true;
        try {
            loader.load(requests);
        } catch (CancelledException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            // API parsing may partially mutate its input. Work on copies to preserve HTML data.
            return false;
        }
        boolean complete = true;
        for (int i = 0; i < originals.size(); i++) {
            String[] tags = requests.get(i).simpleTags;
            if (tags == null) {
                complete = false;
            } else {
                originals.get(i).simpleTags = tags.clone();
            }
        }
        return complete;
    }
}
