package com.hippo.ehviewer.ui.scene.gallery.list;

import static com.hippo.ehviewer.event.SomethingNeedRefresh.bookmarkDrawNeedRefresh;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.dao.QuickSearch;
import com.hippo.ehviewer.subscription.BookmarkDiagnostics;
import com.hippo.ehviewer.subscription.BookmarkGlobalMatcher;
import com.hippo.ehviewer.ui.scene.ToolbarScene;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only bookmark diagnostics with explicit per-item duplicate deletion. */
public final class BookmarkDiagnosticsScene extends ToolbarScene {
    private static final int TYPE_SUMMARY = 0;
    private static final int TYPE_SECTION = 1;
    private static final int TYPE_GROUP = 2;
    private static final int TYPE_ENTRY = 3;
    private static final int TYPE_EMPTY = 4;

    private final List<Row> rows = new ArrayList<>();
    private final Set<Long> expanded = new HashSet<>();
    private DiagnosticsAdapter adapter;

    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        RecyclerView recycler = (RecyclerView) inflater.inflate(
                R.layout.scene_bookmark_diagnostics, container, false);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new DiagnosticsAdapter(inflater);
        recycler.setAdapter(adapter);
        reloadReport();
        return recycler;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.bookmark_diagnostics_title);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    private void reloadReport() {
        BookmarkDiagnostics.Result result =
                BookmarkDiagnostics.analyze(EhDB.getAllQuickSearch());
        rows.clear();
        rows.add(Row.summary(result));
        if (result.duplicateGroups.isEmpty() && result.fallbackItems.isEmpty()) {
            rows.add(Row.empty());
        }
        if (!result.duplicateGroups.isEmpty()) {
            rows.add(Row.section(getString(R.string.bookmark_diagnostics_duplicates_section,
                    result.duplicateGroups.size())));
            int groupIndex = 1;
            for (BookmarkDiagnostics.DuplicateGroup group : result.duplicateGroups) {
                rows.add(Row.group(getString(R.string.bookmark_diagnostics_group_title,
                        groupIndex++, group.bookmarks.size()), group.canonicalQuery));
                for (QuickSearch bookmark : group.bookmarks) {
                    rows.add(Row.entry(bookmark, true, null));
                }
            }
        }
        if (!result.fallbackItems.isEmpty()) {
            rows.add(Row.section(getString(R.string.bookmark_diagnostics_fallback_section,
                    result.fallbackItems.size())));
            for (BookmarkDiagnostics.FallbackItem item : result.fallbackItems) {
                rows.add(Row.entry(item.bookmark, false, item.reason));
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void confirmDelete(QuickSearch bookmark) {
        Context context = getEHContext();
        if (context == null || bookmark == null) return;
        String name = bookmark.name == null ? "" : bookmark.name;
        String query = bookmark.keyword == null ? "" : bookmark.keyword;
        new AlertDialog.Builder(context)
                .setTitle(R.string.delete_quick_search_title)
                .setMessage(getString(R.string.bookmark_diagnostics_delete_message, name, query))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    QuickSearchDeleteHelper.delete(bookmark);
                    if (bookmark.id != null) expanded.remove(bookmark.id);
                    EventBus.getDefault().post(bookmarkDrawNeedRefresh());
                    Toast.makeText(context, R.string.bookmark_diagnostics_deleted,
                            Toast.LENGTH_SHORT).show();
                    reloadReport();
                })
                .show();
    }

    private int fallbackReason(BookmarkGlobalMatcher.FallbackReason reason) {
        if (reason == null) return R.string.bookmark_diagnostics_reason_unknown;
        switch (reason) {
            case TAG_QUERY_NOT_EXACT:
                return R.string.bookmark_diagnostics_reason_tag;
            case EMPTY_UPLOADER:
                return R.string.bookmark_diagnostics_reason_empty_uploader;
            case COMPLEX_OPERATOR:
                return R.string.bookmark_diagnostics_reason_operator;
            case FULL_TEXT_KEYWORD:
                return R.string.bookmark_diagnostics_reason_full_text;
            case FUZZY_EXPRESSION:
                return R.string.bookmark_diagnostics_reason_fuzzy;
            case NEGATIVE_UPLOADER:
                return R.string.bookmark_diagnostics_reason_negative_uploader;
            case UNSUPPORTED_FIELD:
                return R.string.bookmark_diagnostics_reason_field;
            case INVALID_BOOKMARK:
            case UNSUPPORTED_MODE:
            case NONE:
            default:
                return R.string.bookmark_diagnostics_reason_unknown;
        }
    }

    private final class DiagnosticsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final LayoutInflater inflater;

        DiagnosticsAdapter(LayoutInflater inflater) {
            this.inflater = inflater;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(
                @NonNull ViewGroup parent, int viewType) {
            switch (viewType) {
                case TYPE_SUMMARY:
                    return new SummaryHolder(inflater.inflate(
                            R.layout.item_bookmark_diagnostics_summary, parent, false));
                case TYPE_SECTION:
                    return new SectionHolder(inflater.inflate(
                            R.layout.item_bookmark_diagnostics_section, parent, false));
                case TYPE_GROUP:
                    return new GroupHolder(inflater.inflate(
                            R.layout.item_bookmark_diagnostics_group, parent, false));
                case TYPE_EMPTY:
                    return new EmptyHolder(inflater.inflate(
                            R.layout.item_bookmark_diagnostics_empty, parent, false));
                case TYPE_ENTRY:
                default:
                    return new EntryHolder(inflater.inflate(
                            R.layout.item_bookmark_diagnostics_entry, parent, false));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (holder instanceof SummaryHolder) {
                BookmarkDiagnostics.Result report = row.report;
                ((SummaryHolder) holder).detail.setText(getString(
                        R.string.bookmark_diagnostics_summary_detail,
                        report.totalBookmarks, report.duplicateGroups.size(),
                        report.duplicateBookmarks, report.fallbackItems.size()));
            } else if (holder instanceof SectionHolder) {
                ((SectionHolder) holder).text.setText(row.title);
            } else if (holder instanceof GroupHolder) {
                GroupHolder group = (GroupHolder) holder;
                group.title.setText(row.title);
                group.query.setText(row.detail);
            } else if (holder instanceof EntryHolder) {
                bindEntry((EntryHolder) holder, row);
            }
        }

        private void bindEntry(EntryHolder holder, Row row) {
            QuickSearch bookmark = row.bookmark;
            String name = bookmark.name == null ? "" : bookmark.name;
            String query = bookmark.keyword == null ? "" : bookmark.keyword;
            holder.name.setText(name);
            holder.status.setText(row.duplicate
                    ? R.string.bookmark_diagnostics_status_duplicate
                    : R.string.bookmark_diagnostics_status_fallback);
            holder.query.setText(query);
            long id = bookmark.id == null ? Long.MIN_VALUE : bookmark.id;
            holder.query.setMaxLines(expanded.contains(id) ? Integer.MAX_VALUE : 2);
            holder.content.setContentDescription(getString(
                    R.string.bookmark_diagnostics_expand_query, name));
            holder.content.setOnClickListener(view -> {
                if (expanded.contains(id)) expanded.remove(id);
                else expanded.add(id);
                notifyItemChanged(holder.getBindingAdapterPosition());
            });
            if (row.duplicate) {
                holder.reason.setVisibility(View.GONE);
                holder.delete.setVisibility(View.VISIBLE);
                holder.delete.setContentDescription(getString(
                        R.string.bookmark_diagnostics_delete_accessibility, name));
                holder.delete.setOnClickListener(view -> confirmDelete(bookmark));
            } else {
                holder.reason.setVisibility(View.VISIBLE);
                holder.reason.setText(fallbackReason(row.reason));
                holder.delete.setVisibility(View.GONE);
                holder.delete.setOnClickListener(null);
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class SummaryHolder extends RecyclerView.ViewHolder {
        final TextView detail;

        SummaryHolder(View view) {
            super(view);
            detail = view.findViewById(R.id.bookmark_diagnostics_summary_detail);
        }
    }

    private static final class SectionHolder extends RecyclerView.ViewHolder {
        final TextView text;

        SectionHolder(View view) {
            super(view);
            text = view.findViewById(R.id.bookmark_diagnostics_section);
        }
    }

    private static final class GroupHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView query;

        GroupHolder(View view) {
            super(view);
            title = view.findViewById(R.id.bookmark_diagnostics_group_title);
            query = view.findViewById(R.id.bookmark_diagnostics_group_query);
        }
    }

    private static final class EntryHolder extends RecyclerView.ViewHolder {
        final View content;
        final TextView name;
        final TextView status;
        final TextView query;
        final TextView reason;
        final ImageButton delete;

        EntryHolder(View view) {
            super(view);
            content = view.findViewById(R.id.bookmark_diagnostics_entry_content);
            name = view.findViewById(R.id.bookmark_diagnostics_entry_name);
            status = view.findViewById(R.id.bookmark_diagnostics_entry_status);
            query = view.findViewById(R.id.bookmark_diagnostics_entry_query);
            reason = view.findViewById(R.id.bookmark_diagnostics_entry_reason);
            delete = view.findViewById(R.id.bookmark_diagnostics_entry_delete);
        }
    }

    private static final class EmptyHolder extends RecyclerView.ViewHolder {
        EmptyHolder(View view) {
            super(view);
        }
    }

    private static final class Row {
        final int type;
        final BookmarkDiagnostics.Result report;
        final String title;
        final String detail;
        final QuickSearch bookmark;
        final boolean duplicate;
        final BookmarkGlobalMatcher.FallbackReason reason;

        private Row(int type, BookmarkDiagnostics.Result report, String title, String detail,
                    QuickSearch bookmark, boolean duplicate,
                    BookmarkGlobalMatcher.FallbackReason reason) {
            this.type = type;
            this.report = report;
            this.title = title;
            this.detail = detail;
            this.bookmark = bookmark;
            this.duplicate = duplicate;
            this.reason = reason;
        }

        static Row summary(BookmarkDiagnostics.Result report) {
            return new Row(TYPE_SUMMARY, report, null, null, null, false, null);
        }

        static Row section(String title) {
            return new Row(TYPE_SECTION, null, title, null, null, false, null);
        }

        static Row group(String title, String query) {
            return new Row(TYPE_GROUP, null, title, query, null, false, null);
        }

        static Row entry(QuickSearch bookmark, boolean duplicate,
                         BookmarkGlobalMatcher.FallbackReason reason) {
            return new Row(TYPE_ENTRY, null, null, null, bookmark, duplicate, reason);
        }

        static Row empty() {
            return new Row(TYPE_EMPTY, null, null, null, null, false, null);
        }
    }
}
