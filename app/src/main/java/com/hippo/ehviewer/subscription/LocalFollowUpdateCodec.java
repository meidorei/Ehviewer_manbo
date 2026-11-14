package com.hippo.ehviewer.subscription;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** The small, version-independent gallery snapshot used by local update history. */
final class LocalFollowUpdateCodec {
    private LocalFollowUpdateCodec() {}

    static String encodeGallery(GalleryInfo gallery) {
        JSONObject json = new JSONObject(true);
        json.put("gid", gallery.gid);
        json.put("token", gallery.token);
        json.put("title", gallery.title);
        json.put("titleJpn", gallery.titleJpn);
        json.put("thumb", gallery.thumb);
        json.put("category", gallery.category);
        json.put("posted", gallery.posted);
        json.put("postedTimestamp", gallery.postedTimestamp);
        json.put("postedTimestampPrecision", gallery.postedTimestampPrecision.name());
        json.put("uploader", gallery.uploader);
        json.put("rating", gallery.rating);
        json.put("rated", gallery.rated);
        json.put("simpleLanguage", gallery.simpleLanguage);
        json.put("pages", gallery.pages);
        json.put("thumbWidth", gallery.thumbWidth);
        json.put("thumbHeight", gallery.thumbHeight);
        return JSON.toJSONString(json);
    }

    static GalleryInfo decodeGallery(String snapshot) {
        JSONObject json = JSON.parseObject(snapshot);
        if (json == null) throw new IllegalArgumentException("Missing follow update snapshot");
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = json.getLongValue("gid");
        gallery.token = json.getString("token");
        gallery.title = json.getString("title");
        gallery.titleJpn = json.getString("titleJpn");
        gallery.thumb = json.getString("thumb");
        gallery.category = json.getIntValue("category");
        gallery.posted = json.getString("posted");
        gallery.postedTimestamp = json.getLongValue("postedTimestamp");
        // This history is populated exclusively by successful API-backed update scans.
        gallery.postedTimestampPrecision = json.containsKey("postedTimestampPrecision")
                ? GalleryInfo.parsePrecision(json.getString("postedTimestampPrecision"))
                : FeedBoundary.Precision.SECOND;
        gallery.uploader = json.getString("uploader");
        gallery.rating = json.getFloatValue("rating");
        gallery.rated = json.getBooleanValue("rated");
        gallery.simpleLanguage = json.getString("simpleLanguage");
        gallery.pages = json.getIntValue("pages");
        gallery.thumbWidth = json.getIntValue("thumbWidth");
        gallery.thumbHeight = json.getIntValue("thumbHeight");
        return gallery;
    }

    static List<String> decodeTags(String snapshot) {
        JSONArray array = JSON.parseArray(snapshot);
        TreeSet<String> tags = new TreeSet<>();
        if (array != null) {
            for (Object item : array) {
                if (item instanceof String) tags.add((String) item);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(tags));
    }

    static String mergeTag(String snapshot, String rawTag) {
        TreeSet<String> tags = new TreeSet<>(decodeTags(snapshot));
        String tag = SubscriptionRepository.normalizeTagName(rawTag);
        if (!tag.isEmpty()) tags.add(tag);
        return JSON.toJSONString(tags);
    }
}
