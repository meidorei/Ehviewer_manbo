package com.hippo.ehviewer.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.hippo.ehviewer.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Non-interactive, individually framed tags, with a two-row limit and an overflow count. */
public final class BookmarkTagGroup extends ViewGroup {
    private List<String> labels = Collections.emptyList();
    private BookmarkTagLayout.Result arranged;
    private int chipHeight;
    private final int gap;

    public BookmarkTagGroup(Context context, AttributeSet attrs) {
        super(context, attrs);
        gap = Math.round(4 * getResources().getDisplayMetrics().density);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setLabels(List<String> names) {
        if (labels.equals(names)) return;
        labels = new ArrayList<>(names);
        arranged = null;
        // The last child is reserved for +N. Reuse existing children when cards are rebound.
        while (!labels.isEmpty() && getChildCount() < labels.size() + 1) {
            TextView chip = (TextView) LayoutInflater.from(getContext()).inflate(
                    R.layout.item_bookmark_tag_chip, this, false);
            chip.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(chip);
        }
        for (int i = 0; i < getChildCount(); i++) {
            TextView chip = (TextView) getChildAt(i);
            chip.setText(i < labels.size() ? labels.get(i) : null);
            chip.setVisibility(i < labels.size() ? VISIBLE : GONE);
        }
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = Math.max(0, MeasureSpec.getSize(widthMeasureSpec)
                - getPaddingLeft() - getPaddingRight());
        int childWidthSpec = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        int[] widths = new int[labels.size()];
        chipHeight = 0;
        for (int i = 0; i < labels.size(); i++) {
            View chip = getChildAt(i);
            chip.measure(childWidthSpec, childHeightSpec);
            widths[i] = chip.getMeasuredWidth();
            chipHeight = Math.max(chipHeight, chip.getMeasuredHeight());
        }
        arranged = BookmarkTagLayout.arrange(available, widths, chipHeight, gap, gap, hidden -> {
            TextView overflow = (TextView) getChildAt(labels.size());
            String count = "+" + hidden;
            if (!count.contentEquals(overflow.getText())) overflow.setText(count);
            overflow.measure(childWidthSpec, childHeightSpec);
            return overflow.getMeasuredWidth();
        });
        for (int i = 0; i < getChildCount(); i++) {
            boolean visible = i < arranged.shownCount
                    || (i == labels.size() && arranged.hiddenCount > 0
                    && !arranged.positions.isEmpty());
            getChildAt(i).setVisibility(visible ? VISIBLE : GONE);
        }
        setMeasuredDimension(resolveSize(available + getPaddingLeft() + getPaddingRight(),
                        widthMeasureSpec),
                resolveSize(arranged.height + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (arranged == null) return;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < arranged.positions.size(); i++) {
            BookmarkTagLayout.Position position = arranged.positions.get(i);
            View chip = getChildAt(i < arranged.shownCount ? i : labels.size());
            int x = rtl ? getWidth() - getPaddingRight() - position.left - position.width
                    : getPaddingLeft() + position.left;
            int y = getPaddingTop() + position.top;
            chip.layout(x, y, x + position.width, y + chipHeight);
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }
}
