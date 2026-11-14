package com.hippo.ehviewer.subscription;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** UTF-8 import/export contract for device-local follow tags. */
public final class LocalFollowJson {
    public static final String FORMAT = "ehviewer.local-follow-tags";
    public static final int VERSION = 1;
    private static final Set<String> NAMESPACES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("artist", "group", "parody", "character",
                    "female", "male", "misc", "language", "cosplayer", "mixed",
                    "other", "reclass", "location")));

    private LocalFollowJson() {}

    public static void write(OutputStream output, List<String> tags) throws IOException {
        JSONObject root = new JSONObject(true);
        root.put("format", FORMAT);
        root.put("version", VERSION);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        root.put("exportedAt", format.format(new Date()));
        root.put("count", tags.size());
        JSONArray tagArray = new JSONArray();
        tagArray.addAll(tags);
        root.put("tags", tagArray);
        output.write(JSON.toJSONString(root, true).getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    public static ParseResult read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) bytes.write(buffer, 0, read);
        JSONObject root;
        try {
            root = JSON.parseObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("JSON 格式无效", e);
        }
        if (root == null || !FORMAT.equals(root.getString("format"))
                || root.getIntValue("version") != VERSION) {
            throw new IOException("不支持的追更文件格式或版本");
        }
        JSONArray array = root.getJSONArray("tags");
        if (array == null) throw new IOException("追更文件缺少 tags");
        Set<String> valid = new LinkedHashSet<>();
        int invalid = 0;
        int duplicates = 0;
        for (Object item : array) {
            String tag = SubscriptionRepository.normalizeTagName(item == null ? null : item.toString());
            if (!isValidStandardTag(tag)) {
                invalid++;
            } else if (!valid.add(tag)) {
                duplicates++;
            }
        }
        return new ParseResult(valid, invalid, duplicates);
    }

    public static boolean isValidStandardTag(String tag) {
        if (tag == null) return false;
        int colon = tag.indexOf(':');
        return colon > 0 && colon < tag.length() - 1
                && tag.indexOf(':', colon + 1) < 0
                && NAMESPACES.contains(tag.substring(0, colon));
    }

    public static final class ParseResult {
        public final Set<String> tags;
        public final int invalid;
        public final int duplicates;

        ParseResult(Set<String> tags, int invalid, int duplicates) {
            this.tags = Collections.unmodifiableSet(tags);
            this.invalid = invalid;
            this.duplicates = duplicates;
        }
    }
}
