package com.hippo.ehviewer.client.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.hippo.ehviewer.widget.AdvanceSearchTable;

import org.junit.Test;

public class ListUrlBuilderChineseFilterTest {

    @Test
    public void disabledFilterKeepsStoredKeywordsForEveryMode() {
        for (int mode : allModes()) {
            ListUrlBuilder builder = builder(mode, "female:glasses");
            assertEquals("female:glasses", builder.getEffectiveKeyword(false));
        }
    }

    @Test
    public void enabledFilterAppliesToHomepageAndNormalSearch() {
        ListUrlBuilder homepage = builder(ListUrlBuilder.MODE_NORMAL, null);
        assertEquals("l:chinese", homepage.getEffectiveKeyword(true));

        ListUrlBuilder search = builder(ListUrlBuilder.MODE_NORMAL, "artist:foo");
        assertEquals("artist:foo l:chinese", search.getEffectiveKeyword(true));
        assertEquals("artist:foo", search.getKeyword());
    }

    @Test
    public void enabledFilterAppliesToSubscriptionAndFilterModes() {
        ListUrlBuilder subscription = builder(ListUrlBuilder.MODE_SUBSCRIPTION, "parody:bar");
        assertEquals("parody:bar l:chinese", subscription.getEffectiveKeyword(true));

        ListUrlBuilder filter = builder(ListUrlBuilder.MODE_FILTER,
                "female:glasses male:glasses");
        assertEquals("female:glasses male:glasses l:chinese",
                filter.getEffectiveKeyword(true));
    }

    @Test
    public void advancedSearchEnablesTagMatchingWithoutChangingStoredOptions() {
        ListUrlBuilder builder = builder(ListUrlBuilder.MODE_NORMAL, "title:foo");
        builder.setAdvanceSearch(AdvanceSearchTable.SNAME);

        assertEquals(AdvanceSearchTable.SNAME, builder.getEffectiveAdvanceSearch(false));
        assertEquals(AdvanceSearchTable.SNAME | AdvanceSearchTable.STAGS,
                builder.getEffectiveAdvanceSearch(true));
        assertEquals(AdvanceSearchTable.SNAME, builder.getAdvanceSearch());
    }

    @Test
    public void tagAndUploaderBecomeComposableSearches() {
        ListUrlBuilder tag = builder(ListUrlBuilder.MODE_TAG, "female:big breasts");
        assertEquals("female:\"big breasts$\" l:chinese", tag.getEffectiveKeyword(true));
        assertEquals(ListUrlBuilder.MODE_TAG, tag.getMode());
        assertEquals("female:big breasts", tag.getKeyword());

        ListUrlBuilder uploader = builder(ListUrlBuilder.MODE_UPLOADER, "Uploader Name");
        assertEquals("uploader:\"Uploader Name\" l:chinese",
                uploader.getEffectiveKeyword(true));
        assertEquals(ListUrlBuilder.MODE_UPLOADER, uploader.getMode());
    }

    @Test
    public void explicitPositiveLanguageIsRespected() {
        String[] keywords = {
                "artist:foo l:japanese",
                "artist:foo LANG:english",
                "artist:foo language:korean",
                "artist:foo ~l:chinese"
        };
        for (String keyword : keywords) {
            ListUrlBuilder builder = builder(ListUrlBuilder.MODE_NORMAL, keyword);
            assertEquals(keyword, builder.getEffectiveKeyword(true));
        }
    }

    @Test
    public void negativeLanguageDoesNotPreventChineseFilter() {
        ListUrlBuilder builder = builder(ListUrlBuilder.MODE_NORMAL,
                "-l:japanese artist:foo");
        assertEquals("-l:japanese artist:foo l:chinese",
                builder.getEffectiveKeyword(true));
    }

    @Test
    public void unsupportedModesIgnoreEnabledFilter() {
        int[] modes = {
                ListUrlBuilder.MODE_WHATS_HOT,
                ListUrlBuilder.MODE_IMAGE_SEARCH,
                ListUrlBuilder.MODE_TOP_LIST
        };
        for (int mode : modes) {
            ListUrlBuilder withKeyword = builder(mode, "artist:foo");
            assertEquals("artist:foo", withKeyword.getEffectiveKeyword(true));
            assertFalse(withKeyword.supportsChineseFilter());

            ListUrlBuilder withoutKeyword = builder(mode, null);
            assertNull(withoutKeyword.getEffectiveKeyword(true));
        }
    }

    private static int[] allModes() {
        return new int[] {
                ListUrlBuilder.MODE_NORMAL,
                ListUrlBuilder.MODE_SUBSCRIPTION,
                ListUrlBuilder.MODE_TAG,
                ListUrlBuilder.MODE_UPLOADER,
                ListUrlBuilder.MODE_FILTER,
                ListUrlBuilder.MODE_WHATS_HOT,
                ListUrlBuilder.MODE_IMAGE_SEARCH,
                ListUrlBuilder.MODE_TOP_LIST
        };
    }

    private static ListUrlBuilder builder(int mode, String keyword) {
        ListUrlBuilder builder = new ListUrlBuilder();
        builder.setMode(mode);
        builder.setKeyword(keyword);
        return builder;
    }
}
