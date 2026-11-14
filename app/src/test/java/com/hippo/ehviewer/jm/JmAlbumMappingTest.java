package com.hippo.ehviewer.jm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import org.junit.Test;

public class JmAlbumMappingTest {
    @Test
    public void mapsAlbumFieldsAndChapters() {
        JSONObject json = JSON.parseObject("{"
                + "\"id\":350234,"
                + "\"name\":\"Title\","
                + "\"author\":[\"Author\"],"
                + "\"tags\":[\"中文\",\"全彩\"],"
                + "\"works\":[\"Work\"],"
                + "\"actors\":[\"Actor\"],"
                + "\"description\":\"Summary\","
                + "\"total_views\":\"100\","
                + "\"likes\":\"20\","
                + "\"comment_total\":\"3\","
                + "\"series\":[{\"id\":\"88\",\"sort\":\"1\",\"name\":\"First\"}]"
                + "}");

        JmAlbumInfo info = JmClient.mapAlbum("1", json, "", false);
        assertEquals("350234", info.albumId);
        assertEquals("Title", info.name);
        assertEquals("Author", info.authors.get(0));
        assertEquals("中文", info.tags.get(0));
        assertEquals("1. First", info.chapters.get(0));
        assertEquals("100", info.views);
        assertEquals("20", info.likes);
        assertEquals("3", info.commentCount);
        assertFalse(info.partial);
    }

    @Test
    public void emptySeriesUsesAlbumAsFirstChapter() {
        JSONObject json = JSON.parseObject(
                "{\"name\":\"Only chapter\",\"author\":[],\"series\":[]}");
        JmAlbumInfo info = JmClient.mapAlbum("9", json, "", false);
        assertEquals("9", info.albumId);
        assertEquals("Only chapter", info.chapters.get(0));
    }
}
