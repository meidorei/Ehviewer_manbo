package com.hippo.ehviewer.subscription;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCookieStore;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.IgneousUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.dao.QuickSearch;
import com.hippo.ehviewer.ui.MainActivity;

import java.lang.ref.WeakReference;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/** Manual, single-concurrency foreground update service for local follows and bookmarks. */
public final class LocalUpdateService extends Service {
    public static final String ACTION_START_ALL = "local.update.START_ALL";
    public static final String ACTION_START_FOLLOW = "local.update.START_FOLLOW";
    public static final String ACTION_START_BOOKMARKS = "local.update.START_BOOKMARKS";
    public static final String ACTION_START_BOOKMARK = "local.update.START_BOOKMARK";
    public static final String ACTION_START_BASELINES = "local.update.START_BASELINES";
    public static final String ACTION_PAUSE = "local.update.PAUSE";
    public static final String ACTION_CANCEL = "local.update.CANCEL";
    public static final String EXTRA_METHOD = "method";
    public static final String EXTRA_BOOKMARK_ID = "bookmark_id";
    public static final String METHOD_GLOBAL = "GLOBAL";
    public static final String METHOD_TAGS = "TAGS";
    public static final String METHOD_FIRST_PAGE = "FIRST_PAGE";
    private static final String CHANNEL_ID = "local_update";
    private static final int NOTIFICATION_ID = 8042;
    private static final LocalUpdateGate GATE = new LocalUpdateGate();
    private static final CopyOnWriteArrayList<WeakReference<Listener>> LISTENERS =
            new CopyOnWriteArrayList<>();

    private volatile boolean pauseRequested;
    private volatile boolean cancelRequested;
    private Thread worker;
    private long lastSearchAt;
    private int searchIntervalMs = SearchIntervalPolicy.DEFAULT_MS;
    private String effectiveHost;
    private final AtomicReference<Call> activeCall = new AtomicReference<>();

    public interface Listener {
        void onLocalUpdateProgress(LocalRefreshJobStore.Snapshot snapshot);
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(new WeakReference<>(listener));
    }

    public static boolean isActive() {
        return GATE.isBusy();
    }

    /** Returns -1 when an update owns the gate or a saved job must be stopped first. */
    public static int resetBookmarkBaselines() {
        return resetBaselines(false);
    }

    public static int resetFollowBaselines() {
        return resetBaselines(true);
    }

    private static int resetBaselines(boolean follows) {
        if (!GATE.tryStartMaintenance()) return -1;
        try {
            LocalRefreshJobStore.Snapshot snapshot = LocalRefreshJobStore.read();
            if (snapshot != null && LocalBaselineResetPolicy.requiresStoppedJob(snapshot.status)) return -1;
            LocalFollowRepository repository = LocalFollowRepository.getInstance();
            long now = System.currentTimeMillis();
            return follows ? repository.resetFollowBaselines(now)
                    : repository.resetBookmarkBaselines(now);
        } finally {
            GATE.finishMaintenance();
        }
    }

    public static boolean startFollow(Context context, String method) {
        if (METHOD_GLOBAL.equals(method) || method == null) return startGlobal(context);
        return startLegacyFollow(context, method);
    }

    private static boolean startLegacyFollow(Context context, String method) {
        if (!GATE.tryStartUpdate()) return false;
        Intent intent = new Intent(context, LocalUpdateService.class)
                .setAction(ACTION_START_FOLLOW)
                .putExtra(EXTRA_METHOD, method);
        return startServiceSafely(context, intent);
    }

    public static boolean startBookmarks(Context context) {
        return startBookmarks(context, METHOD_FIRST_PAGE);
    }

    public static boolean startBookmarks(Context context, String method) {
        if (METHOD_GLOBAL.equals(method)) return startGlobal(context);
        return startLegacyBookmarks(context, method);
    }

    private static boolean startLegacyBookmarks(Context context, String method) {
        if (!GATE.tryStartUpdate()) return false;
        Intent intent = new Intent(context, LocalUpdateService.class)
                .setAction(ACTION_START_BOOKMARKS)
                .putExtra(EXTRA_METHOD, METHOD_GLOBAL.equals(method)
                        ? METHOD_GLOBAL : METHOD_FIRST_PAGE);
        return startServiceSafely(context, intent);
    }

    public static boolean startGlobal(Context context) {
        if (LocalFollowRepository.getInstance().getAll().isEmpty()
                && EhDB.getAllQuickSearch().isEmpty()) {
            android.widget.Toast.makeText(context, R.string.local_update_empty,
                    android.widget.Toast.LENGTH_SHORT).show();
            return true;
        }
        if (!GATE.tryStartUpdate()) return false;
        return startServiceSafely(context, new Intent(context, LocalUpdateService.class)
                .setAction(ACTION_START_ALL).putExtra(EXTRA_METHOD, METHOD_GLOBAL));
    }

    /** Resume old snapshots without silently expanding their original scope. */
    public static boolean resumeJob(Context context, LocalRefreshJobStore.Snapshot snapshot) {
        if (LocalRefreshJobStore.TYPE_ALL.equals(snapshot.type)) return startGlobal(context);
        if (LocalRefreshJobStore.TYPE_FOLLOW.equals(snapshot.type)) {
            return startLegacyFollow(context, snapshot.method);
        }
        if (LocalRefreshJobStore.TYPE_BOOKMARK.equals(snapshot.type)) {
            return startLegacyBookmarks(context, snapshot.method);
        }
        return false;
    }

    public static boolean startBookmark(Context context, long id) {
        if (!GATE.tryStartUpdate()) return false;
        Intent intent = new Intent(context, LocalUpdateService.class)
                .setAction(ACTION_START_BOOKMARK)
                .putExtra(EXTRA_BOOKMARK_ID, id);
        return startServiceSafely(context, intent);
    }

    public static boolean startPendingBaselines(Context context) {
        LocalRefreshJobStore.Snapshot snapshot = LocalRefreshJobStore.read();
        if (snapshot != null
                && LocalRefreshJobStore.STATUS_PAUSED.equals(snapshot.status)) {
            return false;
        }
        return startBaselines(context);
    }

    public static boolean resumePendingBaselines(Context context) {
        return startBaselines(context);
    }

    private static boolean startBaselines(Context context) {
        if (!LocalBaselineQueue.hasPending()) return false;
        if (!GATE.tryStartUpdate()) return false;
        // A reset may have cleared the queue between the first read and acquiring the gate.
        if (!LocalBaselineQueue.hasPending()) {
            GATE.finishUpdate();
            return false;
        }
        Intent intent = new Intent(context, LocalUpdateService.class)
                .setAction(ACTION_START_BASELINES);
        return startServiceSafely(context, intent);
    }

    public static boolean requestPause(Context context) {
        return sendControl(context, ACTION_PAUSE);
    }

    public static boolean requestCancel(Context context) {
        if (GATE.isUpdating()) return sendControl(context, ACTION_CANCEL);
        // Paused jobs have no running service to receive a control Intent.
        if (!GATE.tryStartMaintenance()) return false;
        try {
            LocalRefreshJobStore.Snapshot snapshot = LocalRefreshJobStore.read();
            if (snapshot == null
                    || !LocalRefreshJobStore.STATUS_PAUSED.equals(snapshot.status)) return false;
            org.greenrobot.greendao.database.Database db = EhDB.getDatabase();
            db.beginTransaction();
            try {
                if (LocalRefreshJobStore.TYPE_BASELINE.equals(snapshot.type)) {
                    LocalBaselineQueue.cancelOutstanding();
                }
                LocalRefreshJobStore.finish(LocalRefreshJobStore.STATUS_CANCELLED, false);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(NOTIFICATION_ID);
        } catch (RuntimeException error) {
            android.util.Log.e("LocalUpdateService", "Unable to stop paused job", error);
            return false;
        } finally {
            GATE.finishMaintenance();
        }
        notifyListeners();
        return true;
    }

    private static boolean sendControl(Context context, String action) {
        if (!GATE.isUpdating()) return false;
        try {
            context.startService(new Intent(context, LocalUpdateService.class).setAction(action));
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean startServiceSafely(Context context, Intent intent) {
        try {
            ContextCompat.startForegroundService(context, intent);
            return true;
        } catch (RuntimeException error) {
            GATE.finishUpdate();
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (worker == null && (ACTION_PAUSE.equals(action) || ACTION_CANCEL.equals(action))) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            pauseRequested = true;
            interruptActiveWork();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            cancelRequested = true;
            interruptActiveWork();
            return START_NOT_STICKY;
        }
        if (worker != null) return START_NOT_STICKY;
        if (!GATE.isUpdating() && !GATE.tryStartUpdate()) return START_NOT_STICKY;
        pauseRequested = false;
        cancelRequested = false;
        startForeground(NOTIFICATION_ID, notification("准备检查更新", 0, 0, true));
        worker = new Thread(() -> runJob(intent), "local-update-worker");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runJob(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean follow = ACTION_START_FOLLOW.equals(action);
        boolean baseline = ACTION_START_BASELINES.equals(action);
        try {
            if (ACTION_START_ALL.equals(action)) {
                runUnifiedGlobal();
            } else if (follow) {
                runFollows(intent.getStringExtra(EXTRA_METHOD));
            } else if (ACTION_START_BOOKMARKS.equals(action)) {
                runBookmarks(null, intent.getStringExtra(EXTRA_METHOD));
            } else if (ACTION_START_BOOKMARK.equals(action)) {
                runBookmarks(intent.getLongExtra(EXTRA_BOOKMARK_ID, -1),
                        METHOD_FIRST_PAGE);
            } else if (baseline) {
                runBaselines();
            }
            if (cancelRequested) {
                LocalRefreshJobStore.finish(LocalRefreshJobStore.STATUS_CANCELLED, false);
            } else if (pauseRequested) {
                LocalRefreshJobStore.finish(LocalRefreshJobStore.STATUS_PAUSED, false);
            } else {
                LocalRefreshJobStore.finish(LocalRefreshJobStore.STATUS_SUCCESS, follow);
            }
        } catch (Throwable error) {
            if (cancelRequested) {
                LocalRefreshJobStore.finish(LocalRefreshJobStore.STATUS_CANCELLED, false);
            } else if (pauseRequested) {
                LocalRefreshJobStore.finish(LocalRefreshJobStore.STATUS_PAUSED, false);
            } else {
                LocalRefreshJobStore.Snapshot snapshot = LocalRefreshJobStore.read();
                if (snapshot != null) {
                    LocalRefreshJobStore.progress(snapshot.index, snapshot.pages,
                            snapshot.galleries, snapshot.currentKey,
                            error instanceof ItemsFailedException ? snapshot.failures
                                    : appendFailure(snapshot.failures, safeMessage(error)));
                }
                LocalRefreshJobStore.finish(LocalRefreshJobStore.STATUS_FAILED, false);
            }
        } finally {
            activeCall.set(null);
            if (baseline) {
                if (cancelRequested) {
                    LocalBaselineQueue.cancelOutstanding();
                } else if (pauseRequested
                        || LocalRefreshJobStore.STATUS_FAILED.equals(
                        statusOf(LocalRefreshJobStore.read()))) {
                    LocalBaselineQueue.resetRunning();
                }
            }
            String summary = terminalSummary(LocalRefreshJobStore.read());
            boolean hasPending = !baseline && !pauseRequested && LocalBaselineQueue.hasPending();
            // Serialize service teardown with onStartCommand before handing the gate to the next job.
            new Handler(Looper.getMainLooper()).post(() -> {
                worker = null;
                stopForeground(false);
                stopSelf();
                GATE.finishUpdate();
                updateNotification(summary, 0, 0, false);
                notifyListeners();
                if (hasPending) {
                    startPendingBaselines(getApplicationContext());
                }
            });
        }
    }

    private void runBaselines() throws Throwable {
        List<LocalBaselineQueue.Item> pending = LocalBaselineQueue.pending();
        if (pending.isEmpty()) throw new IllegalStateException("没有待建立的基线");
        String host = preferredHost();
        effectiveHost = host;
        LocalRefreshJobStore.Snapshot previous = LocalRefreshJobStore.read();
        searchIntervalMs = intervalForJob(previous,
                LocalRefreshJobStore.TYPE_BASELINE, "AUTO");
        LocalRefreshJobStore.start("BASELINE", "AUTO", pending.size(), host,
                searchIntervalMs);
        LocalRefreshJobStore.phase(LocalRefreshJobStore.PHASE_BASELINE);
        notifyListeners();
        int completed = 0;
        List<String> fallbacks = new ArrayList<>();
        for (int i = 0; i < pending.size() && !shouldStop();) {
            LocalBaselineQueue.Item first = pending.get(i);
            List<LocalBaselineQueue.Item> group = new ArrayList<>();
            group.add(first);
            if (LocalBaselineQueue.METHOD_FOLLOW_GLOBAL.equals(first.method)) {
                int next = i + 1;
                while (next < pending.size()
                        && first.batchKey.equals(pending.get(next).batchKey)
                        && LocalBaselineQueue.METHOD_FOLLOW_GLOBAL.equals(
                        pending.get(next).method)) {
                    group.add(pending.get(next++));
                }
            }
            LocalBaselineQueue.markRunning(group);
            String current = group.size() == 1
                    ? group.get(0).sourceKey : "追更导入 " + group.size() + " 项";
            try {
                processBaselineWithRetries(group);
                throwIfStopRequested();
                LocalBaselineQueue.markDone(group);
            } catch (BaselineFallbackException fallback) {
                LocalBaselineQueue.markFallback(group, fallback.getMessage());
                fallbacks.add(current + "：" + fallback.getMessage());
            } catch (Throwable error) {
                if (shouldStop()) return;
                throw error;
            }
            completed += group.size();
            i += group.size();
            LocalRefreshJobStore.progress(completed, 1, completed, current,
                    joinFailures(fallbacks));
            updateNotification("建立基线：" + completed + "/" + pending.size(),
                    completed, pending.size(), false);
            notifyListeners();
        }
        LocalRefreshJobStore.progress(completed, 1, completed, "",
                joinFailures(fallbacks));
    }

    private void processBaselineWithRetries(List<LocalBaselineQueue.Item> group)
            throws Throwable {
        Throwable last = null;
        for (int attempt = 0; attempt < 3 && !shouldStop(); attempt++) {
            try {
                processBaselineGroup(group);
                return;
            } catch (BaselineFallbackException error) {
                throw error;
            } catch (Throwable error) {
                if (shouldStop() || !isRetryable(error)) throw error;
                last = error;
                if (attempt < 2) Thread.sleep((attempt + 1L) * 1600L);
            }
        }
        throw new BaselineFallbackException(last == null
                ? "使用新增时刻基线" : "网络失败，使用新增时刻基线");
    }

    private void processBaselineGroup(List<LocalBaselineQueue.Item> group)
            throws Throwable {
        if (group.isEmpty()) return;
        LocalBaselineQueue.Item first = group.get(0);
        if (LocalBaselineQueue.METHOD_FOLLOW_GLOBAL.equals(first.method)) {
            List<LocalBaselineQueue.Item> existing = new ArrayList<>();
            for (LocalBaselineQueue.Item item : group) {
                if (LocalFollowRepository.getInstance().contains(item.sourceKey)) {
                    existing.add(item);
                    ensureProvisional(item);
                }
            }
            if (existing.isEmpty()) return;
            ListUrlBuilder builder = new ListUrlBuilder();
            builder.setMode(ListUrlBuilder.MODE_NORMAL);
            builder.setKeyword(null);
            GalleryListParser.Result page = fetchPage(
                    withHost(builder.build(true), effectiveHost), ListUrlBuilder.MODE_NORMAL);
            throwIfStopRequested();
            FeedBoundary boundary = BaselineBoundaryPolicy.newestAtOrBefore(
                    page.galleryInfoList, first.addedAt);
            if (boundary.isEmpty()) {
                throw new BaselineFallbackException("第一页未覆盖加入时刻，使用新增时刻基线");
            }
            for (LocalBaselineQueue.Item item : existing) {
                throwIfStopRequested();
                ensureProvisional(item);
                LocalFollowRepository.getInstance().establishBaseline(
                        item.sourceType, item.sourceKey, item.signature,
                        baselineCheckpoint(item), boundary);
            }
            return;
        }
        if (LocalBaselineQueue.METHOD_FOLLOW_TAG.equals(first.method)) {
            if (!LocalFollowRepository.getInstance().contains(first.sourceKey)) return;
            ensureProvisional(first);
            ListUrlBuilder builder = new ListUrlBuilder();
            builder.set(first.sourceKey);
            GalleryListParser.Result page = fetchPage(
                    withHost(builder.build(true), effectiveHost), ListUrlBuilder.MODE_TAG);
            commitRefinedBoundary(first, page.galleryInfoList);
            return;
        }
        if (LocalBaselineQueue.METHOD_BOOKMARK.equals(first.method)) {
            QuickSearch search = findBookmark(first.sourceKey);
            if (search == null) return;
            BookmarkUpdatePolicy.Result policy = BookmarkUpdatePolicy.resolve(search);
            if (!policy.supported || !first.signature.equals(policy.signature)) {
                throw new BaselineFallbackException(policy.error.isEmpty()
                        ? "查询条件已变化" : policy.error);
            }
            ensureProvisional(first);
            GalleryListParser.Result page = fetchPage(
                    withHost(policy.url, effectiveHost), search.mode);
            commitRefinedBoundary(first, page.galleryInfoList);
        }
    }

    private void commitRefinedBoundary(LocalBaselineQueue.Item item,
                                       List<GalleryInfo> galleries)
            throws InterruptedException, BaselineFallbackException {
        throwIfStopRequested();
        FeedBoundary boundary =
                BaselineBoundaryPolicy.newestAtOrBefore(galleries, item.addedAt);
        if (boundary.isEmpty()) {
            throw new BaselineFallbackException(galleries == null || galleries.isEmpty()
                    ? "结果为空，使用新增时刻基线"
                    : "第一页未覆盖加入时刻，使用新增时刻基线");
        }
        ensureProvisional(item);
        LocalFollowRepository.getInstance().establishBaseline(
                item.sourceType, item.sourceKey, item.signature,
                baselineCheckpoint(item), boundary);
    }

    private void ensureProvisional(LocalBaselineQueue.Item item) {
        LocalFollowRepository.getInstance().ensureProvisionalCheckpoint(
                item.sourceType, item.sourceKey, item.signature,
                baselineCheckpoint(item), item.addedAt);
    }

    private CheckpointKey baselineCheckpoint(LocalBaselineQueue.Item item) {
        String source = LocalFollowRepository.SOURCE_FOLLOW.equals(item.sourceType)
                ? LocalFollowRepository.CHECKPOINT_FOLLOW
                : LocalFollowRepository.CHECKPOINT_BOOKMARK;
        return new CheckpointKey(sourceContext(effectiveHost), source, item.sourceKey,
                item.signature);
    }

    @Nullable
    private static QuickSearch findBookmark(String sourceKey) {
        long id;
        try {
            id = Long.parseLong(sourceKey);
        } catch (NumberFormatException ignored) {
            return null;
        }
        for (QuickSearch search : EhDB.getAllQuickSearch()) {
            if (search.id != null && search.id == id) return search;
        }
        return null;
    }

    private static String statusOf(@Nullable LocalRefreshJobStore.Snapshot snapshot) {
        return snapshot == null ? "" : snapshot.status;
    }

    private static final class BaselineFallbackException extends Exception {
        BaselineFallbackException(String message) {
            super(message);
        }
    }

    private void runUnifiedGlobal() throws Throwable {
        List<String> tags = LocalFollowRepository.getInstance().getAll();
        List<QuickSearch> jobs = EhDB.getAllQuickSearch();
        effectiveHost = preferredHost();
        searchIntervalMs = intervalForJob(LocalRefreshJobStore.read(),
                LocalRefreshJobStore.TYPE_ALL, METHOD_GLOBAL);
        LocalRefreshJobStore.start(LocalRefreshJobStore.TYPE_ALL, METHOD_GLOBAL,
                tags.size() + jobs.size(), effectiveHost, searchIntervalMs);
        notifyListeners();
        if (tags.isEmpty() && jobs.isEmpty()) return;
        scanGlobalSources(tags, jobs);
    }

    private void scanGlobalSources(List<String> tags, List<QuickSearch> jobs) throws Throwable {
        // A host switch reloads both source cursors and discards both accumulators.
        while (!shouldStop()) {
            if (runUnifiedSource(tags, jobs)) return;
        }
    }

    private boolean runUnifiedSource(List<String> tags, List<QuickSearch> jobs) throws Throwable {
        String scanHost = effectiveHost;
        String contextKey = sourceContext(scanHost);
        LocalFollowRepository local = LocalFollowRepository.getInstance();
        Map<String, FeedCheckpoint> oldFollows = new LinkedHashMap<>();
        boolean hadFollows = false;
        for (String tag : tags) {
            CheckpointKey key = followCheckpoint(contextKey, tag);
            hadFollows |= !SubscriptionRepository.getInstance().readCheckpoint(key).current.isEmpty();
            ensureStateProvisional(LocalFollowRepository.SOURCE_FOLLOW, tag,
                    LocalFollowRepository.FIXED_CHINESE_SIGNATURE, key);
            oldFollows.put(tag, SubscriptionRepository.getInstance().readCheckpoint(key));
        }
        FeedBoundary followCursor = globalCursor(contextKey,
                LocalGlobalCursorStore.TYPE_FOLLOW, hadFollows, oldFollows);
        List<GlobalBookmark> exact = new ArrayList<>();
        List<QuickSearch> complex = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int completed = 0;
        boolean unsupportedBookmark = false;
        for (QuickSearch search : jobs) {
            BookmarkUpdatePolicy.Result policy = BookmarkUpdatePolicy.resolve(search);
            String key = Long.toString(search.id);
            local.prepareBookmarkSignature(key, policy.signature);
            if (!policy.supported) {
                local.markError(LocalFollowRepository.SOURCE_BOOKMARK, key,
                        policy.signature, policy.error);
                failures.add("书签 " + key + ": " + policy.error);
                unsupportedBookmark = true;
                completed++;
                continue;
            }
            BookmarkGlobalMatcher.Result matcher = BookmarkGlobalMatcher.compile(search);
            if (matcher.exact) exact.add(new GlobalBookmark(search, policy, matcher.matcher));
            else complex.add(search);
        }
        Map<Long, FeedCheckpoint> oldBookmarks = new LinkedHashMap<>();
        boolean hadBookmarks = hasExistingGlobalBookmarkCursor(exact, contextKey);
        initializeGlobalBookmarks(exact, contextKey, oldBookmarks);
        FeedBoundary bookmarkCursor = globalCursor(contextKey,
                LocalGlobalCursorStore.TYPE_BOOKMARK, hadBookmarks, oldBookmarks);
        List<QuickSearch> bridge = new ArrayList<>();
        exact = selectGloballyCovered(exact, oldBookmarks, bookmarkCursor, bridge);
        Map<Long, BookmarkGlobalMatcher> matchers = new LinkedHashMap<>();
        for (GlobalBookmark work : exact) matchers.put(work.search.id, work.matcher);
        UnifiedGlobalScan scan = new UnifiedGlobalScan(tags, matchers, followCursor, bookmarkCursor);
        LocalRefreshJobStore.phase(LocalRefreshJobStore.PHASE_GLOBAL_SCAN);
        ListUrlBuilder builder = new ListUrlBuilder();
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setKeyword(null);
        String url = withHost(builder.build(true), scanHost);
        int pageLimit = Settings.getGlobalScanPageLimit();
        final int initiallyCompleted = completed;
        boolean sameHost = scan.readPages(url, pageLimit, requestedUrl -> {
            GalleryListParser.Result page = fetchPage(requestedUrl, ListUrlBuilder.MODE_NORMAL);
            return new UnifiedGlobalScan.Page(page.galleryInfoList,
                    resolveNext(requestedUrl, page.nextHref), !scanHost.equals(effectiveHost));
        }, this::throwIfStopRequested, () -> {
            LocalRefreshJobStore.progress(initiallyCompleted, scan.pages, scan.galleries,
                    "", joinFailures(failures));
            updateNotification("书签与追更全局扫描：" + scan.pages + " 页 · "
                    + scan.galleries + " 本", scan.pages, pageLimit, true);
            notifyListeners();
        });
        if (!sameHost) return false;
        if ((!tags.isEmpty() || !exact.isEmpty()) && scan.top.isEmpty()) {
            throw new IllegalStateException("中文结果为空，无法建立更新基线");
        }
        // Commit only after the shared scan has completed without errors.
        WorkCounter totals = new WorkCounter();
        totals.pages = scan.pages;
        totals.galleries = scan.galleries;
        int total = tags.size() + jobs.size();
        int beforeFollowFailures = failures.size();
        unifiedProgress(completed, total, totals, failures);
        LocalRefreshJobStore.phase(LocalRefreshJobStore.PHASE_FOLLOW_QUEUE);
        for (String tag : tags) {
            runItem("追更 " + tag, () -> {
                if (!scan.coversFollow(oldFollows.get(tag).current)) checkTag(tag, totals);
                else if (followCursor.isEmpty()) local.establishBaseline(
                        LocalFollowRepository.SOURCE_FOLLOW, tag,
                        LocalFollowRepository.FIXED_CHINESE_SIGNATURE,
                        followCheckpoint(contextKey, tag), scan.top);
                else local.commitGlobalScan(LocalFollowRepository.SOURCE_FOLLOW, tag,
                        LocalFollowRepository.FIXED_CHINESE_SIGNATURE,
                        followCheckpoint(contextKey, tag), scan.top, scan.follows.get(tag));
            }, failures);
            unifiedProgress(++completed, total, totals, failures);
        }
        if (!tags.isEmpty() && failures.size() == beforeFollowFailures
                && scanHost.equals(effectiveHost)) {
            writeUnifiedCursor(contextKey, LocalGlobalCursorStore.TYPE_FOLLOW, scan.top, followCursor);
        }
        int beforeBookmarkFailures = failures.size();
        LocalRefreshJobStore.phase(LocalRefreshJobStore.PHASE_BOOKMARK_QUEUE);
        for (GlobalBookmark work : exact) {
            String key = Long.toString(work.search.id);
            runItem("书签 " + key, () -> {
                if (!scan.bookmarkCovered) checkBookmark(work.search,
                        scanHost.equals(effectiveHost) ? scan.top : null, totals);
                else if (bookmarkCursor.isEmpty()) local.establishBaseline(
                        LocalFollowRepository.SOURCE_BOOKMARK, key, work.policy.signature,
                        bookmarkCheckpoint(contextKey, key, work.policy.signature), scan.top);
                else local.commitGlobalScan(LocalFollowRepository.SOURCE_BOOKMARK, key,
                        work.policy.signature, bookmarkCheckpoint(contextKey, key, work.policy.signature),
                        scan.top, scan.bookmarks.get(work.search.id));
            }, failures);
            unifiedProgress(++completed, total, totals, failures);
        }
        for (QuickSearch search : bridge) {
            runItem("书签 " + search.id, () -> checkBookmark(search,
                    scanHost.equals(effectiveHost) ? scan.top : null, totals), failures);
            unifiedProgress(++completed, total, totals, failures);
        }
        for (QuickSearch search : complex) {
            runItem("书签 " + search.id, () -> checkBookmark(search, null, totals), failures);
            unifiedProgress(++completed, total, totals, failures);
        }
        if (!exact.isEmpty() && !unsupportedBookmark
                && failures.size() == beforeBookmarkFailures && scanHost.equals(effectiveHost)) {
            writeUnifiedCursor(contextKey, LocalGlobalCursorStore.TYPE_BOOKMARK,
                    scan.top, bookmarkCursor);
        }
        if (!failures.isEmpty()) throw new ItemsFailedException();
        return true;
    }

    private static FeedBoundary globalCursor(String context, String type,
                                             boolean hadExisting, Map<?, FeedCheckpoint> old) {
        FeedBoundary cursor = LocalGlobalCursorStore.read(context, type,
                LocalFollowRepository.FIXED_CHINESE_SIGNATURE);
        return cursor.isEmpty() && hadExisting ? LocalGlobalCursorStore.oldest(old) : cursor;
    }

    private void writeUnifiedCursor(String context, String type,
                                    FeedBoundary top, FeedBoundary old) throws InterruptedException {
        throwIfStopRequested();
        LocalGlobalCursorStore.write(context, type, LocalFollowRepository.FIXED_CHINESE_SIGNATURE,
                LocalBaselineResetPolicy.newer(top, old));
    }

    private interface UpdateItem { void run() throws Throwable; }

    private void runItem(String label, UpdateItem item, List<String> failures) throws Throwable {
        for (int attempt = 0; ; attempt++) {
            throwIfStopRequested();
            LocalRefreshJobStore.Snapshot progress = LocalRefreshJobStore.read();
            LocalRefreshJobStore.progress(progress.index, progress.pages, progress.galleries,
                    label, joinFailures(failures));
            notifyListeners();
            try {
                item.run();
                return;
            } catch (Exception error) {
                throwIfStopRequested();
                if (isRetryable(error) && attempt < 2) {
                    Thread.sleep((attempt + 1) * 1600L);
                    continue;
                }
                failures.add(label + ": " + safeMessage(error).replace('\n', ' '));
                return;
            }
        }
    }

    private void unifiedProgress(int completed, int total, WorkCounter work, List<String> failures) {
        LocalRefreshJobStore.progress(completed, work.pages, work.galleries, "", joinFailures(failures));
        updateNotification("书签与追更：" + completed + "/" + total, completed, total, false);
        notifyListeners();
    }

    private static final class ItemsFailedException extends Exception {}

    private void runFollows(String requestedMethod) throws Throwable {
        List<String> tags = LocalFollowRepository.getInstance().getAll();
        String host = preferredHost();
        effectiveHost = host;
        String method = requestedMethod == null ? METHOD_GLOBAL : requestedMethod;
        LocalRefreshJobStore.Snapshot previous = LocalRefreshJobStore.read();
        searchIntervalMs = intervalForJob(previous,
                LocalRefreshJobStore.TYPE_FOLLOW, method);
        int resumeIndex = LocalRefreshJobStore.startQueue(LocalRefreshJobStore.TYPE_FOLLOW,
                method, tags, sourceContext(host), host, searchIntervalMs);
        notifyListeners();
        if (tags.isEmpty()) throw new IllegalStateException("追更列表为空");
        if (METHOD_GLOBAL.equals(method)) {
            scanGlobalSources(tags, Collections.emptyList());
        } else {
            runTagQueue(tags, resumeIndex);
        }
    }

    private void runTagQueue(List<String> tags, int startIndex) throws Throwable {
        LocalRefreshJobStore.phase(LocalRefreshJobStore.PHASE_FOLLOW_QUEUE);
        List<String> failures = new ArrayList<>();
        WorkCounter work = new WorkCounter();
        LocalRefreshJobStore.progress(startIndex, 0, 0, "", "");
        for (int i = startIndex; i < tags.size(); i++) {
            String tag = tags.get(i);
            int previousFailures = failures.size();
            runItem(tag, () -> checkTag(tag, work), failures);
            if (failures.size() > previousFailures) {
                LocalFollowRepository.getInstance().markError(LocalFollowRepository.SOURCE_FOLLOW,
                        tag, LocalFollowRepository.FIXED_CHINESE_SIGNATURE, failures.get(previousFailures));
            }
            LocalRefreshJobStore.progress(i + 1, work.pages, work.galleries, tag, joinFailures(failures));
            updateNotification("逐标签：" + (i + 1) + "/" + tags.size(),
                    i + 1, tags.size(), false);
            notifyListeners();
        }
        if (!failures.isEmpty()) throw new ItemsFailedException();
    }

    private void checkTag(String tag, WorkCounter work) throws Throwable {
        ListUrlBuilder builder = new ListUrlBuilder();
        builder.set(tag);
        String url = withHost(builder.build(true), effectiveHost);
        GalleryListParser.Result result = fetchPage(url, ListUrlBuilder.MODE_TAG);
        work.add(result);
        throwIfStopRequested();
        String contextKey = sourceContext(effectiveHost);
        CheckpointKey checkpoint = followCheckpoint(contextKey, tag);
        ensureStateProvisional(LocalFollowRepository.SOURCE_FOLLOW, tag,
                LocalFollowRepository.FIXED_CHINESE_SIGNATURE, checkpoint);
        if (result.galleryInfoList.isEmpty()) {
            LocalFollowRepository.getInstance().markCheckedWithoutChanges(
                    LocalFollowRepository.SOURCE_FOLLOW, tag,
                    LocalFollowRepository.FIXED_CHINESE_SIGNATURE);
            return;
        }
        LocalFollowRepository.getInstance().commitPage(
                LocalFollowRepository.SOURCE_FOLLOW, tag,
                LocalFollowRepository.FIXED_CHINESE_SIGNATURE,
                checkpoint, result.galleryInfoList, false,
                resolveNext(url, result.nextHref) == null);
    }

    private void runBookmarks(@Nullable Long onlyId, String requestedMethod) throws Throwable {
        List<QuickSearch> all = EhDB.getAllQuickSearch();
        List<QuickSearch> jobs = new ArrayList<>();
        for (QuickSearch search : all) {
            if (onlyId == null || (search.id != null && search.id.equals(onlyId))) jobs.add(search);
        }
        String host = preferredHost();
        effectiveHost = host;
        String method = onlyId != null ? "SINGLE:" + onlyId
                : METHOD_GLOBAL.equals(requestedMethod) ? METHOD_GLOBAL : METHOD_FIRST_PAGE;
        LocalRefreshJobStore.Snapshot previous = LocalRefreshJobStore.read();
        searchIntervalMs = intervalForJob(previous,
                LocalRefreshJobStore.TYPE_BOOKMARK, method);
        List<String> keys = new ArrayList<>();
        for (QuickSearch search : jobs) {
            keys.add(search.id + ":" + BookmarkUpdatePolicy.querySignature(search));
        }
        int resumeIndex = LocalRefreshJobStore.startQueue(LocalRefreshJobStore.TYPE_BOOKMARK,
                method, keys, sourceContext(host), host, searchIntervalMs);
        notifyListeners();
        if (jobs.isEmpty()) throw new IllegalStateException("书签列表为空");
        if (onlyId == null && METHOD_GLOBAL.equals(method)) {
            scanGlobalSources(Collections.emptyList(), jobs);
        } else {
            runBookmarkQueue(jobs, resumeIndex);
        }
    }

    private void initializeGlobalBookmarks(
            List<GlobalBookmark> jobs, String contextKey,
            Map<Long, FeedCheckpoint> checkpoints) {
        for (GlobalBookmark work : jobs) {
            String key = Long.toString(work.search.id);
            CheckpointKey checkpoint =
                    bookmarkCheckpoint(contextKey, key, work.policy.signature);
            ensureStateProvisional(LocalFollowRepository.SOURCE_BOOKMARK, key,
                    work.policy.signature, checkpoint);
            checkpoints.put(work.search.id,
                    SubscriptionRepository.getInstance().readCheckpoint(checkpoint));
        }
    }

    private static List<GlobalBookmark> selectGloballyCovered(
            List<GlobalBookmark> jobs, Map<Long, FeedCheckpoint> checkpoints,
            FeedBoundary globalCursor, List<QuickSearch> bridgeFallback) {
        if (globalCursor == null || globalCursor.isEmpty()) {
            return jobs;
        }
        List<GlobalBookmark> covered = new ArrayList<>();
        for (GlobalBookmark work : jobs) {
            FeedCheckpoint checkpoint = checkpoints.get(work.search.id);
            if (checkpoint != null && GlobalScanPolicy.requiresItemBridge(
                    checkpoint.current, globalCursor)) {
                bridgeFallback.add(work.search);
            } else {
                covered.add(work);
            }
        }
        return covered;
    }

    private boolean hasExistingGlobalBookmarkCursor(
            List<GlobalBookmark> jobs, String contextKey) {
        for (GlobalBookmark work : jobs) {
            String key = Long.toString(work.search.id);
            if (!SubscriptionRepository.getInstance().readCheckpoint(
                    bookmarkCheckpoint(contextKey, key, work.policy.signature))
                    .current.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void runBookmarkQueue(List<QuickSearch> jobs, int startIndex) throws Throwable {
        LocalRefreshJobStore.phase(LocalRefreshJobStore.PHASE_BOOKMARK_QUEUE);
        WorkCounter work = new WorkCounter();
        List<String> failures = new ArrayList<>();
        LocalRefreshJobStore.progress(startIndex, 0, 0, "", "");
        for (int i = startIndex; i < jobs.size(); i++) {
            QuickSearch search = jobs.get(i);
            String label = search.name == null ? Long.toString(search.id) : search.name;
            runItem(label, () -> checkBookmark(search, null, work), failures);
            LocalRefreshJobStore.progress(i + 1, work.pages, work.galleries,
                    label, joinFailures(failures));
            updateNotification("逐书签：" + (i + 1) + "/" + jobs.size(),
                    i + 1, jobs.size(), false);
            notifyListeners();
        }
        if (!failures.isEmpty()) throw new ItemsFailedException();
    }

    private void checkBookmark(
            QuickSearch search, @Nullable FeedBoundary synchronizedTop,
            WorkCounter work) throws Throwable {
        BookmarkUpdatePolicy.Result policy = BookmarkUpdatePolicy.resolve(search);
        String key = Long.toString(search.id);
        LocalFollowRepository local = LocalFollowRepository.getInstance();
        local.prepareBookmarkSignature(key, policy.signature);
        if (!policy.supported) {
            local.markError(LocalFollowRepository.SOURCE_BOOKMARK, key,
                    policy.signature, policy.error);
            throw new IllegalArgumentException(policy.error);
        }
        String url = withHost(policy.url, effectiveHost);
        Set<String> visited = new HashSet<>();
        BookmarkScanAccumulator accumulator = null;
        CheckpointKey checkpoint = null;
        while (url != null) {
            String requestedUrl = withHost(url, effectiveHost);
            if (!visited.add(requestedUrl)) {
                throw new IllegalStateException("书签分页地址重复");
            }
            GalleryListParser.Result page = fetchPage(requestedUrl, search.mode);
            work.add(page);
            recordBookmarkPageProgress(work, search);
            throwIfStopRequested();
            if (!requestedUrl.startsWith(effectiveHost)) {
                accumulator = null;
                checkpoint = null;
                synchronizedTop = null;
                visited.clear();
                url = withHost(policy.url, effectiveHost);
                continue;
            }
            if (accumulator == null) {
                checkpoint = bookmarkCheckpoint(
                        sourceContext(effectiveHost), key, policy.signature);
                ensureStateProvisional(LocalFollowRepository.SOURCE_BOOKMARK, key,
                        policy.signature, checkpoint);
                FeedCheckpoint old = SubscriptionRepository.getInstance()
                        .readCheckpoint(checkpoint);
                accumulator = new BookmarkScanAccumulator(old.current);
            }
            String next = resolveNext(withHost(requestedUrl, effectiveHost), page.nextHref);
            if (next != null && visited.contains(withHost(next, effectiveHost))) {
                throw new IllegalStateException("书签分页地址重复");
            }
            if (!accumulator.addPage(page.galleryInfoList, next != null)) break;
            url = next;
        }
        throwIfStopRequested();
        if (accumulator == null || checkpoint == null) {
            throw new IllegalStateException("书签扫描未开始");
        }
        local.commitBookmarkScan(key, policy.signature, checkpoint,
                synchronizedTop, accumulator.finish());
    }

    private void recordBookmarkPageProgress(WorkCounter work, QuickSearch search) {
        LocalRefreshJobStore.Snapshot snapshot = LocalRefreshJobStore.read();
        int index = snapshot == null ? 0 : snapshot.index;
        String failures = snapshot == null ? "" : snapshot.failures;
        String current = search.name == null ? Long.toString(search.id) : search.name;
        LocalRefreshJobStore.progress(index, work.pages, work.galleries, current, failures);
        notifyListeners();
    }

    private static final class WorkCounter {
        int pages;
        int galleries;

        void add(GalleryListParser.Result page) {
            pages++;
            if (page != null && page.galleryInfoList != null) {
                galleries += page.galleryInfoList.size();
            }
        }
    }

    private GalleryListParser.Result fetchPage(String url, int mode) throws Throwable {
        waitForSearchSlot();
        OkHttpClient client = EhApplication.getOkHttpClient(this);
        try {
            return EhEngine.getGalleryListForUpdate(
                    null, client, url, mode, this::observeCall);
        } catch (Throwable exError) {
            if (shouldStop()) throw exError;
            if (url.startsWith(EhUrl.HOST_EX)) {
                String fallback = withHost(url, EhUrl.HOST_E);
                waitForSearchSlot();
                GalleryListParser.Result result =
                        EhEngine.getGalleryListForUpdate(
                                null, client, fallback, mode, this::observeCall);
                effectiveHost = EhUrl.HOST_E;
                LocalRefreshJobStore.updateHost(effectiveHost);
                return result;
            }
            throw exError;
        }
    }

    private void waitForSearchSlot() throws InterruptedException {
        long wait = searchIntervalMs - (System.currentTimeMillis() - lastSearchAt);
        if (wait > 0) Thread.sleep(wait);
        lastSearchAt = System.currentTimeMillis();
    }

    private static int intervalForJob(@Nullable LocalRefreshJobStore.Snapshot previous,
                                      String type, String method) {
        if (previous != null
                && LocalRefreshJobStore.STATUS_PAUSED.equals(previous.status)
                && type.equals(previous.type) && method.equals(previous.method)
                && previous.requestIntervalMs >= SearchIntervalPolicy.MIN_MS
                && previous.requestIntervalMs <= SearchIntervalPolicy.MAX_MS) {
            return previous.requestIntervalMs;
        }
        return Settings.getLocalUpdateSearchIntervalMs();
    }

    private boolean shouldStop() {
        return pauseRequested || cancelRequested || Thread.currentThread().isInterrupted();
    }

    private void throwIfStopRequested() throws InterruptedException {
        if (shouldStop()) throw new InterruptedException("update check stopped");
    }

    private void observeCall(@Nullable Call call) {
        activeCall.set(call);
        if (call != null && shouldStop()) call.cancel();
    }

    private void interruptActiveWork() {
        Call call = activeCall.getAndSet(null);
        if (call != null) call.cancel();
        Thread current = worker;
        if (current != null) current.interrupt();
    }

    private String preferredHost() {
        EhCookieStore store = EhApplication.getEhCookieStore(this);
        return IgneousUtils.isUsableIgneous(store.getIgneous()) ? EhUrl.HOST_EX : EhUrl.HOST_E;
    }

    private String sourceContext(String host) {
        return SubscriptionRepository.getInstance().getAccountKey() + "|" + host;
    }

    private static CheckpointKey followCheckpoint(String context, String tag) {
        return new CheckpointKey(context, LocalFollowRepository.CHECKPOINT_FOLLOW, tag,
                LocalFollowRepository.FIXED_CHINESE_SIGNATURE);
    }

    private static CheckpointKey bookmarkCheckpoint(
            String context, String key, String signature) {
        return new CheckpointKey(context, LocalFollowRepository.CHECKPOINT_BOOKMARK,
                key, signature);
    }

    private static void ensureStateProvisional(String sourceType, String sourceKey,
                                               String signature, CheckpointKey checkpoint) {
        LocalFollowRepository repository = LocalFollowRepository.getInstance();
        TagUpdateState state = repository.readState(sourceType, sourceKey, signature);
        if (state.checkedAt > 0
                && SubscriptionRepository.getInstance().readCheckpoint(checkpoint)
                .current.isEmpty()) {
            repository.ensureProvisionalCheckpoint(sourceType, sourceKey, signature,
                    checkpoint, state.checkedAt);
        }
    }

    private static String resolveNext(String current, String href) {
        if (href == null || href.isEmpty()) return null;
        HttpUrl base = HttpUrl.parse(current);
        HttpUrl next = base == null ? null : base.resolve(href);
        if (next == null) throw new IllegalStateException(GetText.getString(R.string.error_invalid_url));
        return next.toString();
    }

    private static String withHost(String url, String host) {
        if (url == null) return null;
        if (url.startsWith(EhUrl.HOST_EX)) return host + url.substring(EhUrl.HOST_EX.length());
        if (url.startsWith(EhUrl.HOST_E)) return host + url.substring(EhUrl.HOST_E.length());
        return url;
    }

    private static String joinFailures(List<String> failures) {
        StringBuilder out = new StringBuilder();
        for (String failure : failures) {
            if (out.length() > 0) out.append('\n');
            out.append(failure);
        }
        return out.toString();
    }

    private static String appendFailure(String failures, String failure) {
        if (failures == null || failures.isEmpty()) return failure;
        return failures + '\n' + failure;
    }

    private static String terminalSummary(@Nullable LocalRefreshJobStore.Snapshot snapshot) {
        if (snapshot == null) return "更新检查已结束";
        int failed = snapshot.failures == null || snapshot.failures.isEmpty()
                ? 0 : snapshot.failures.split("\\n").length;
        int success = Math.max(0, snapshot.index - failed);
        int capped;
        if ("FOLLOW".equals(snapshot.type)) {
            capped = LocalFollowRepository.getInstance().countCapped(
                    LocalFollowRepository.SOURCE_FOLLOW);
        } else if ("BOOKMARK".equals(snapshot.type)) {
            capped = LocalFollowRepository.getInstance().countCapped(
                    LocalFollowRepository.SOURCE_BOOKMARK);
        } else {
            capped = LocalFollowRepository.getInstance().countCapped(
                    LocalFollowRepository.SOURCE_FOLLOW)
                    + LocalFollowRepository.getInstance().countCapped(
                    LocalFollowRepository.SOURCE_BOOKMARK);
        }
        String state = LocalRefreshJobStore.STATUS_SUCCESS.equals(snapshot.status) ? "完成"
                : LocalRefreshJobStore.STATUS_PAUSED.equals(snapshot.status) ? "已暂停"
                : LocalRefreshJobStore.STATUS_CANCELLED.equals(snapshot.status) ? "已取消"
                : "失败";
        if ("BASELINE".equals(snapshot.type)) {
            return state + " · 成功细化 " + success + " · 时间基线 " + failed;
        }
        return state + " · 成功 " + success + " · 20+ " + capped + " · 失败 " + failed;
    }

    private static String safeMessage(Throwable error) {
        if (error instanceof UnifiedGlobalScan.IncompleteBaselineException) {
            return GetText.getString(R.string.local_update_incomplete_baseline);
        }
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static final class GlobalBookmark {
        final QuickSearch search;
        final BookmarkUpdatePolicy.Result policy;
        final BookmarkGlobalMatcher matcher;

        GlobalBookmark(QuickSearch search, BookmarkUpdatePolicy.Result policy,
                       BookmarkGlobalMatcher matcher) {
            this.search = search;
            this.policy = policy;
            this.matcher = matcher;
        }
    }

    private static boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IOException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static void notifyListeners() {
        LocalRefreshJobStore.Snapshot snapshot = LocalRefreshJobStore.read();
        for (WeakReference<Listener> reference : LISTENERS) {
            Listener listener = reference.get();
            if (listener == null) {
                LISTENERS.remove(reference);
            } else {
                listener.onLocalUpdateProgress(snapshot);
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "追更与书签更新", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification notification(String text, int progress, int total, boolean indeterminate) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 1,
                new Intent(this, LocalUpdateService.class).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 2,
                new Intent(this, LocalUpdateService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_subscriptions_24)
                .setContentTitle("EhViewer 更新检查")
                .setContentText(text)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(GATE.isUpdating());
        if (GATE.isUpdating()) {
            builder.setProgress(total, progress, indeterminate)
                    .addAction(0, "暂停", pause)
                    .addAction(0, "取消", cancel);
        }
        return builder.build();
    }

    private void updateNotification(String text, int progress, int total, boolean indeterminate) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification(text, progress, total, indeterminate));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
