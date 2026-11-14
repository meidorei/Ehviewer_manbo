package com.hippo.ehviewer.jm;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JmIdParser {
    private static final Pattern PREFIX = Pattern.compile("(?i)^jm(\\d+)$");
    private static final Pattern PATH =
            Pattern.compile("(?i)(?:^|/)(?:photos?|albums?)/(?:\\?[^#]*&)?(\\d+)(?:[/?#]|$)");
    private static final Pattern PATH_SIMPLE =
            Pattern.compile("(?i)(?:photos?|albums?)/(\\d+)");
    private static final Pattern QUERY = Pattern.compile("(?i)[?&]id=(\\d+)(?:[&#]|$)");

    private JmIdParser() {
    }

    public static String parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("JM number is empty");
        }
        String text = value.trim();
        if (text.matches("\\d+")) {
            return normalize(text);
        }

        Matcher matcher = PREFIX.matcher(text);
        if (matcher.matches()) {
            return normalize(matcher.group(1));
        }
        matcher = PATH.matcher(text);
        if (matcher.find()) {
            return normalize(matcher.group(1));
        }
        matcher = PATH_SIMPLE.matcher(text);
        if (matcher.find()) {
            return normalize(matcher.group(1));
        }
        matcher = QUERY.matcher(text);
        if (matcher.find()) {
            return normalize(matcher.group(1));
        }
        throw new IllegalArgumentException(
                String.format(Locale.US, "Cannot parse JM number: %s", text));
    }

    private static String normalize(String id) {
        int firstNonZero = 0;
        while (firstNonZero < id.length() - 1 && id.charAt(firstNonZero) == '0') {
            firstNonZero++;
        }
        String normalized = id.substring(firstNonZero);
        if ("0".equals(normalized)) {
            throw new IllegalArgumentException("JM number must be greater than zero");
        }
        return normalized;
    }
}
