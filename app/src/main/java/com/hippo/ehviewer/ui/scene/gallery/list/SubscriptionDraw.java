package com.hippo.ehviewer.ui.scene.gallery.list;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.viewpager.widget.ViewPager;

import com.hippo.app.EditTextDialogBuilder;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.callBack.SubscriptionCallback;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.userTag.TagPushParam;
import com.hippo.ehviewer.client.data.userTag.UserTag;
import com.hippo.ehviewer.client.data.userTag.UserTagList;
import com.hippo.ehviewer.client.exception.EhException;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.scene.EhCallback;
import com.hippo.ehviewer.subscription.QuerySignatureFactory;
import com.hippo.ehviewer.subscription.SearchQueryPolicy;
import com.hippo.ehviewer.subscription.SubscriptionRepository;
import com.hippo.ehviewer.subscription.SubscriptionRefreshStatus;
import com.hippo.ehviewer.subscription.SubscriptionScanProgress;
import com.hippo.ehviewer.subscription.SubscriptionSnapshot;
import com.hippo.ehviewer.subscription.TagUpdateState;
import com.hippo.ehviewer.subscription.LocalFollowRepository;
import com.hippo.ehviewer.subscription.LocalRefreshJobStore;
import com.hippo.ehviewer.subscription.LocalUpdateService;
import com.hippo.scene.Announcer;
import com.hippo.scene.SceneFragment;
import com.hippo.widget.ProgressView;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ViewUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.Comparator;

import static com.hippo.ehviewer.Settings.*;

public class SubscriptionDraw implements LocalUpdateService.Listener {

    private static final String SUBSCRIPTION_DRAW_SCROLL_Y = "SubscriptionDrawScrollY";
    private static final String SUBSCRIPTION_DRAW_POS = "SubscriptionDrawPos";

    private final Context context;
    private final LayoutInflater inflater;

    final private EhApplication ehApplication;

    private ListView listView;
    private ProgressView progressView;
    private FrameLayout frameLayout;
    private TextView textView;
    private final EhClient ehClient;
    protected MainActivity activity;
    private SubscriptionCallback callback;

    private final String mTag;

    boolean needLoad = true;

    private UserTagList userTagList;

    private final EhTagDatabase ehTags;

    private String tagName;
    private SubscriptionItemAdapter adapter;
    private Toolbar toolbar;
    private LocalUpdateToolbarController updateToolbar;



    public SubscriptionDraw(@NonNull Context context, LayoutInflater inflater, EhClient ehClient, String mTag, EhTagDatabase ehTags) {
        this.context = context;
        this.inflater = inflater;
        this.ehClient = ehClient;
        this.mTag = mTag;
        if (ehTags == null) {
            this.ehTags = EhTagDatabase.getInstance(context);
        } else {
            this.ehTags = ehTags;
        }
        ehApplication = (EhApplication) context.getApplicationContext();
    }

    @SuppressLint("NonConstantResourceId")
    public View onCreate(ViewPager drawPager, MainActivity activity, SubscriptionCallback callback) {
        this.activity = activity;
        this.callback = callback;
        LocalUpdateService.addListener(this);
        @SuppressLint("InflateParams")
        View subscriptionView = inflater.inflate(R.layout.subscription_draw, null, false);

        progressView = (ProgressView) ViewUtils.$$(subscriptionView, R.id.tag_list_view_progress);
        frameLayout = (FrameLayout) ViewUtils.$$(subscriptionView, R.id.tag_list_parent);
        textView = (TextView) ViewUtils.$$(subscriptionView, R.id.not_login_text);
        frameLayout.setVisibility(View.GONE);

        toolbar = (Toolbar) ViewUtils.$$(subscriptionView, R.id.toolbar);
        final TextView tip = (TextView) ViewUtils.$$(subscriptionView, R.id.tip);
        listView = (ListView) ViewUtils.$$(subscriptionView, R.id.list_view);
        AssertUtils.assertNotNull(context);

        tip.setText(R.string.local_follow_empty);
        toolbar.setLogo(R.drawable.ic_baseline_subscriptions_24);
        toolbar.setTitle(R.string.local_follow);
        toolbar.inflateMenu(R.menu.drawer_gallery_list);
        toolbar.getMenu().findItem(R.id.action_add).setVisible(false);
        updateToolbar = new LocalUpdateToolbarController(context, inflater, toolbar,
                LocalRefreshJobStore.TYPE_FOLLOW, callback::onSubscriptionRefresh,
                this::showRefreshDetails);
        restoreRefreshStatus();
        toolbar.setOnMenuItemClickListener(item -> {  //点击增加快速搜索按钮触发
            int id = item.getItemId();
            switch (id) {
                case R.id.action_refresh:
                    callback.onSubscriptionRefresh();
                    break;
                case R.id.action_settings:
                    seeDetailPage();
                    break;
            }
            return true;
        });

        toolbar.setOnClickListener(l -> {
            if (LocalUpdateTaskDialog.isOpenTask(LocalRefreshJobStore.read())) {
                showRefreshDetails();
            } else {
                drawPager.setCurrentItem(0);
            }
        });

        if (needLoad) loadLocalData();

        return subscriptionView;
    }

    public void showRefreshProgress(SubscriptionScanProgress progress) {
        if (updateToolbar != null) updateToolbar.render(LocalRefreshJobStore.read());
    }

    public void showRefreshSaving() {
        if (toolbar != null) toolbar.setSubtitle(R.string.subscription_refresh_saving);
    }

    public void showRefreshResult(SubscriptionRefreshStatus.Result result, long time) {
        String account = SubscriptionRepository.getInstance().getAccountKey();
        SubscriptionRefreshStatus.save(account, result, time);
        if (updateToolbar != null) updateToolbar.render(LocalRefreshJobStore.read());
    }

    private void restoreRefreshStatus() {
        if (updateToolbar != null) updateToolbar.render(LocalRefreshJobStore.read());
    }

    public void showRefreshDetails() {
        LocalRefreshJobStore.Snapshot snapshot = LocalRefreshJobStore.read();
        if (snapshot == null) return;
        LocalUpdateTaskDialog.show(context, snapshot, true);
    }

    public void setUserTagList(UserTagList tagList){
        loadLocalData();

    }

    private void seeDetailPage() {
        activity.startScene(new Announcer(LocalFollowScene.class));
    }

    private void bindViewSecond() {
        bindViewSecond(true);
    }

    private void bindViewSecond(boolean sort) {
        progressView.setVisibility(View.GONE);
        frameLayout.setVisibility(View.VISIBLE);
        if (userTagList.userTags.isEmpty()) {
            textView.setText(R.string.local_follow_empty);
            textView.setVisibility(View.VISIBLE);
            return;
        }
        textView.setVisibility(View.GONE);
//        List<String> name = new ArrayList<>();
//
//        for (UserTag userTag : userTagList.userTags) {
//            name.add(userTag.getName(ehTags));
//        }

        adapter = new SubscriptionItemAdapter(context, userTagList, ehTags);

        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view1, position, id) -> {
            UserTag tag = userTagList.userTags.get(position);
            callback.onSubscriptionItemClick(tag.tagName);
        });
        listView.setOnScrollListener(new ScrollListener());
        if (userTagList.size()>0){
            resume();
        }
        loadFollowCounts(sort);
    }

    private void loadFollowCounts(boolean sort) {
        UserTagList snapshot = userTagList;
        if (snapshot == null) return;
        SubscriptionRepository repository = SubscriptionRepository.getInstance();
        repository.execute(() -> {
            LocalFollowRepository local = LocalFollowRepository.getInstance();
            for (UserTag tag : snapshot.userTags) {
                TagUpdateState state = local.readState(LocalFollowRepository.SOURCE_FOLLOW,
                        tag.tagName, LocalFollowRepository.FIXED_CHINESE_SIGNATURE);
                tag.followCount = state.checkedAt == 0 ? null : state.displayCount();
            }
            if (sort) snapshot.userTags.sort((left, right) -> {
                TagUpdateState a = local.readState(LocalFollowRepository.SOURCE_FOLLOW,
                        left.tagName, LocalFollowRepository.FIXED_CHINESE_SIGNATURE);
                TagUpdateState b = local.readState(LocalFollowRepository.SOURCE_FOLLOW,
                        right.tagName, LocalFollowRepository.FIXED_CHINESE_SIGNATURE);
                int aRank = a.state == TagUpdateState.State.LOWER_BOUND
                        ? TagUpdateState.DISPLAY_CAP + 1 : a.count;
                int bRank = b.state == TagUpdateState.State.LOWER_BOUND
                        ? TagUpdateState.DISPLAY_CAP + 1 : b.count;
                int count = Integer.compare(bRank, aRank);
                if (count != 0) return count;
                int checked = Long.compare(b.checkedAt, a.checkedAt);
                return checked != 0 ? checked : left.tagName.compareTo(right.tagName);
            });
            if (activity != null) activity.runOnUiThread(() -> {
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        });
    }

    private void loadLocalData() {
        loadLocalData(true);
    }

    private void loadLocalData(boolean sort) {
        SubscriptionRepository.getInstance().execute(() -> {
            UserTagList localList = new UserTagList();
            int index = 1;
            for (String name : LocalFollowRepository.getInstance().getAll()) {
                UserTag tag = new UserTag();
                tag.userTagId = "usertag_" + index++;
                tag.tagName = name;
                tag.watched = true;
                tag.hidden = false;
                localList.userTags.add(tag);
            }
            userTagList = localList;
            SubscriptionSnapshot.refreshFromDatabase();
            if (activity != null) activity.runOnUiThread(() -> bindViewSecond(sort));
        });
    }

    @Override
    public void onLocalUpdateProgress(LocalRefreshJobStore.Snapshot snapshot) {
        if (listView == null) return;
        listView.post(() -> {
            if (updateToolbar != null) updateToolbar.render(snapshot);
            boolean terminal = snapshot != null
                    && !LocalRefreshJobStore.STATUS_RUNNING.equals(snapshot.status)
                    && !LocalRefreshJobStore.STATUS_PAUSED.equals(snapshot.status);
            if (terminal) {
                if (LocalRefreshJobStore.TYPE_FOLLOW.equals(snapshot.type)
                        || LocalRefreshJobStore.TYPE_ALL.equals(snapshot.type)) {
                    loadLocalData(true);
                } else if (LocalRefreshJobStore.TYPE_BASELINE.equals(snapshot.type)) {
                    loadLocalData(false);
                }
            }
        });
    }

    public void refreshFollowCounts() {
        refreshFollowCounts(true);
    }

    public void refreshFollowCounts(boolean sort) {
        if (userTagList != null) loadFollowCounts(sort);
    }

    private void addNewTag() {
        if (!isLogin()) {
            Toast.makeText(context, R.string.settings_eh_identity_cookies_tourist, Toast.LENGTH_SHORT).show();
            return;
        }
        tagName = callback.getAddTagName(userTagList);
        if (tagName == null) {
            Toast.makeText(context, R.string.can_not_use_this_tag, Toast.LENGTH_SHORT).show();
            return;
        }

        final EditTextDialogBuilder builder = new EditTextDialogBuilder(context,
                tagName, context.getString(R.string.tag_title));
        builder.setTitle(R.string.add_tag_dialog_title);
        builder.setPositiveButton(R.string.subscription_watched, this::onDialogPositiveButtonClick);
        builder.setNegativeButton(R.string.subscription_hidden, this::onDialogNegativeButtonClick);
        builder.show();
    }

    private void onDialogNegativeButtonClick(DialogInterface dialog, int which) {
        dialog.dismiss();
        requestTag(tagName, false);
    }

    private void onDialogPositiveButtonClick(DialogInterface dialog, int which) {
        dialog.dismiss();
        requestTag(tagName, true);
    }

    private void loadData() throws EhException {
        boolean requested = request();
        if (!requested) {
            throw new EhException("请求数据失败请更换IP地址或检查网络设置是否正确~");
        }
    }

    private void requestTag(String tagName, boolean tagState) {
        String url = EhUrl.getMyTag();

        if (null == context || null == activity) {
            return;
        }

        progressView.setVisibility(View.VISIBLE);
        frameLayout.setVisibility(View.GONE);

        EhClient.Callback<UserTagList> callback = new SubscriptionDetailListener(context, activity.getStageId(), mTag);

        TagPushParam param = new TagPushParam();

        param.tagNameNew = tagName;
        if (tagState) {
            param.tagWatchNew = "on";
        } else {
            param.tagHiddenNew = "on";
        }


        EhRequest mRequest = new EhRequest()
                .setMethod(EhClient.METHOD_ADD_TAG)
                .setArgs(url, param).setCallback(callback);

        ehClient.execute(mRequest);
    }

    /**
     * 请求数据
     *
     */
    private boolean request() {

//        String url = EhUrl.getTopListUrl();
        String url = EhUrl.getMyTag();

        if (null == context || null == activity) {
            return false;
        }

        EhClient.Callback<UserTagList> callback = new SubscriptionDetailListener(context, activity.getStageId(), mTag);

        EhRequest mRequest = new EhRequest()
                .setMethod(EhClient.METHOD_GET_WATCHED)
                .setArgs(url).setCallback(callback);

        ehClient.execute(mRequest);

        return true;
    }

    public void resume() {
        Object scrollY = ehApplication.getTempCache(SUBSCRIPTION_DRAW_SCROLL_Y);
        Object pos = ehApplication.getTempCache(SUBSCRIPTION_DRAW_POS);
        if (scrollY != null && pos != null) {
            listView.setSelection((Integer) pos);
        }
    }


    private class SubscriptionDetailListener extends EhCallback<GalleryListScene, UserTagList> {

        public SubscriptionDetailListener(Context context, int stageId, String sceneTag) {
            super(context, stageId, sceneTag);
        }

        @Override
        public boolean isInstance(SceneFragment scene) {
            return false;
        }

        @Override
        public void onSuccess(UserTagList result) {

            if (result == null) {
                userTagList = new UserTagList();
                userTagList.userTags = new ArrayList<>();
            } else {
                userTagList = result;
            }
            EhApplication.saveUserTagList(context, userTagList);
            SubscriptionSnapshot.replace(userTagList);
            bindViewSecond();
            needLoad = false;
        }

        @Override
        public void onFailure(Exception e) {

        }

        @Override
        public void onCancel() {

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
            ehApplication.putTempCache(SUBSCRIPTION_DRAW_SCROLL_Y, scrollY);
            ehApplication.putTempCache(SUBSCRIPTION_DRAW_POS, firstPos);
        }

        @Override
        public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        }
    }
}
