package com.hippo.ehviewer.ui.scene.reading;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.hippo.android.resource.AttrResources;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.easyrecyclerview.FastScroller;
import com.hippo.easyrecyclerview.HandlerDrawable;
import com.hippo.easyrecyclerview.MarginItemDecoration;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.reader.ReadingQueueManager;
import com.hippo.ehviewer.reader.ReadingQueuePolicy;
import com.hippo.ehviewer.reader.ReadingQueueRepository;
import com.hippo.ehviewer.ui.DownloadGalleryActivity;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.ehviewer.ui.scene.TransitionNameFactory;
import com.hippo.ehviewer.widget.SimpleRatingView;
import com.hippo.ripple.Ripple;
import com.hippo.util.DrawableManager;
import com.hippo.view.ViewTransition;
import com.hippo.widget.LoadImageView;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ViewUtils;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

/** Newest-first list of complete app-managed downloads that were successfully displayed. */
public class ReadingQueueScene extends ToolbarScene implements
        EasyRecyclerView.OnItemClickListener, EasyRecyclerView.OnItemLongClickListener {

    @Nullable private EasyRecyclerView mRecyclerView;
    @Nullable private ViewTransition mViewTransition;
    @Nullable private QueueAdapter mAdapter;
    @Nullable private DownloadManager mDownloadManager;
    private final List<DownloadInfo> mItems = new ArrayList<>();

    @Override
    public int getNavCheckedItem() {
        return R.id.nav_reading_queue;
    }

    @Override
    public int getMenuResId() {
        return R.menu.scene_reading_queue;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            showQueueSettings();
            return true;
        }
        return false;
    }

    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_history, container, false);
        View content = ViewUtils.$$(view, R.id.content);
        mRecyclerView = (EasyRecyclerView) ViewUtils.$$(content, R.id.recycler_view);
        FastScroller fastScroller = (FastScroller) ViewUtils.$$(content, R.id.fast_scroller);
        TextView tip = (TextView) ViewUtils.$$(view, R.id.tip);
        tip.setText(R.string.no_reading_queue);
        mViewTransition = new ViewTransition(content, tip);

        Context context = getEHContext();
        AssertUtils.assertNotNull(context);
        mDownloadManager = EhApplication.getDownloadManager(context);
        Drawable drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download);
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        tip.setCompoundDrawables(null, drawable, null, null);

        mAdapter = new QueueAdapter(inflater);
        mAdapter.setHasStableIds(true);
        mRecyclerView.setAdapter(mAdapter);
        AutoStaggeredGridLayoutManager layoutManager = new AutoStaggeredGridLayoutManager(
                0, StaggeredGridLayoutManager.VERTICAL);
        layoutManager.setColumnSize(context.getResources().getDimensionPixelOffset(
                Settings.getDetailSizeResId()));
        layoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE);
        mRecyclerView.setLayoutManager(layoutManager);
        mRecyclerView.setSelector(Ripple.generateRippleDrawable(context,
                !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme),
                new ColorDrawable(Color.TRANSPARENT)));
        mRecyclerView.setDrawSelectorOnTop(true);
        mRecyclerView.setClipToPadding(false);
        mRecyclerView.setOnItemClickListener(this);
        mRecyclerView.setOnItemLongClickListener(this);
        int interval = context.getResources().getDimensionPixelOffset(
                R.dimen.gallery_list_interval);
        int paddingH = context.getResources().getDimensionPixelOffset(
                R.dimen.gallery_list_margin_h);
        int paddingV = context.getResources().getDimensionPixelOffset(
                R.dimen.gallery_list_margin_v);
        MarginItemDecoration decoration = new MarginItemDecoration(
                interval, paddingH, paddingV, paddingH, paddingV);
        mRecyclerView.addItemDecoration(decoration);
        decoration.applyPaddings(mRecyclerView);

        fastScroller.attachToRecyclerView(mRecyclerView);
        HandlerDrawable handlerDrawable = new HandlerDrawable();
        handlerDrawable.setColor(AttrResources.getAttrColor(context,
                R.attr.widgetColorThemeAccent));
        fastScroller.setHandlerDrawable(handlerDrawable);
        reloadQueue();
        return view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.reading_queue);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onResume() {
        super.onResume();
        Context context = getEHContext();
        if (context != null) {
            ReadingQueueManager.pruneInvalid(context, result -> reloadQueue());
        }
    }

    @Override
    public void onDestroyView() {
        if (mRecyclerView != null) {
            mRecyclerView.stopScroll();
        }
        mRecyclerView = null;
        mViewTransition = null;
        mAdapter = null;
        mDownloadManager = null;
        mItems.clear();
        super.onDestroyView();
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    private void showQueueSettings() {
        View view = LayoutInflater.from(requireContext()).inflate(
                R.layout.dialog_reading_queue_settings, null);
        SwitchCompat autoDelete = view.findViewById(R.id.auto_delete);
        TextInputLayout capacityLayout = view.findViewById(R.id.capacity_layout);
        EditText capacityInput = view.findViewById(R.id.capacity);
        boolean oldEnabled = Settings.getReadingQueueAutoDelete();
        int oldCapacity = Settings.getReadingQueueCapacity();
        autoDelete.setChecked(oldEnabled);
        capacityInput.setText(String.valueOf(oldCapacity));
        capacityInput.setSelection(capacityInput.length());

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.reading_queue_settings)
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    Integer newCapacity = ReadingQueuePolicy.parseCapacity(
                            capacityInput.getText() == null
                                    ? null : capacityInput.getText().toString());
                    if (newCapacity == null) {
                        capacityLayout.setError(getString(
                                R.string.settings_reading_queue_capacity_invalid));
                        capacityInput.requestFocus();
                        return;
                    }
                    capacityLayout.setError(null);
                    boolean newEnabled = autoDelete.isChecked();
                    int pendingDeletionCount =
                            ReadingQueuePolicy.confirmationDeletionCount(
                                    oldEnabled, oldCapacity, newEnabled, newCapacity,
                                    ReadingQueueRepository.getInstance().getCount());
                    dialog.dismiss();
                    if (pendingDeletionCount > 0) {
                        confirmQueueSettings(newEnabled, newCapacity,
                                pendingDeletionCount);
                    } else {
                        saveQueueSettings(newEnabled, newCapacity);
                    }
                }));
        dialog.show();
    }

    private void confirmQueueSettings(boolean enabled, int capacity, int deletionCount) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_reading_queue_cleanup_title)
                .setMessage(getString(R.string.settings_reading_queue_cleanup_message,
                        deletionCount))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    saveQueueSettings(enabled, capacity);
                    ReadingQueueManager.trim(requireContext(), result -> {
                        Context context = getEHContext();
                        if (context == null) {
                            return;
                        }
                        Toast.makeText(context, context.getString(
                                R.string.reading_queue_cleanup_result,
                                result.deleted, result.failed), Toast.LENGTH_LONG).show();
                        reloadQueue();
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveQueueSettings(boolean enabled, int capacity) {
        Settings.putReadingQueueAutoDelete(enabled);
        Settings.putReadingQueueCapacity(capacity);
    }

    private void reloadQueue() {
        if (mDownloadManager == null || mAdapter == null || mViewTransition == null) {
            return;
        }
        mItems.clear();
        for (Long gid : ReadingQueueRepository.getInstance().getNewestFirst()) {
            DownloadInfo info = mDownloadManager.getDownloadInfo(gid);
            if (ReadingQueueManager.isEligible(info)) {
                mItems.add(info);
            }
        }
        mAdapter.notifyDataSetChanged();
        mViewTransition.showView(mItems.isEmpty() ? 1 : 0, true);
    }

    @Override
    public boolean onItemClick(EasyRecyclerView parent, View view, int position, long id) {
        if (position < 0 || position >= mItems.size()) {
            return false;
        }
        DownloadInfo info = mItems.get(position);
        long[] queue = new long[mItems.size()];
        for (int i = 0; i < mItems.size(); i++) {
            queue[i] = mItems.get(i).gid;
        }
        Intent intent = new Intent(requireActivity(), DownloadGalleryActivity.class);
        intent.setAction(GalleryActivity.ACTION_EH);
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, info);
        intent.putExtra(GalleryActivity.KEY_DOWNLOAD_READING_QUEUE, queue);
        intent.putExtra(GalleryActivity.KEY_DOWNLOAD_READING_INDEX, position);
        intent.putExtra(GalleryActivity.KEY_DOWNLOAD_READING_START_INDEX, position);
        startActivity(intent);
        return true;
    }

    @Override
    public boolean onItemLongClick(EasyRecyclerView parent, View view, int position, long id) {
        if (position < 0 || position >= mItems.size()) {
            return false;
        }
        DownloadInfo info = mItems.get(position);
        new AlertDialog.Builder(requireContext())
                .setTitle(EhUtils.getSuitableTitle(info))
                .setItems(new String[]{getString(R.string.reading_queue_remove),
                        getString(R.string.reading_queue_delete_download)},
                        (dialog, which) -> {
                            if (which == 0) {
                                ReadingQueueManager.removeFromQueue(info.gid, result -> {
                                    reloadQueue();
                                    Context context = getEHContext();
                                    if (context == null) {
                                        return;
                                    }
                                    Toast.makeText(context,
                                            R.string.reading_queue_remove_done,
                                            Toast.LENGTH_SHORT).show();
                                });
                            } else if (which == 1) {
                                confirmDelete(info);
                            }
                        })
                .show();
        return true;
    }

    private void confirmDelete(DownloadInfo info) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.reading_queue_delete_download)
                .setMessage(getString(R.string.reading_queue_delete_confirm,
                        EhUtils.getSuitableTitle(info)))
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        ReadingQueueManager.deleteDownload(requireContext(), info.gid, result -> {
                            Context context = getEHContext();
                            if (context == null) {
                                return;
                            }
                            if (result.failed > 0) {
                                Toast.makeText(context,
                                        R.string.reading_queue_delete_failed,
                                        Toast.LENGTH_LONG).show();
                            } else if (result.deleted > 0) {
                                Toast.makeText(context,
                                        R.string.reading_queue_delete_done,
                                        Toast.LENGTH_SHORT).show();
                            }
                            reloadQueue();
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private final class QueueAdapter extends RecyclerView.Adapter<QueueHolder> {
        private final LayoutInflater inflater;
        private final int thumbWidth;
        private final int thumbHeight;

        QueueAdapter(LayoutInflater inflater) {
            this.inflater = inflater;
            View calculator = inflater.inflate(R.layout.item_gallery_list_thumb_height, null);
            ViewUtils.measureView(calculator, 1024, ViewGroup.LayoutParams.WRAP_CONTENT);
            thumbHeight = calculator.getMeasuredHeight();
            thumbWidth = thumbHeight * 2 / 3;
        }

        @Override
        public long getItemId(int position) {
            return mItems.get(position).gid;
        }

        @NonNull
        @Override
        public QueueHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            QueueHolder holder = new QueueHolder(
                    inflater.inflate(R.layout.item_history, parent, false));
            ViewGroup.LayoutParams lp = holder.thumb.getLayoutParams();
            lp.width = thumbWidth;
            lp.height = thumbHeight;
            holder.thumb.setLayoutParams(lp);
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull QueueHolder holder, int position) {
            DownloadInfo info = mItems.get(position);
            holder.thumb.load(EhCacheKeyFactory.getThumbKey(info.gid), info.thumb);
            holder.title.setText(EhUtils.getSuitableTitle(info));
            holder.uploader.setText(info.uploader);
            holder.rating.setRating(info.rating);
            String categoryText = EhUtils.getCategory(info.category);
            if (!categoryText.equals(holder.category.getText())) {
                holder.category.setText(categoryText);
                holder.category.setBackgroundColor(EhUtils.getCategoryColor(info.category));
            }
            holder.posted.setText(info.posted);
            holder.language.setText(info.simpleLanguage);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ViewCompat.setTransitionName(holder.thumb,
                        TransitionNameFactory.getThumbTransitionName(info.gid));
            }
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }

    private static final class QueueHolder extends RecyclerView.ViewHolder {
        final LoadImageView thumb;
        final TextView title;
        final TextView uploader;
        final SimpleRatingView rating;
        final TextView category;
        final TextView posted;
        final TextView language;

        QueueHolder(View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumb);
            title = itemView.findViewById(R.id.title);
            uploader = itemView.findViewById(R.id.uploader);
            rating = itemView.findViewById(R.id.rating);
            category = itemView.findViewById(R.id.category);
            posted = itemView.findViewById(R.id.posted);
            language = itemView.findViewById(R.id.simple_language);
        }
    }
}
