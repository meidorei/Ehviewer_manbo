package com.hippo.ehviewer.ui.scene.gallery.list;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.subscription.LocalFollowRepository;
import com.hippo.ehviewer.subscription.LocalGlobalCursorStore;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.subscription.LocalRefreshStatusFormatter;
import com.hippo.ehviewer.subscription.LocalUpdateService;

/** Consistent, explicit start confirmation dialogs for long update checks. */
final class LocalUpdateStartDialog {
    interface Starter {
        void start(String method);
    }

    private LocalUpdateStartDialog() {}

    static void showFollow(Context context, boolean recommendGlobal,
                           Starter starter) {
        View content = LayoutInflater.from(context).inflate(
                R.layout.dialog_local_follow_update, null, false);
        TextView history = content.findViewById(R.id.local_update_history);
        RadioGroup methods = content.findViewById(R.id.local_update_methods);
        RadioButton global = content.findViewById(R.id.local_update_method_global);
        RadioButton tags = content.findViewById(R.id.local_update_method_tags);
        int pageLimit = Settings.getGlobalScanPageLimit();

        history.setText(combinedHistory(context));
        setMethodText(context, global, recommendGlobal
                ? R.string.local_update_method_global_recommended
                : R.string.local_update_method_global, pageLimit);
        setMethodText(context, tags, recommendGlobal
                ? R.string.local_update_method_tags
                : R.string.local_update_method_tags_recommended);
        methods.check(recommendGlobal
                ? R.id.local_update_method_global : R.id.local_update_method_tags);

        new AlertDialog.Builder(context)
                .setTitle(R.string.local_update_all_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.local_update_start, (dialog, which) ->
                        starter.start(methods.getCheckedRadioButtonId()
                                == R.id.local_update_method_global
                                ? LocalUpdateService.METHOD_GLOBAL
                                : LocalUpdateService.METHOD_TAGS))
                .show();
    }

    private static String combinedHistory(Context context) {
        return context.getString(R.string.local_update_all_history,
                LocalFollowRepository.getInstance().getAll().size(), EhDB.getAllQuickSearch().size(),
                cursorText(context, LocalGlobalCursorStore.TYPE_FOLLOW),
                cursorText(context, LocalGlobalCursorStore.TYPE_BOOKMARK));
    }

    private static String cursorText(Context context, String type) {
        long time = LocalGlobalCursorStore.readCurrent(context, type).timeMillis();
        return time <= 0 ? context.getString(R.string.local_update_no_global_cursor)
                : LocalRefreshStatusFormatter.formatTime(time, System.currentTimeMillis());
    }

    private static void setMethodText(Context context, RadioButton button, int stringId,
                                      Object... formatArgs) {
        String value = context.getString(stringId, formatArgs);
        SpannableString styled = new SpannableString(value);
        int separator = value.indexOf('\n');
        int titleEnd = separator < 0 ? value.length() : separator;
        styled.setSpan(new StyleSpan(Typeface.BOLD), 0, titleEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (separator >= 0 && separator + 1 < value.length()) {
            TypedArray colors = context.obtainStyledAttributes(
                    new int[]{android.R.attr.textColorSecondary});
            int secondary = colors.getColor(0, button.getCurrentTextColor());
            colors.recycle();
            styled.setSpan(new ForegroundColorSpan(secondary), separator + 1, value.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new RelativeSizeSpan(0.87f), separator + 1, value.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        button.setText(styled);
        button.setLineSpacing(4f * context.getResources().getDisplayMetrics().density, 1f);
    }

    static void showBookmarks(Context context, boolean recommendGlobal, Starter starter) {
        View content = LayoutInflater.from(context).inflate(
                R.layout.dialog_local_follow_update, null, false);
        TextView history = content.findViewById(R.id.local_update_history);
        RadioGroup methods = content.findViewById(R.id.local_update_methods);
        RadioButton global = content.findViewById(R.id.local_update_method_global);
        RadioButton bookmarks = content.findViewById(R.id.local_update_method_tags);
        int pageLimit = Settings.getGlobalScanPageLimit();

        history.setText(combinedHistory(context));
        setMethodText(context, global, recommendGlobal
                ? R.string.local_update_method_global_recommended
                : R.string.local_update_method_global, pageLimit);
        setMethodText(context, bookmarks, recommendGlobal
                ? R.string.bookmark_update_method_each
                : R.string.bookmark_update_method_each_recommended);
        methods.check(recommendGlobal
                ? R.id.local_update_method_global : R.id.local_update_method_tags);

        new AlertDialog.Builder(context)
                .setTitle(R.string.local_update_all_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.local_update_start, (dialog, which) ->
                        starter.start(methods.getCheckedRadioButtonId()
                                == R.id.local_update_method_global
                                ? LocalUpdateService.METHOD_GLOBAL
                                : LocalUpdateService.METHOD_FIRST_PAGE))
                .show();
    }
}
