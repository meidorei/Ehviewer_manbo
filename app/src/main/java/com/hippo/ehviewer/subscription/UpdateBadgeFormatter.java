package com.hippo.ehviewer.subscription;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.hippo.ehviewer.R;

/** Keeps "never checked" distinct from a successfully established zero baseline. */
public final class UpdateBadgeFormatter {
    private static final String SEPARATOR = "·";
    private static final String NEW_CONTENT_INDICATOR = "●";

    private UpdateBadgeFormatter() {}

    public static String format(String name, String badge) {
        String safeName = name == null ? "" : name;
        if (badge == null) return safeName;
        String separator = hasNewContent(badge) ? NEW_CONTENT_INDICATOR : SEPARATOR;
        return badge + "  " + separator + "  " + safeName;
    }

    public static CharSequence format(Context context, String name, String badge) {
        String formatted = format(name, badge);
        if (!hasNewContent(badge)) return formatted;

        int indicatorStart = formatted.indexOf(NEW_CONTENT_INDICATOR);
        SpannableString result = new SpannableString(formatted);
        result.setSpan(new ForegroundColorSpan(
                        ContextCompat.getColor(context, R.color.deep_green_600)),
                indicatorStart, indicatorStart + NEW_CONTENT_INDICATOR.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }

    public static Presentation present(String name, String badge) {
        return present(name, badge, null);
    }

    public static Presentation present(String name, String badge, String detail) {
        String safeName = name == null ? "" : name;
        String safeDetail = detail == null ? "" : detail;
        if (badge == null) {
            return new Presentation("", "", safeName, safeDetail, false, false);
        }
        boolean hasNewContent = hasNewContent(badge);
        return new Presentation(
                badge,
                hasNewContent ? NEW_CONTENT_INDICATOR : SEPARATOR,
                safeName,
                safeDetail,
                hasNewContent,
                !isCountBadge(badge));
    }

    public static void bind(Context context, TextView countView, TextView indicatorView,
                            TextView labelView, TextView detailView,
                            String name, String badge, String detail) {
        Presentation presentation = present(name, badge, detail);
        ViewGroup.LayoutParams countLayoutParams = countView.getLayoutParams();
        countLayoutParams.width = presentation.diagnostic
                ? ViewGroup.LayoutParams.WRAP_CONTENT
                : context.getResources().getDimensionPixelSize(R.dimen.update_badge_count_width);
        countView.setLayoutParams(countLayoutParams);
        countView.setText(presentation.count);
        indicatorView.setText(presentation.indicator);
        indicatorView.setTextColor(presentation.hasNewContent
                ? ContextCompat.getColor(context, R.color.deep_green_600)
                : labelView.getCurrentTextColor());
        labelView.setText(presentation.name);
        if (presentation.detail.isEmpty()) {
            detailView.setText("");
            detailView.setVisibility(View.GONE);
        } else {
            detailView.setText(presentation.detail);
            detailView.setVisibility(View.VISIBLE);
        }
        String description = format(presentation.name, badge);
        if (!presentation.detail.isEmpty()) {
            description += "  " + presentation.detail;
        }
        labelView.setContentDescription(description);
    }

    static boolean hasNewContent(String badge) {
        if ("20+".equals(badge)) return true;
        try {
            return Integer.parseInt(badge) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isCountBadge(String badge) {
        if ("20+".equals(badge)) return true;
        try {
            Integer.parseInt(badge);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public static final class Presentation {
        public final String count;
        public final String indicator;
        public final String name;
        public final String detail;
        public final boolean hasNewContent;
        public final boolean diagnostic;

        private Presentation(String count, String indicator, String name, String detail,
                             boolean hasNewContent, boolean diagnostic) {
            this.count = count;
            this.indicator = indicator;
            this.name = name;
            this.detail = detail;
            this.hasNewContent = hasNewContent;
            this.diagnostic = diagnostic;
        }
    }
}
