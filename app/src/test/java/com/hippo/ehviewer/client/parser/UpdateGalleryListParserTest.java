package com.hippo.ehviewer.client.parser;

import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.exception.ParseException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class UpdateGalleryListParserTest {
    @Test public void readsEveryGalleryAndTheNextLinkFromBothSitesInEveryLayout() throws Exception {
        for (String site : new String[]{"E", "Ex"}) {
            for (String layout : new String[]{"Minimal", "MinimalPlus", "Compat", "Extended", "Thumbnail"}) {
                String file = "GalleryListParserTest" + site + layout + ".html";
                try (InputStream sample = getClass().getResourceAsStream(file)) {
                    assertNotNull(file, sample);
                    GalleryListParser.Result page = GalleryListParser.parseForUpdate(
                            new String(sample.readAllBytes(), StandardCharsets.UTF_8), ListUrlBuilder.MODE_NORMAL);
                    assertEquals(file, 25, page.galleryInfoList.size());
                    assertEquals(file, site.equals("E") ? 1392268L : 1392234L, page.galleryInfoList.get(0).gid);
                    assertEquals(file, site.equals("E") ? 1391690L : 1391669L, page.galleryInfoList.get(24).gid);
                    assertTrue(file, page.nextHref.contains("page=1"));
                }
            }
        }
    }

    @Test public void cursorPaginationKeepsTheFullNextUrl() throws Exception {
        String body = "<div class='searchnav'><a id='unext' href='?next=123&amp;q=x'>next</a></div>"
                + "<div class='itg'><a href='https://e-hentai.org/g/123/0123456789/'>"
                + "<span class='glname'>Gallery</span></a></div>";
        GalleryListParser.Result page = GalleryListParser.parseForUpdate(body, ListUrlBuilder.MODE_NORMAL);
        assertEquals("?next=123&q=x", page.nextHref);
        assertEquals(123L, page.galleryInfoList.get(0).gid);
    }
    @Test public void accessErrorCannotBeCommittedAsAnEmptyPage() {
        assertThrows(ParseException.class, () -> GalleryListParser.parseForUpdate(
                "<html><p>Access denied</p></html>", ListUrlBuilder.MODE_NORMAL));
    }

    @Test public void explicitNoHitsIsAValidEmptyResult() throws Exception {
        assertTrue(GalleryListParser.parseForUpdate("<div><p>No hits found</p></div>",
                ListUrlBuilder.MODE_NORMAL).galleryInfoList.isEmpty());
    }
}
