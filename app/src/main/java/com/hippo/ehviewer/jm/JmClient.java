package com.hippo.ehviewer.jm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.EhApplication;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.ByteString;

public final class JmClient {
    private static final String DEFAULT_APP_VERSION = "2.0.28";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Version/4.0 Chrome/113.0 Mobile Safari/537.36";
    private static final String REDIRECT_URL = "https://jm365.work/3YeBdF";
    private static final String[] API_DOMAINS = {
            "www.cdnhjk.net",
            "www.cdngwc.cc",
            "www.cdngwc.net",
            "www.cdngwc.club"
    };
    private static final String[] IMAGE_DOMAINS = {
            "cdn-msp.jmapiproxy1.cc",
            "cdn-msp.jmapiproxy2.cc",
            "cdn-msp2.jmapiproxy2.cc",
            "cdn-msp3.jmapiproxy2.cc",
            "cdn-msp.jmapinodeudzn.net",
            "cdn-msp3.jmapinodeudzn.net"
    };
    private static final Pattern WRAPPED_HTML = Pattern.compile(
            "const\\s+html\\s*=\\s*base64DecodeUtf8\\(\"(.*?)\"\\)",
            Pattern.DOTALL);

    private final OkHttpClient client;
    private final AtomicBoolean canceled = new AtomicBoolean();
    private volatile Call currentCall;

    private JmClient(OkHttpClient client) {
        this.client = client;
    }

    public static JmClient create(@NonNull Context context) {
        OkHttpClient.Builder builder =
                EhApplication.getOkHttpClient(context.getApplicationContext()).newBuilder();
        builder.cookieJar(new MemoryCookieJar());
        builder.cache(null);
        // The shared interceptors synchronize EH cookies into WebView. JM cookies must stay isolated.
        builder.networkInterceptors().clear();
        return new JmClient(builder.build());
    }

    public void cancel() {
        canceled.set(true);
        Call call = currentCall;
        if (call != null) {
            call.cancel();
        }
    }

    public JmAlbumInfo query(String albumId) throws IOException {
        Throwable lastFailure = null;
        for (String domain : API_DOMAINS) {
            checkCanceled();
            try {
                return queryApiDomain(domain, albumId);
            } catch (NotFoundException e) {
                throw e;
            } catch (Exception e) {
                lastFailure = e;
            }
        }

        checkCanceled();
        try {
            return queryHtml(albumId);
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            if (lastFailure != null) {
                e.addSuppressed(lastFailure);
            }
            throw new IOException("JM service is temporarily unavailable", e);
        }
    }

    public Bitmap loadCover(String albumId) throws IOException {
        IOException lastFailure = null;
        for (String domain : IMAGE_DOMAINS) {
            checkCanceled();
            String url = "https://" + domain + "/media/albums/" + albumId + ".jpg";
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build();
            try (Response response = execute(request)) {
                if (!response.isSuccessful() || response.body() == null) {
                    lastFailure = new IOException("Cover HTTP " + response.code());
                    continue;
                }
                byte[] bytes = response.body().bytes();
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    return bitmap;
                }
                lastFailure = new IOException("Invalid cover image");
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("Cover unavailable");
    }

    public String discoverAlbumWebUrl(String albumId) throws IOException {
        String baseUrl = discoverWebBaseUrl();
        return baseUrl + "/album/" + albumId;
    }

    private JmAlbumInfo queryApiDomain(String domain, String albumId)
            throws IOException, GeneralSecurityException {
        String version = DEFAULT_APP_VERSION;
        JSONObject setting = apiRequest(domain, "/setting", version, false);
        String serverVersion = setting.getString("jm3_version");
        if (!isEmpty(serverVersion) && compareVersions(serverVersion, version) > 0) {
            version = serverVersion;
        }
        JSONObject album = apiRequest(domain, "/album?id=" + albumId, version, true);
        String name = album.getString("name");
        if (isEmpty(name)) {
            throw new NotFoundException("JM" + albumId + " does not exist");
        }
        return mapAlbum(albumId, album, "", false);
    }

    private JSONObject apiRequest(String domain, String path, String version,
            boolean missingMeansNotFound)
            throws IOException, GeneralSecurityException {
        checkCanceled();
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        Request request = new Request.Builder()
                .url("https://" + domain + path)
                .header("User-Agent", USER_AGENT)
                .header("token", JmApiCodec.token(timestamp))
                .header("tokenparam", JmApiCodec.tokenParam(timestamp, version))
                .build();
        try (Response response = execute(request)) {
            if (response.code() == 404) {
                if (missingMeansNotFound) {
                    throw new NotFoundException("JM entry does not exist");
                }
                throw new IOException("JM API HTTP 404");
            }
            if (!response.isSuccessful()) {
                throw new IOException("JM API HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty JM API response");
            }
            JSONObject outer = parseOuterJson(body.string());
            int code = outer.getIntValue("code");
            if (code == 404) {
                if (missingMeansNotFound) {
                    throw new NotFoundException("JM entry does not exist");
                }
                throw new IOException("JM API code 404");
            }
            if (code != 200) {
                throw new IOException("JM API code " + code);
            }
            String data = outer.getString("data");
            if (isEmpty(data)) {
                if (missingMeansNotFound) {
                    throw new NotFoundException("JM entry does not exist");
                }
                throw new IOException("JM API response is missing data");
            }
            return JmApiCodec.decrypt(data, timestamp);
        }
    }

    private JmAlbumInfo queryHtml(String albumId) throws IOException {
        String url = discoverAlbumWebUrl(albumId);
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response response = execute(request)) {
            if (response.code() == 404) {
                throw new NotFoundException("JM" + albumId + " does not exist");
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("JM web HTTP " + response.code());
            }
            Document document = Jsoup.parse(unwrapHtml(response.body().string()), url);
            Element nameElement = document.selectFirst("#book-name");
            if (nameElement == null || isEmpty(nameElement.text())) {
                throw new IOException("JM web page format changed");
            }
            List<String> authors = texts(document,
                    "a[href*=/search/photos?search_query=], .author a");
            List<String> tags = texts(document, ".tag-block a, a[href*=/tag/]");
            Element descriptionElement = document.selectFirst(
                    "#intro-block, .p-t-5, .description");
            String description = descriptionElement == null ? "" : descriptionElement.text();
            return new JmAlbumInfo(albumId, nameElement.text(), description,
                    authors, tags, Collections.emptyList(), Collections.emptyList(),
                    Collections.singletonList(nameElement.text()), "", "", "", "",
                    response.request().url().toString(), true);
        }
    }

    private String discoverWebBaseUrl() throws IOException {
        Request request = new Request.Builder()
                .url(REDIRECT_URL)
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response response = execute(request)) {
            if (!response.isSuccessful()) {
                throw new IOException("JM domain discovery HTTP " + response.code());
            }
            HttpUrl url = response.request().url();
            return url.scheme() + "://" + url.host()
                    + (url.port() == HttpUrl.defaultPort(url.scheme()) ? "" : ":" + url.port());
        }
    }

    private Response execute(Request request) throws IOException {
        checkCanceled();
        Call call = client.newCall(request);
        currentCall = call;
        // Keep the call reachable until the next request so cancel() can also stop body reads.
        return call.execute();
    }

    private void checkCanceled() throws InterruptedIOException {
        if (canceled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Canceled");
        }
    }

    private static JSONObject parseOuterJson(String text) throws IOException {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IOException("JM API returned non-JSON content");
        }
        try {
            Object value = JSON.parse(text.substring(start, end + 1));
            if (value instanceof JSONObject) {
                return (JSONObject) value;
            }
        } catch (RuntimeException ignored) {
        }
        throw new IOException("Invalid JM API JSON");
    }

    static JmAlbumInfo mapAlbum(String fallbackId, JSONObject data,
            String webUrl, boolean partial) {
        String id = string(data, "id");
        if (isEmpty(id)) {
            id = fallbackId;
        }
        List<String> chapters = new ArrayList<>();
        JSONArray series = data.getJSONArray("series");
        if (series != null) {
            for (int i = 0; i < series.size(); i++) {
                JSONObject chapter = series.getJSONObject(i);
                if (chapter == null) {
                    continue;
                }
                String sort = string(chapter, "sort");
                String name = string(chapter, "name");
                if (!isEmpty(name)) {
                    chapters.add(isEmpty(sort) ? name : sort + ". " + name);
                }
            }
        }
        if (chapters.isEmpty()) {
            chapters.add(string(data, "name"));
        }
        return new JmAlbumInfo(id, string(data, "name"), string(data, "description"),
                strings(data.get("author")), strings(data.get("tags")),
                strings(data.get("works")), strings(data.get("actors")), chapters,
                first(data, "page_count", "total_page", "pages"),
                first(data, "total_views", "views"), string(data, "likes"),
                first(data, "comment_total", "comment_count"), webUrl, partial);
    }

    private static String first(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = string(object, key);
            if (!isEmpty(value)) {
                return value;
            }
        }
        return "";
    }

    private static String string(JSONObject object, String key) {
        Object value = object.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static List<String> strings(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                if (item != null && !isEmpty(String.valueOf(item).trim())) {
                    result.add(String.valueOf(item).trim());
                }
            }
        } else if (!isEmpty(String.valueOf(value).trim())) {
            result.add(String.valueOf(value).trim());
        }
        return result;
    }

    private static List<String> texts(Document document, String selector) {
        List<String> result = new ArrayList<>();
        for (Element element : document.select(selector)) {
            String text = element.text().trim();
            if (!isEmpty(text) && !result.contains(text)) {
                result.add(text);
            }
        }
        return result;
    }

    private static String unwrapHtml(String text) throws IOException {
        Matcher matcher = WRAPPED_HTML.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        ByteString decoded = ByteString.decodeBase64(matcher.group(1));
        if (decoded == null) {
            throw new IOException("Invalid wrapped JM HTML");
        }
        return new String(decoded.toByteArray(), StandardCharsets.UTF_8);
    }

    static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int av = i < a.length ? parseVersionPart(a[i]) : 0;
            int bv = i < b.length ? parseVersionPart(b[i]) : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private static int parseVersionPart(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    public static final class NotFoundException extends IOException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    private static final class MemoryCookieJar implements CookieJar {
        private final Map<String, List<Cookie>> cookiesByHost = new HashMap<>();

        @Override
        public synchronized void saveFromResponse(@NonNull HttpUrl url,
                @NonNull List<Cookie> cookies) {
            List<Cookie> merged = new ArrayList<>();
            List<Cookie> existing = cookiesByHost.get(url.host());
            if (existing != null) {
                merged.addAll(existing);
            }
            for (Cookie cookie : cookies) {
                for (int i = merged.size() - 1; i >= 0; i--) {
                    Cookie old = merged.get(i);
                    if (old.name().equals(cookie.name())
                            && old.domain().equals(cookie.domain())
                            && old.path().equals(cookie.path())) {
                        merged.remove(i);
                    }
                }
                if (cookie.expiresAt() > System.currentTimeMillis()) {
                    merged.add(cookie);
                }
            }
            cookiesByHost.put(url.host(), merged);
        }

        @NonNull
        @Override
        public synchronized List<Cookie> loadForRequest(@NonNull HttpUrl url) {
            List<Cookie> cookies = cookiesByHost.get(url.host());
            if (cookies == null) {
                return Collections.emptyList();
            }
            List<Cookie> valid = new ArrayList<>();
            for (Cookie cookie : cookies) {
                if (cookie.matches(url)) {
                    valid.add(cookie);
                }
            }
            return valid;
        }
    }
}
