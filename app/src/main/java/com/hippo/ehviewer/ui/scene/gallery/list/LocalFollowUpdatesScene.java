package com.hippo.ehviewer.ui.scene.gallery.list;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.subscription.LocalFollowUpdatesRepository;
import com.hippo.ehviewer.subscription.LocalFollowUpdatesRepository.Entry;
import com.hippo.ehviewer.subscription.LocalRefreshJobStore;
import com.hippo.ehviewer.subscription.LocalUpdateService;
import com.hippo.ehviewer.subscription.SubscriptionRepository;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene;
import com.hippo.scene.Announcer;
import com.hippo.widget.LoadImageView;

import java.util.ArrayList;
import java.util.List;

/** Local snapshots of discovered follow updates, independent of per-tag unread counts. */
public final class LocalFollowUpdatesScene extends ToolbarScene
        implements LocalUpdateService.Listener {
    private static final String KEY_UNREAD = "follow_updates_unread";
    private static final String KEY_LIMIT = "follow_updates_limit";
    private static final String KEY_ANCHOR_GID = "follow_updates_anchor_gid";
    private static final String KEY_ANCHOR_POSITION = "follow_updates_anchor_position";
    private static final String KEY_ANCHOR_OFFSET = "follow_updates_anchor_offset";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Entry> items = new ArrayList<>();
    private boolean unreadOnly;
    private int visibleLimit = LocalFollowUpdatesRepository.PAGE_SIZE;
    private long anchorGid = -1;
    private int anchorPosition;
    private int anchorOffset;
    private int requestGeneration;
    private boolean loading;
    private boolean hasMore;
    private boolean loadFailed;
    private boolean active;
    private RecyclerView recycler;
    private LinearLayoutManager layoutManager;
    private UpdatesAdapter adapter;
    private View progress;
    private TextView message;
    private View retry;
    private View footer;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            unreadOnly = savedInstanceState.getBoolean(KEY_UNREAD);
            visibleLimit = Math.max(LocalFollowUpdatesRepository.PAGE_SIZE,
                    Math.min(LocalFollowUpdatesRepository.CAPACITY,
                            savedInstanceState.getInt(KEY_LIMIT,
                                    LocalFollowUpdatesRepository.PAGE_SIZE)));
            anchorGid = savedInstanceState.getLong(KEY_ANCHOR_GID, -1);
            anchorPosition = savedInstanceState.getInt(KEY_ANCHOR_POSITION);
            anchorOffset = savedInstanceState.getInt(KEY_ANCHOR_OFFSET);
        }
        LocalUpdateService.addListener(this);
    }

    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_local_follow_updates, container, false);
        recycler = view.findViewById(R.id.recycler_view);
        progress = view.findViewById(R.id.follow_updates_progress);
        message = view.findViewById(R.id.follow_updates_message);
        retry = view.findViewById(R.id.follow_updates_retry);
        footer = view.findViewById(R.id.follow_updates_footer);
        layoutManager = new LinearLayoutManager(requireContext());
        recycler.setLayoutManager(layoutManager);
        recycler.setItemAnimator(null);
        adapter = new UpdatesAdapter(inflater);
        recycler.setAdapter(adapter);
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (dy > 0) loadNextPageIfNeeded();
            }
        });
        retry.setOnClickListener(ignored -> reload(true));
        RadioGroup filters = view.findViewById(R.id.follow_updates_filters);
        filters.check(unreadOnly ? R.id.follow_updates_unread : R.id.follow_updates_all);
        filters.setOnCheckedChangeListener((group, checkedId) -> {
            boolean nextUnread = checkedId == R.id.follow_updates_unread;
            if (nextUnread == unreadOnly) return;
            unreadOnly = nextUnread;
            visibleLimit = LocalFollowUpdatesRepository.PAGE_SIZE;
            anchorGid = -1;
            anchorPosition = 0;
            anchorOffset = 0;
            items.clear();
            adapter.notifyDataSetChanged();
            reload(false);
        });
        renderStatus();
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.local_follow_updates);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    @Override
    public void onResume() {
        super.onResume();
        active = true;
        reload(true);
    }

    @Override
    public void onPause() {
        active = false;
        rememberAnchor();
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        rememberAnchor();
        outState.putBoolean(KEY_UNREAD, unreadOnly);
        outState.putInt(KEY_LIMIT, visibleLimit);
        outState.putLong(KEY_ANCHOR_GID, anchorGid);
        outState.putInt(KEY_ANCHOR_POSITION, anchorPosition);
        outState.putInt(KEY_ANCHOR_OFFSET, anchorOffset);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        rememberAnchor();
        requestGeneration++;
        loading = false;
        if (recycler != null) {
            recycler.stopScroll();
            recycler.setAdapter(null);
        }
        recycler = null;
        layoutManager = null;
        adapter = null;
        progress = null;
        message = null;
        retry = null;
        footer = null;
        items.clear();
        super.onDestroyView();
    }

    @Override
    public void onLocalUpdateProgress(LocalRefreshJobStore.Snapshot snapshot) {
        if (snapshot == null || LocalRefreshJobStore.STATUS_RUNNING.equals(snapshot.status)
                || LocalRefreshJobStore.STATUS_PAUSED.equals(snapshot.status)) return;
        if (!LocalRefreshJobStore.TYPE_FOLLOW.equals(snapshot.type)
                && !LocalRefreshJobStore.TYPE_ALL.equals(snapshot.type)) return;
        mainHandler.post(() -> {
            if (active && recycler != null) reload(true);
        });
    }

    private void reload(boolean preservePosition) {
        if (recycler == null) return;
        if (preservePosition) rememberAnchor();
        final int generation = ++requestGeneration;
        final int limit = visibleLimit;
        final boolean filter = unreadOnly;
        final long requestedAnchor = preservePosition ? anchorGid : -1;
        loading = true;
        loadFailed = false;
        renderStatus();
        SubscriptionRepository.getInstance().execute(() -> {
            try {
                // Reload the visible prefix: newly inserted or read rows cannot shift an offset
                // and silently skip a gallery between pages. Only 50 more cards become visible.
                List<Entry> result = LocalFollowUpdatesRepository.getInstance().listUpdates(
                        filter, 0, Math.min(LocalFollowUpdatesRepository.CAPACITY, limit + 1));
                int resolvedLimit = limit;
                if (FollowUpdatesPagingPolicy.needsAnchorLookup(requestedAnchor,
                        findPosition(result, requestedAnchor), limit, result.size())) {
                    // A scan may have pushed a still-existing anchor outside the old prefix.
                    // Only then inspect the bounded history and reveal enough pages to keep it.
                    result = LocalFollowUpdatesRepository.getInstance().listUpdates(
                            filter, 0, LocalFollowUpdatesRepository.CAPACITY);
                }
                resolvedLimit = FollowUpdatesPagingPolicy.limitIncludingAnchor(limit,
                        findPosition(result, requestedAnchor),
                        LocalFollowUpdatesRepository.PAGE_SIZE,
                        LocalFollowUpdatesRepository.CAPACITY);
                final List<Entry> loaded = result;
                final int loadedLimit = resolvedLimit;
                mainHandler.post(() -> {
                    if (recycler == null || generation != requestGeneration) return;
                    if (preservePosition) rememberAnchor();
                    visibleLimit = loadedLimit;
                    hasMore = loaded.size() > loadedLimit;
                    items.clear();
                    items.addAll(loaded.subList(0, Math.min(loaded.size(), loadedLimit)));
                    loading = false;
                    adapter.notifyDataSetChanged();
                    restoreAnchor();
                    renderStatus();
                    recycler.post(this::loadNextPageIfNeeded);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (recycler == null || generation != requestGeneration) return;
                    loading = false;
                    loadFailed = true;
                    renderStatus();
                });
            }
        });
    }

    private void loadNextPageIfNeeded() {
        if (!active || recycler == null || layoutManager == null || loading || loadFailed
                || !hasMore || items.isEmpty()) return;
        if (layoutManager.findLastVisibleItemPosition() < items.size() - 5) return;
        visibleLimit = Math.min(LocalFollowUpdatesRepository.CAPACITY,
                visibleLimit + LocalFollowUpdatesRepository.PAGE_SIZE);
        reload(true);
    }

    private void rememberAnchor() {
        if (layoutManager == null || items.isEmpty()) return;
        int position = layoutManager.findFirstVisibleItemPosition();
        if (position < 0 || position >= items.size()) return;
        View first = layoutManager.findViewByPosition(position);
        if (first == null) return;
        anchorGid = items.get(position).gallery.gid;
        anchorPosition = position;
        anchorOffset = layoutManager.getDecoratedTop(first) - recycler.getPaddingTop();
    }

    private void restoreAnchor() {
        if (items.isEmpty() || layoutManager == null) return;
        int position = Math.min(Math.max(0, anchorPosition), items.size() - 1);
        int match = findPosition(items, anchorGid);
        if (match >= 0) position = match;
        layoutManager.scrollToPositionWithOffset(position, anchorOffset);
    }

    private static int findPosition(List<Entry> entries, long gid) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).gallery.gid == gid) return i;
        }
        return -1;
    }

    private void renderStatus() {
        if (recycler == null) return;
        boolean empty = items.isEmpty();
        recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        message.setVisibility(!loading && (empty || loadFailed) ? View.VISIBLE : View.GONE);
        message.setText(loadFailed ? R.string.local_follow_updates_load_failed
                : unreadOnly ? R.string.local_follow_updates_no_unread
                : R.string.local_follow_updates_empty);
        retry.setVisibility(loadFailed ? View.VISIBLE : View.GONE);
        footer.setVisibility(loading || empty || loadFailed ? View.VISIBLE : View.GONE);
    }

    private void openDetail(Entry entry) {
        Bundle args = new Bundle();
        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GALLERY_INFO);
        args.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, entry.gallery);
        startScene(new Announcer(GalleryDetailScene.class).setArgs(args));
    }

    private final class UpdatesAdapter extends RecyclerView.Adapter<UpdateHolder> {
        private final LayoutInflater inflater;

        UpdatesAdapter(LayoutInflater inflater) {
            this.inflater = inflater;
            setHasStableIds(true);
        }

        @NonNull
        @Override
        public UpdateHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new UpdateHolder(inflater.inflate(R.layout.item_local_follow_update,
                    parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull UpdateHolder holder, int position) {
            Entry entry = items.get(position);
            GalleryInfo gallery = entry.gallery;
            holder.thumb.load(EhCacheKeyFactory.getThumbKey(gallery.gid), gallery.thumb);
            holder.title.setText(EhUtils.getSuitableTitle(gallery));
            holder.posted.setText(getString(R.string.local_follow_updates_posted,
                    gallery.posted == null ? "" : gallery.posted));
            holder.unread.setVisibility(entry.readAt == 0 ? View.VISIBLE : View.GONE);
            EhTagDatabase database = EhTagDatabase.getInstance(holder.itemView.getContext());
            StringBuilder tags = new StringBuilder();
            for (String tag : entry.matchedTags) {
                if (tags.length() > 0) tags.append('\n');
                tags.append(FollowLabelFormatter.present(tag, database).displayName);
            }
            holder.tags.setText(getString(R.string.local_follow_updates_tags, tags.toString()));
            holder.itemView.setOnClickListener(view -> openDetail(entry));
            holder.thumb.setOnClickListener(view -> openDetail(entry));
        }

        @Override
        public void onViewRecycled(@NonNull UpdateHolder holder) {
            holder.thumb.unload();
            holder.itemView.setOnClickListener(null);
            holder.thumb.setOnClickListener(null);
        }

        @Override public int getItemCount() { return items.size(); }
        @Override public long getItemId(int position) { return items.get(position).gallery.gid; }
    }

    private static final class UpdateHolder extends RecyclerView.ViewHolder {
        final LoadImageView thumb;
        final TextView title;
        final TextView posted;
        final TextView tags;
        final TextView unread;

        UpdateHolder(View view) {
            super(view);
            thumb = view.findViewById(R.id.thumb);
            title = view.findViewById(R.id.title);
            posted = view.findViewById(R.id.posted);
            tags = view.findViewById(R.id.follow_updates_tags);
            unread = view.findViewById(R.id.follow_updates_unread_badge);
        }
    }
}
