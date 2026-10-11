package com.hippo.ehviewer.widget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Two-row tag packing without Android dependencies. Widths are already measured in pixels. */
final class BookmarkTagLayout {
    interface OverflowWidth {
        int measure(int hiddenCount);
    }

    static Result arrange(int width, int[] widths, int height, int horizontalGap,
                          int verticalGap, OverflowWidth overflowWidth) {
        if (width <= 0 || widths.length == 0) {
            return new Result(Collections.emptyList(), 0, widths.length, 0);
        }
        List<Position> positions = pack(width, widths, height, horizontalGap, verticalGap);
        if (positions.size() == widths.length) {
            return result(positions, widths.length, 0, height);
        }
        for (int shown = positions.size(); shown >= 0; shown--) {
            int hidden = widths.length - shown;
            int[] candidates = Arrays.copyOf(widths, shown + 1);
            candidates[shown] = overflowWidth.measure(hidden);
            positions = pack(width, candidates, height, horizontalGap, verticalGap);
            if (positions.size() == candidates.length) {
                return result(positions, shown, hidden, height);
            }
        }
        return new Result(Collections.emptyList(), 0, widths.length, 0);
    }

    private static Result result(List<Position> positions, int shown, int hidden, int height) {
        int totalHeight = positions.isEmpty() ? 0
                : positions.get(positions.size() - 1).top + height;
        return new Result(positions, shown, hidden, totalHeight);
    }

    private static List<Position> pack(int width, int[] widths, int height,
                                       int horizontalGap, int verticalGap) {
        List<Position> positions = new ArrayList<>();
        int row = 0;
        int used = 0;
        boolean occupied = false;
        for (int measuredWidth : widths) {
            int chipWidth = Math.min(width, Math.max(0, measuredWidth));
            int start = occupied ? used + horizontalGap : 0;
            if (occupied && start + chipWidth > width) {
                if (++row == 2) break;
                start = 0;
            }
            positions.add(new Position(start, row * (height + verticalGap), chipWidth));
            used = start + chipWidth;
            occupied = true;
        }
        return positions;
    }

    static final class Position {
        final int left;
        final int top;
        final int width;

        Position(int left, int top, int width) {
            this.left = left;
            this.top = top;
            this.width = width;
        }
    }

    static final class Result {
        final List<Position> positions;
        final int shownCount;
        final int hiddenCount;
        final int height;

        Result(List<Position> positions, int shown, int hidden, int height) {
            this.positions = Collections.unmodifiableList(new ArrayList<>(positions));
            shownCount = shown;
            hiddenCount = hidden;
            this.height = height;
        }
    }
}
