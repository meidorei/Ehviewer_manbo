package com.hippo.ehviewer.ui.scene.gallery.list;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.subscription.LocalFollowRepository;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.util.DrawableManager;
import com.hippo.view.ViewTransition;
import com.hippo.lib.yorozuya.ViewUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable/delete-only management screen for local follows. */
public final class LocalFollowScene extends ToolbarScene {
    private final List<String> all = new ArrayList<>();
    private final List<String> visible = new ArrayList<>();
    private ViewTransition transition;
    private FollowAdapter adapter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        all.addAll(LocalFollowRepository.getInstance().getAll());
        visible.addAll(all);
    }

    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_label_list, container, false);
        EasyRecyclerView recycler = (EasyRecyclerView) ViewUtils.$$(view, R.id.recycler_view);
        TextView tip = (TextView) ViewUtils.$$(view, R.id.tip);
        Drawable drawable = DrawableManager.getVectorDrawable(requireContext(), R.drawable.big_search);
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        tip.setCompoundDrawables(null, drawable, null, null);
        tip.setText(R.string.local_follow_empty);
        transition = new ViewTransition(recycler, tip);
        adapter = new FollowAdapter(requireContext(), inflater);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);
        updateEmpty();
        return view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.local_follow);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public int getMenuResId() {
        return R.menu.local_follow_management;
    }

    @Override
    public void onMenuCreated(Menu menu) {
        SearchView search = (SearchView) menu.findItem(R.id.action_search).getActionView();
        search.setQueryHint(getString(R.string.search));
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) { return true; }
            @Override public boolean onQueryTextChange(String query) {
                filter(query);
                return true;
            }
        });
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    private void filter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (String tag : all) {
            if (needle.isEmpty() || tag.contains(needle)) visible.add(tag);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateEmpty();
    }

    private void updateEmpty() {
        if (transition != null) transition.showView(visible.isEmpty() ? 1 : 0);
    }

    private final class FollowAdapter extends RecyclerView.Adapter<FollowHolder> {
        private final Context context;
        private final LayoutInflater inflater;

        FollowAdapter(Context context, LayoutInflater inflater) {
            this.context = context;
            this.inflater = inflater;
            setHasStableIds(true);
        }

        @Override public FollowHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new FollowHolder(inflater.inflate(R.layout.item_quick_search, parent, false));
        }

        @Override public void onBindViewHolder(FollowHolder holder, int position) {
            String tag = visible.get(position);
            holder.label.setText(tag);
            holder.delete.setOnClickListener(v -> new AlertDialog.Builder(context)
                    .setTitle(R.string.local_unfollow)
                    .setMessage(getString(R.string.delete_quick_search_message, tag))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        LocalFollowRepository.getInstance().remove(tag);
                        all.remove(tag);
                        visible.remove(tag);
                        notifyDataSetChanged();
                        updateEmpty();
                    }).show());
        }

        @Override public int getItemCount() { return visible.size(); }
        @Override public long getItemId(int position) { return visible.get(position).hashCode(); }
    }

    private static final class FollowHolder extends RecyclerView.ViewHolder {
        final TextView label;
        final View delete;

        FollowHolder(View itemView) {
            super(itemView);
            label = (TextView) ViewUtils.$$(itemView, R.id.label);
            delete = ViewUtils.$$(itemView, R.id.delete);
            View drag = ViewUtils.$$(itemView, R.id.drag_handler);
            drag.setVisibility(View.INVISIBLE);
        }
    }
}
