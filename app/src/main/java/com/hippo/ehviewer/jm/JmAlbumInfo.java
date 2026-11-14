package com.hippo.ehviewer.jm;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class JmAlbumInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String albumId;
    public final String name;
    public final String description;
    public final List<String> authors;
    public final List<String> tags;
    public final List<String> works;
    public final List<String> actors;
    public final List<String> chapters;
    public final String pageCount;
    public final String views;
    public final String likes;
    public final String commentCount;
    public final String webUrl;
    public final boolean partial;

    public JmAlbumInfo(String albumId, String name, String description,
            List<String> authors, List<String> tags, List<String> works,
            List<String> actors, List<String> chapters, String pageCount,
            String views, String likes, String commentCount, String webUrl,
            boolean partial) {
        this.albumId = value(albumId);
        this.name = value(name);
        this.description = value(description);
        this.authors = immutable(authors);
        this.tags = immutable(tags);
        this.works = immutable(works);
        this.actors = immutable(actors);
        this.chapters = immutable(chapters);
        this.pageCount = value(pageCount);
        this.views = value(views);
        this.likes = value(likes);
        this.commentCount = value(commentCount);
        this.webUrl = value(webUrl);
        this.partial = partial;
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> immutable(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
