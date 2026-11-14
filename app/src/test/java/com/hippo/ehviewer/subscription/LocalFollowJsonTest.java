package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class LocalFollowJsonTest {
    @Test public void locationTagsRoundTripWithoutBeingRejected() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LocalFollowJson.write(output, Arrays.asList("location:school"));
        LocalFollowJson.ParseResult result = LocalFollowJson.read(
                new ByteArrayInputStream(output.toByteArray()));
        assertTrue(result.tags.contains("location:school"));
        assertEquals(0, result.invalid);
    }

    @Test public void utf8RoundTripNormalizesAndReportsInvalidEntries() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LocalFollowJson.write(output, Arrays.asList("artist:foo", "group:中文组"));
        String json = output.toString(StandardCharsets.UTF_8.name());
        assertTrue(json.contains("ehviewer.local-follow-tags"));
        assertTrue(json.contains("中文组"));

        LocalFollowJson.ParseResult result = LocalFollowJson.read(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, result.tags.size());
        assertEquals(0, result.invalid);
    }

    @Test public void duplicatesAndInvalidTagsAreSkipped() throws Exception {
        String json = "{\"format\":\"ehviewer.local-follow-tags\",\"version\":1," +
                "\"tags\":[\" Artist:Foo \",\"artist:foo\",\"free text\"]}";
        LocalFollowJson.ParseResult result = LocalFollowJson.read(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, result.tags.size());
        assertEquals(1, result.invalid);
        assertEquals(1, result.duplicates);
    }
}
