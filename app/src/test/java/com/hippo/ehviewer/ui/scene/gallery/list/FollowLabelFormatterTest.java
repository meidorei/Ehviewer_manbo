package com.hippo.ehviewer.ui.scene.gallery.list;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FollowLabelFormatterTest {

    @Test
    public void formatsArtistTranslation() {
        assertEquals("艺术家:作者名", FollowLabelFormatter.composeDisplayName(
                "artist:author", "艺术家", "作者名"));
    }

    @Test
    public void formatsGroupTranslation() {
        assertEquals("团队:团队名", FollowLabelFormatter.composeDisplayName(
                "group:circle", "团队", "团队名"));
    }

    @Test
    public void formatsFemaleTranslation() {
        assertEquals("女性:标签名", FollowLabelFormatter.composeDisplayName(
                "female:tag", "女性", "标签名"));
    }

    @Test
    public void supportsOtherNamespaces() {
        assertEquals("原作:作品名", FollowLabelFormatter.composeDisplayName(
                "parody:series", "原作", "作品名"));
    }

    @Test
    public void keepsRawValueWhenOnlyNamespaceIsTranslated() {
        assertEquals("艺术家:unknown_author", FollowLabelFormatter.composeDisplayName(
                "artist:unknown_author", "艺术家", null));
    }

    @Test
    public void keepsRawQueryWhenNothingIsTranslated() {
        assertEquals("custom:value", FollowLabelFormatter.composeDisplayName(
                "custom:value", null, null));
    }

    @Test
    public void usesLocalizedNamespaceFallbackWithoutDatabase() {
        FollowLabelFormatter.Presentation presentation =
                FollowLabelFormatter.present("artist:amagaeru", null, "艺术家");
        assertEquals("艺术家:amagaeru", presentation.displayName);
        assertEquals("artist:amagaeru", presentation.rawQuery);
    }

    @Test
    public void handlesNullAndMalformedQueries() {
        assertEquals("", FollowLabelFormatter.composeDisplayName(null, "艺术家", "作者名"));
        assertEquals("artist", FollowLabelFormatter.composeDisplayName(
                "artist", "艺术家", "作者名"));
        assertEquals("artist:", FollowLabelFormatter.composeDisplayName(
                "artist:", "艺术家", "作者名"));
    }
}
