/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.scene.gallery.list;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.animator.GeneralItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemAdapter;
import com.h6ah4i.android.widget.advrecyclerview.draggable.ItemDraggableRange;
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager;
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractDraggableItemViewHolder;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.dao.QuickSearch;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.scene.Announcer;
import com.hippo.util.DrawableManager;
import com.hippo.view.ViewTransition;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ViewUtils;

import java.util.ArrayList;
import java.util.List;

public final class QuickSearchScene extends ToolbarScene {


    /*---------------
     Whole life cycle
     ---------------*/
    @Nullable
    private List<QuickSearch> mQuickSearchList;
    private final List<QuickSearch> mVisibleQuickSearchList = new ArrayList<>();
    private String mSearchQuery = "";

    /*---------------
     View life cycle
     ---------------*/
    @Nullable
    private EasyRecyclerView mRecyclerView;
    @Nullable
    private ViewTransition mViewTransition;
    @Nullable
    private TextView mTip;
    @Nullable
    private RecyclerView.Adapter mAdapter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mQuickSearchList = EhDB.getAllQuickSearch();
        mVisibleQuickSearchList.clear();
        mVisibleQuickSearchList.addAll(mQuickSearchList);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mQuickSearchList = null;
        mVisibleQuickSearchList.clear();
    }

    @SuppressWarnings("deprecation")
    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater,
                              @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_label_list, container, false);

        mRecyclerView = (EasyRecyclerView) ViewUtils.$$(view, R.id.recycler_view);
        mTip = (TextView) ViewUtils.$$(view, R.id.tip);
        mViewTransition = new ViewTransition(mRecyclerView, mTip);

        Context context = getEHContext();
        AssertUtils.assertNotNull(context);

        Drawable drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_search);
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        mTip.setCompoundDrawables(null, drawable, null, null);
        mTip.setText(R.string.no_quick_search);

        // drag & drop manager
        RecyclerViewDragDropManager dragDropManager = new RecyclerViewDragDropManager();
        dragDropManager.setDraggingItemShadowDrawable(
                (NinePatchDrawable) context.getResources().getDrawable(R.drawable.shadow_8dp));

        RecyclerView.Adapter adapter = new QuickSearchAdapter();
        adapter.setHasStableIds(true);
        adapter = dragDropManager.createWrappedAdapter(adapter); // wrap for dragging
        mAdapter = adapter;

        final GeneralItemAnimator animator = new DraggableItemAnimator();
        mRecyclerView.setLayoutManager(new LinearLayoutManager(context));
        mRecyclerView.setAdapter(adapter);
        mRecyclerView.setItemAnimator(animator);




        dragDropManager.attachRecyclerView(mRecyclerView);

        updateView();

        return view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.quick_search);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (null != mRecyclerView) {
            mRecyclerView.stopScroll();
            mRecyclerView = null;
        }

        mViewTransition = null;
        mTip = null;
    }

    @Override
    public int getMenuResId() {
        return R.menu.quick_search_management;
    }

    @Override
    public void onMenuCreated(Menu menu) {
        SearchView search = (SearchView) menu.findItem(R.id.action_search).getActionView();
        search.setQueryHint(getString(R.string.search));
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) {
                filter(query);
                return true;
            }

            @Override public boolean onQueryTextChange(String query) {
                filter(query);
                return true;
            }
        });
        search.setOnCloseListener(() -> {
            filter("");
            return false;
        });
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.action_bookmark_diagnostics) {
            startScene(new Announcer(BookmarkDiagnosticsScene.class));
            return true;
        }
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mQuickSearchList == null) return;
        mQuickSearchList.clear();
        mQuickSearchList.addAll(EhDB.getAllQuickSearch());
        filter(mSearchQuery);
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    private void updateView() {
        if (mViewTransition != null) {
            if (!mVisibleQuickSearchList.isEmpty()) {
                mViewTransition.showView(0);
            } else {
                if (mTip != null) {
                    mTip.setText(isFiltering()
                            ? R.string.quick_search_no_results
                            : R.string.no_quick_search);
                }
                mViewTransition.showView(1);
            }
        }
    }

    private void filter(String query) {
        mSearchQuery = query == null ? "" : query.trim();
        mVisibleQuickSearchList.clear();
        mVisibleQuickSearchList.addAll(
                QuickSearchFilter.filter(mQuickSearchList, mSearchQuery));
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
        updateView();
    }

    private boolean isFiltering() {
        return !mSearchQuery.isEmpty();
    }

    private class QuickSearchHolder extends AbstractDraggableItemViewHolder implements View.OnClickListener {

        public final TextView label;
        public final View dragHandler;
        public final View delete;

        public QuickSearchHolder(View itemView) {
            super(itemView);

            label = (TextView) ViewUtils.$$(itemView, R.id.label);
            dragHandler = ViewUtils.$$(itemView, R.id.drag_handler);
            delete = ViewUtils.$$(itemView, R.id.delete);

            delete.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            int position = getAdapterPosition();
            Context context = getEHContext();
            if (position == RecyclerView.NO_POSITION) {
                return;
            }

            final QuickSearch quickSearch = mVisibleQuickSearchList.get(position);
            new AlertDialog.Builder(context)
                    .setTitle(R.string.delete_quick_search_title)
                    .setMessage(getString(R.string.delete_quick_search_message, quickSearch.name))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            QuickSearchDeleteHelper.delete(quickSearch);
                            if (mQuickSearchList != null) {
                                mQuickSearchList.remove(quickSearch);
                            }
                            mVisibleQuickSearchList.remove(quickSearch);
                        }
                    })
                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            if (null != mAdapter) {
                                mAdapter.notifyDataSetChanged();
                            }
                            updateView();
                        }
                    }).show();
        }
    }

    private class QuickSearchAdapter extends RecyclerView.Adapter<QuickSearchHolder>
            implements DraggableItemAdapter<QuickSearchHolder> {

        private final LayoutInflater mInflater;

        public QuickSearchAdapter() {
            mInflater = getLayoutInflater2();
            AssertUtils.assertNotNull(mInflater);
        }

        @Override
        public QuickSearchHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new QuickSearchHolder(mInflater.inflate(R.layout.item_quick_search, parent, false));
        }

        @Override
        public void onBindViewHolder(QuickSearchHolder holder, int position) {
            holder.label.setText(mVisibleQuickSearchList.get(position).name);
            holder.dragHandler.setVisibility(isFiltering() ? View.INVISIBLE : View.VISIBLE);
        }

        @Override
        public long getItemId(int position) {
            Long id = mVisibleQuickSearchList.get(position).getId();
            return id == null ? 0 : id;
        }

        @Override
        public int getItemCount() {
            return mVisibleQuickSearchList.size();
        }

        @Override
        public boolean onCheckCanStartDrag(QuickSearchHolder holder, int position, int x, int y) {
            return !isFiltering() && ViewUtils.isViewUnder(holder.dragHandler, x, y, 0);
        }

        @Override
        public ItemDraggableRange onGetItemDraggableRange(QuickSearchHolder holder, int position) {
            return null;
        }

        @Override
        public void onMoveItem(int fromPosition, int toPosition) {
            if (fromPosition == toPosition) {
                return;
            }
            if (null == mQuickSearchList) {
                return;
            }
            if (isFiltering()) {
                return;
            }

            EhDB.moveQuickSearch(fromPosition, toPosition);
            final QuickSearch item = mQuickSearchList.remove(fromPosition);
            mQuickSearchList.add(toPosition, item);
            mVisibleQuickSearchList.remove(fromPosition);
            mVisibleQuickSearchList.add(toPosition, item);
        }

        @Override
        public boolean onCheckCanDrop(int draggingPosition, int dropPosition) {
            return !isFiltering();
        }

        @Override
        public void onItemDragStarted(int position) {
        }

        @Override
        public void onItemDragFinished(int fromPosition, int toPosition, boolean result) {
        }
    }


}
