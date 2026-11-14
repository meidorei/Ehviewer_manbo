package com.hippo.ehviewer.reader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure ordering and retention rules for the reading queue. */
public final class ReadingQueuePolicy {
    public static final int MIN_CAPACITY = 1;
    public static final int MAX_CAPACITY = 100;
    public static final int DEFAULT_CAPACITY = 20;

    private ReadingQueuePolicy() {}

    public static boolean isValidCapacity(int capacity) {
        return capacity >= MIN_CAPACITY && capacity <= MAX_CAPACITY;
    }

    public static Integer parseCapacity(String value) {
        if (value == null) {
            return null;
        }
        try {
            int capacity = Integer.parseInt(value.trim());
            return isValidCapacity(capacity) ? capacity : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Returns the number requiring confirmation for a settings transition. */
    public static int confirmationDeletionCount(boolean oldEnabled, int oldCapacity,
            boolean newEnabled, int newCapacity, int queueSize) {
        if (!newEnabled || (oldEnabled && newCapacity >= oldCapacity)) {
            return 0;
        }
        return overflowCount(queueSize, newCapacity);
    }

    public static int overflowCount(int size, int capacity) {
        if (!isValidCapacity(capacity)) {
            return 0;
        }
        return Math.max(0, size - capacity);
    }

    /** Returns oldest-first candidates from a newest-first queue snapshot. */
    public static List<Long> oldestOverflow(List<Long> newestFirst, int capacity) {
        int count = overflowCount(newestFirst.size(), capacity);
        if (count == 0) {
            return Collections.emptyList();
        }
        List<Long> result = new ArrayList<>(newestFirst.subList(newestFirst.size() - count,
                newestFirst.size()));
        Collections.reverse(result);
        return result;
    }
}
