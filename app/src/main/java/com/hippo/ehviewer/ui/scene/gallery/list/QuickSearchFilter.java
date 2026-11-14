package com.hippo.ehviewer.ui.scene.gallery.list;

import com.hippo.ehviewer.dao.QuickSearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class QuickSearchFilter {
    private QuickSearchFilter() {}

    static List<QuickSearch> filter(List<QuickSearch> source, String query) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        String needle = normalize(query);
        if (needle.isEmpty()) return new ArrayList<>(source);

        List<QuickSearch> result = new ArrayList<>();
        for (QuickSearch item : source) {
            if (item == null) continue;
            if (normalize(item.getName()).contains(needle)
                    || normalize(item.getKeyword()).contains(needle)) {
                result.add(item);
            }
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
