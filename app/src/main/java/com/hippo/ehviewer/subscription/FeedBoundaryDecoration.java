package com.hippo.ehviewer.subscription;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.function.BooleanSupplier;

public final class FeedBoundaryDecoration extends RecyclerView.ItemDecoration {
    public interface ItemProvider { GalleryInfo get(int adapterPosition); }

    private final ItemProvider provider;
    private final BooleanSupplier homeMode;
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile String label;
    private final int height;
    private volatile FeedBoundary boundary = FeedBoundary.EMPTY;
    private int markerAfter = RecyclerView.NO_POSITION;

    public FeedBoundaryDecoration(float density, float scaledDensity, int color,
                                  String label, ItemProvider provider, BooleanSupplier homeMode) {
        this.provider = provider;
        this.homeMode = homeMode;
        this.label = label;
        this.height = markerHeightPx(density);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(lineWidthPx(density));
        textPaint.setColor(color);
        textPaint.setTextSize(textSizePx(scaledDensity));
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    static int markerHeightPx(float density) {
        return Math.round(32 * density);
    }

    static float lineWidthPx(float density) {
        return Math.max(1, 2 * density);
    }

    static float textSizePx(float scaledDensity) {
        return 14 * scaledDensity;
    }

    static boolean isWithinMarkerTouchBounds(float touchY, int childTop, int markerHeight) {
        return touchY >= childTop - markerHeight && touchY < childTop;
    }

    public void setBoundary(FeedBoundary value) {
        boundary = value == null ? FeedBoundary.EMPTY : value;
    }

    public void setMarkerAfter(int position) {
        markerAfter = position;
    }

    public void setLabel(String value) {
        label = value == null ? "" : value;
    }

    private boolean isMarker(int position) {
        GalleryInfo item = provider.get(position);
        if (item == null) return false;
        GalleryInfo previous = position > 0 ? provider.get(position - 1) : null;
        return FeedMarkerPolicy.isMarkerBefore(boundary, item, previous, homeMode.getAsBoolean());
    }

    public boolean isInMarkerTouchArea(@NonNull RecyclerView parent, float touchX, float touchY) {
        if (touchX < parent.getPaddingLeft()
                || touchX >= parent.getWidth() - parent.getPaddingRight()) return false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position < 0 || !isMarker(position)) continue;
            return isWithinMarkerTouchBounds(touchY, child.getTop(), height);
        }
        return false;
    }

    @Override public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                         @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position < 0) return;
        if (position == markerAfter) {
            outRect.bottom = height;
        } else if (isMarker(position)) {
            outRect.top = height;
        }
    }

    @Override public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
                                 @NonNull RecyclerView.State state) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position < 0) continue;
            boolean after = position == markerAfter;
            if (!after && !isMarker(position)) continue;
            float y = after ? child.getBottom() + height / 2f : child.getTop() - height / 2f;
            float left = after ? child.getLeft() : parent.getPaddingLeft();
            float right = after ? child.getRight() : parent.getWidth() - parent.getPaddingRight();
            float center = (left + right) / 2f;
            float gap = textPaint.measureText(label) / 2f + 12 * parent.getResources().getDisplayMetrics().density;
            canvas.save();
            canvas.clipRect(left, y - height / 2f, right, y + height / 2f);
            canvas.drawLine(left, y, Math.max(left, center - gap), y, linePaint);
            canvas.drawText(label, center, y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint);
            canvas.drawLine(Math.min(right, center + gap), y, right, y, linePaint);
            canvas.restore();
            break;
        }
    }
}
