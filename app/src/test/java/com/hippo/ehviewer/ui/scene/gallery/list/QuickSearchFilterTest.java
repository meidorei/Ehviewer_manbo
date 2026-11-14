package com.hippo.ehviewer.ui.scene.gallery.list;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.dao.QuickSearch;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class QuickSearchFilterTest {
    private final QuickSearch first = item(1L, "Favorites", "artist:Alpha");
    private final QuickSearch second = item(2L, "中文标签", "female:glasses");
    private final QuickSearch third = item(3L, "Later", "uploader:Example");
    private final List<QuickSearch> source = Arrays.asList(first, second, third);

    @Test public void emptyQueryReturnsStableOriginalOrder() {
        List<QuickSearch> result = QuickSearchFilter.filter(source, "  ");
        assertEquals(Arrays.asList(first, second, third), result);
    }

    @Test public void matchesDisplayNameIgnoringCase() {
        assertEquals(Arrays.asList(first), QuickSearchFilter.filter(source, "FAVOR"));
    }

    @Test public void matchesSavedKeywordIgnoringCase() {
        assertEquals(Arrays.asList(second), QuickSearchFilter.filter(source, "GLASSES"));
        assertEquals(Arrays.asList(third), QuickSearchFilter.filter(source, "example"));
    }

    @Test public void noMatchReturnsEmptyList() {
        assertTrue(QuickSearchFilter.filter(source, "missing").isEmpty());
    }

    @Test public void matchingSeveralItemsKeepsSourceOrder() {
        List<QuickSearch> result = QuickSearchFilter.filter(source, "a");
        assertEquals(Arrays.asList(first, second, third), result);
    }

    private static QuickSearch item(long id, String name, String keyword) {
        QuickSearch item = new QuickSearch(id);
        item.setName(name);
        item.setKeyword(keyword);
        return item;
    }
}
