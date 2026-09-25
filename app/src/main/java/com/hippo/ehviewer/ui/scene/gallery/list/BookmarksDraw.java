package com.hippo.ehviewer.ui.scene.gallery.list;

import android.annotation.SuppressLint;
import android.content.Context;
import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.dao.QuickSearch;
import com.hippo.ehviewer.util.TagTranslationUtil;
import com.hippo.ehviewer.subscription.CheckpointKey;
import com.hippo.ehviewer.subscription.FeedCheckpoint;
import com.hippo.ehviewer.subscription.QuerySignatureFactory;
import com.hippo.ehviewer.subscription.SearchQueryPolicy;
import com.hippo.ehviewer.subscription.SubscriptionRepository;
import com.hippo.ehviewer.subscription.LocalFollowRepository;
import com.hippo.ehviewer.subscription.LocalRefreshJobStore;
import com.hippo.ehviewer.subscription.LocalUpdateService;
import com.hippo.ehviewer.subscription.BookmarkUpdatePolicy;
import com.hippo.ehviewer.subscription.TagUpdateState;
import com.hippo.ehviewer.subscription.UpdateBadgeFormatter;
import androidx.appcompat.app.AlertDialog;
import com.hippo.scene.Announcer;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ViewUtils;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;

public class BookmarksDraw {

    private final Context context;
    private final LayoutInflater inflater;

    private final EhTagDatabase ehTags;

    private static final String QUICK_SEARCH_DRAW_SCROLL_Y = "QuickSearchDrawScrollY";
    private static final String QUICK_SEARCH_DRAW_SCROLL_POS = "QuickSearchDrawScrollPos";

    final private EhApplication ehApplication;

    private ListView listView;
    private Toolbar toolbar;
    private TextView tip;
    private LocalUpdateToolbarController updateToolbar;
    private List<QuickSearch> bookmarks;
    private final Map<Long, String> updates = new HashMap<>();
    private final Map<Long, Integer> originalOrder = new HashMap<>();
    private ArrayAdapter<QuickSearch> adapter;
    private final LocalUpdateService.Listener updateListener = this::onLocalUpdateProgress;

    public BookmarksDraw(@NonNull Context context, LayoutInflater inflater, EhTagDatabase ehTags) {
        this.context = context;
        this.inflater = inflater;
        if (ehTags == null) {
            ehTags = EhTagDatabase.getInstance(context);
        }
        this.ehTags = ehTags;
        ehApplication = (EhApplication) context.getApplicationContext();
    }

    @SuppressLint("NonConstantResourceId")
    public View onCreate(GalleryListScene scene) {
        View bookmarksView = inflater.inflate(R.layout.bookmarks_draw, null, false);

        toolbar = (Toolbar) ViewUtils.$$(bookmarksView, R.id.toolbar);
        tip = (TextView) ViewUtils.$$(bookmarksView, R.id.tip);
        listView = (ListView) ViewUtils.$$(bookmarksView, R.id.list_view);


        AssertUtils.assertNotNull(context);

        List<QuickSearch> quickSearchList = EhDB.getAllQuickSearch();
        //汉化标签
        final boolean judge = Settings.getShowTagTranslations();
        if (judge && !quickSearchList.isEmpty()) {
            for (int i = 0; i < quickSearchList.size(); i++) {
                String name = quickSearchList.get(i).getName();
                //重设标签名称,并跳过已翻译的标签
                if (name != null && 2 == name.split(":").length) {
                    quickSearchList.get(i).setName(TagTranslationUtil.getTagCN(name.split(":"), ehTags));
                    EhDB.updateQuickSearch(quickSearchList.get(i));
                }
            }
        } else if (!judge && !quickSearchList.isEmpty()) {
            for (int i = 0; i < quickSearchList.size(); i++) {
                String name = quickSearchList.get(i).getName();
                //重设标签名称,并跳过未翻译的标签
                if (null != name && 1 == name.split(":").length) {
                    quickSearchList.get(i).setName(quickSearchList.get(i).getKeyword());
                    EhDB.updateQuickSearch(quickSearchList.get(i));
                }
            }
        }


        bookmarks = quickSearchList;
        updates.clear();
        originalOrder.clear();
        for (int i = 0; i < bookmarks.size(); i++) {
            originalOrder.put(bookmarks.get(i).getId(), i);
        }
        adapter = new ArrayAdapter<QuickSearch>(
                context, R.layout.item_update_badge_list, bookmarks) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView == null
                        ? inflater.inflate(R.layout.item_update_badge_list, parent, false)
                        : convertView;
                TextView count = view.findViewById(R.id.update_badge_count);
                TextView indicator = view.findViewById(R.id.update_badge_indicator);
                TextView label = view.findViewById(R.id.update_badge_label);
                TextView detail = view.findViewById(R.id.update_badge_detail);
                QuickSearch item = getItem(position);
                String badge = item == null ? null : updates.get(item.getId());
                UpdateBadgeFormatter.bind(context, count, indicator, label, detail,
                        item == null ? "" : item.name, badge,
                        item == null ? "" : item.keyword);
                return view;
            }
        };
        listView.setAdapter(adapter);
        loadUpdateBadges();
        //快速搜索点击tag事件监听
        listView.setOnItemClickListener((parent, view1, position, id) -> {
            if (null == scene.mHelper || null == scene.mUrlBuilder) {
                return;
            }

            scene.setQuickSearchFeedSource(bookmarks.get(position));
            scene.mUrlBuilder.set(bookmarks.get(position));
            scene.mUrlBuilder.setPageIndex(0);
            scene.onUpdateUrlBuilder();
            scene.mHelper.refresh();
            scene.setState(GalleryListScene.STATE_NORMAL);
            scene.closeDrawer(Gravity.RIGHT);
        });
        listView.setOnItemLongClickListener((parent, view1, position, id) -> {
            QuickSearch search = bookmarks.get(position);
            new AlertDialog.Builder(context)
                    .setCustomTitle(createActionTitle(search))
                    .setItems(new String[]{
                                    context.getString(R.string.bookmark_check_this),
                                    context.getString(R.string.delete_quick_search_title)},
                            (dialog, which) -> {
                                if (which == 1) {
                                    confirmDelete(search);
                                    return;
                                }
                                if (LocalUpdateTaskDialog.isOpenTask(
                                        LocalRefreshJobStore.read())) {
                                    showJobDetails();
                                    return;
                                }
                                requestNotificationPermission();
                                if (!LocalUpdateService.startBookmark(context, search.getId())) {
                                    showJobDetails();
                                }
                            })
                    .show();
            return true;
        });
        listView.setOnScrollListener(new ScrollListener());

        tip.setText(R.string.quick_search_tip);
        toolbar.setLogo(R.drawable.ic_baseline_bookmarks_24);
        toolbar.setTitle(R.string.quick_search);
        toolbar.inflateMenu(R.menu.drawer_gallery_list);
        updateToolbar = new LocalUpdateToolbarController(context, inflater, toolbar,
                LocalRefreshJobStore.TYPE_BOOKMARK, this::requestBookmarkRefresh,
                this::showJobDetails);
        LocalUpdateService.addListener(updateListener);
        onLocalUpdateProgress(LocalRefreshJobStore.read());
        toolbar.setOnMenuItemClickListener(item -> {  //点击增加快速搜索按钮触发
            int id = item.getItemId();
            switch (id) {
                case R.id.action_refresh:
                    requestBookmarkRefresh();
                    break;
                case R.id.action_add:
                    if (Settings.getQuickSearchTip()) {
                        scene.showQuickSearchTipDialog(bookmarks, adapter, listView, tip);
                    } else {
                        scene.showAddQuickSearchDialog(bookmarks, adapter, listView, tip);
                    }
                    break;
                case R.id.action_settings:
                    scene.startScene(new Announcer(QuickSearchScene.class));
                    break;
            }
            return true;
        });

        updateEmptyState();
        if (!bookmarks.isEmpty()) {
            resume();
        }

        toolbar.setOnClickListener(l -> {
            if (LocalUpdateTaskDialog.isOpenTask(LocalRefreshJobStore.read())) {
                showJobDetails();
            } else {
                scene.drawPager.setCurrentItem(1);
            }
        });
        toolbar.setOnLongClickListener(view -> {
            showJobDetails();
            return true;
        });


        return bookmarksView;
    }

    private void loadUpdateBadges() {
        loadUpdateBadges(true);
    }

    private void loadUpdateBadges(boolean sort) {
        if (bookmarks == null || adapter == null) return;
        SubscriptionRepository repository = SubscriptionRepository.getInstance();
        repository.execute(() -> {
            LocalFollowRepository local = LocalFollowRepository.getInstance();
            for (QuickSearch item : bookmarks) {
                if (item.getId() == null) continue;
                BookmarkUpdatePolicy.Result policy = BookmarkUpdatePolicy.resolve(item);
                if (!policy.supported) {
                    updates.put(item.getId(), context.getString(
                            "language conflict".equals(policy.error)
                                    ? R.string.update_language_conflict
                                    : R.string.update_mode_unsupported));
                    continue;
                }
                TagUpdateState state = local.readState(LocalFollowRepository.SOURCE_BOOKMARK,
                        Long.toString(item.getId()), policy.signature);
                updates.put(item.getId(), state.checkedAt == 0 ? null : state.displayCount());
            }
            if (sort) {
                bookmarks.sort(Comparator
                        .comparingInt((QuickSearch item) -> badgeValue(updates.get(item.getId())))
                        .reversed()
                        .thenComparingInt(item -> originalOrder.getOrDefault(item.getId(),
                                Integer.MAX_VALUE)));
            }
            if (listView != null) listView.post(adapter::notifyDataSetChanged);
        });
    }

    public void refreshUpdateBadges(boolean sort) {
        loadUpdateBadges(sort);
    }

    private static int badgeValue(String badge) {
        if (badge == null || "!".equals(badge)) return 0;
        if ("20+".equals(badge)) return 21;
        try { return Integer.parseInt(badge); } catch (NumberFormatException ignored) { return 0; }
    }

    private void showJobDetails() {
        LocalRefreshJobStore.Snapshot snapshot = LocalRefreshJobStore.read();
        if (snapshot == null) return;
        LocalUpdateTaskDialog.show(context, snapshot, true);
    }

    private void confirmDelete(QuickSearch search) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.delete_quick_search_title)
                .setMessage(context.getString(R.string.delete_quick_search_message, search.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> deleteBookmark(search))
                .show();
    }

    private View createActionTitle(QuickSearch search) {
        View title = inflater.inflate(R.layout.dialog_quick_search_action_title, null, false);
        TextView name = title.findViewById(R.id.quick_search_action_title);
        TextView detail = title.findViewById(R.id.quick_search_action_detail);
        name.setText(search.name);
        if (search.keyword == null || search.keyword.isEmpty()) {
            detail.setVisibility(View.GONE);
        } else {
            detail.setText(search.keyword);
            detail.setVisibility(View.VISIBLE);
        }
        return title;
    }

    private void deleteBookmark(QuickSearch search) {
        QuickSearchDeleteHelper.delete(search);
        bookmarks.remove(search);
        if (search.getId() != null) {
            updates.remove(search.getId());
            originalOrder.remove(search.getId());
        }
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (tip == null || listView == null || bookmarks == null) return;
        boolean empty = bookmarks.isEmpty();
        tip.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void requestBookmarkRefresh() {
        LocalRefreshJobStore.Snapshot snapshot = LocalRefreshJobStore.read();
        if (LocalUpdateService.isActive()
                || LocalUpdateTaskDialog.isOpenTask(snapshot)) {
            showJobDetails();
            return;
        }
        long lastSuccess = LocalRefreshJobStore.lastBookmarkSuccess();
        boolean recommendGlobal = lastSuccess == 0
                || System.currentTimeMillis() - lastSuccess <= 5L * 24L * 60L * 60L * 1000L;
        LocalUpdateStartDialog.showBookmarks(context, recommendGlobal,
                method -> {
                    requestNotificationPermission();
                    if (!LocalUpdateService.startBookmarks(context, method)) showJobDetails();
                });
    }

    private void onLocalUpdateProgress(LocalRefreshJobStore.Snapshot snapshot) {
        if (listView == null) return;
        listView.post(() -> {
            if (updateToolbar != null) updateToolbar.render(snapshot);
            boolean terminal = snapshot != null
                    && !LocalRefreshJobStore.STATUS_RUNNING.equals(snapshot.status)
                    && !LocalRefreshJobStore.STATUS_PAUSED.equals(snapshot.status);
            if (terminal) {
                if (LocalRefreshJobStore.TYPE_BOOKMARK.equals(snapshot.type)
                        || LocalRefreshJobStore.TYPE_ALL.equals(snapshot.type)) {
                    loadUpdateBadges(true);
                } else if (LocalRefreshJobStore.TYPE_BASELINE.equals(snapshot.type)) {
                    loadUpdateBadges(false);
                }
            }
        });
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && context instanceof Activity) {
            Activity activity = (Activity) context;
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 8043);
            }
        }
    }

    public void resume() {
        Object scrollY = ehApplication.getTempCache(QUICK_SEARCH_DRAW_SCROLL_Y);
        Object pos = ehApplication.getTempCache(QUICK_SEARCH_DRAW_SCROLL_POS);
        if (scrollY != null && pos != null) {
            listView.setSelection((Integer) pos);
        }
    }

    private class ScrollListener implements AbsListView.OnScrollListener {
        public ScrollListener() {
            super();
        }

        @Override
        public void onScrollStateChanged(AbsListView view, int scrollState) {
            View item = view.getChildAt(0);
            if (item == null) {
                return;
            }
            int firstPos = view.getFirstVisiblePosition();
            int top = item.getTop();
            int scrollY = firstPos * item.getHeight() - top;
            ehApplication.putTempCache(QUICK_SEARCH_DRAW_SCROLL_Y, scrollY);
            ehApplication.putTempCache(QUICK_SEARCH_DRAW_SCROLL_POS, firstPos);
        }

        @Override
        public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        }
    }
}
