package com.hippo.ehviewer.ui.scene.gallery.list;

import android.content.Context;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.subscription.BookmarkTagMarkerPolicy;
import com.hippo.ehviewer.util.TagTranslationUtil;

import java.util.ArrayList;
import java.util.List;

/** Shared presentation for online lists; callers supply a read-only bookmark snapshot. */
final class BookmarkTagMarkerLabels {
    private BookmarkTagMarkerLabels() {}

    static List<String> get(BookmarkTagMarkerPolicy policy, GalleryInfo gallery, Context context) {
        boolean showTranslations = Settings.getShowTagTranslations();
        EhTagDatabase tags = showTranslations && context != null
                ? EhTagDatabase.getInstance(context) : null;
        BookmarkTagMarkerPolicy.TagTranslator translator = new BookmarkTagMarkerPolicy.TagTranslator() {
            @Override public String translate(String tag) {
                return TagTranslationUtil.getTagCN(tag, tags);
            }

            @Override public String translateNamespace(String namespace) {
                return tags == null ? null : tags.getTranslation("n:" + namespace);
            }
        };
        List<String> labels = new ArrayList<>();
        for (BookmarkTagMarkerPolicy.Match match : policy.matchGallery(gallery)) {
            labels.add(match.displayLabel(showTranslations, translator));
        }
        return labels;
    }
}
