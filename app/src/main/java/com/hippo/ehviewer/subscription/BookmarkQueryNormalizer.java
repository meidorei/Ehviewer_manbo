package com.hippo.ehviewer.subscription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Shared exact-query parsing used by global matching and bookmark diagnostics. */
final class BookmarkQueryNormalizer {
    private BookmarkQueryNormalizer() {}

    static ParseResult parseExactQuery(String raw) {
        return parseExactQuery(raw, false);
    }

    /** Namespace aliases are opt-in for homepage hints; update/diagnostic callers stay unchanged. */
    static ParseResult parseExactQuery(String raw, boolean allowNamespaceAliases) {
        TokenizeResult tokenized = tokenize(raw);
        if (!tokenized.balanced) {
            return ParseResult.fallback(BookmarkGlobalMatcher.FallbackReason.COMPLEX_OPERATOR);
        }
        List<QueryToken> parsed = new ArrayList<>();
        for (String rawToken : tokenized.tokens) {
            String token = rawToken.trim();
            boolean negative = token.startsWith("-");
            if (negative) token = token.substring(1).trim();
            token = unwrapWholeExpression(token);
            if (token.isEmpty() || token.startsWith("~")
                    || "or".equalsIgnoreCase(token)) {
                return ParseResult.fallback(
                        BookmarkGlobalMatcher.FallbackReason.COMPLEX_OPERATOR);
            }
            int colon = token.indexOf(':');
            if (colon <= 0 || colon == token.length() - 1) {
                return ParseResult.fallback(
                        BookmarkGlobalMatcher.FallbackReason.FULL_TEXT_KEYWORD);
            }
            String namespace = normalizeNamespace(token.substring(0, colon), allowNamespaceAliases);
            String value = cleanValue(token.substring(colon + 1));
            if (value.isEmpty() || containsFuzzyOperator(value)) {
                return ParseResult.fallback(
                        BookmarkGlobalMatcher.FallbackReason.FUZZY_EXPRESSION);
            }
            if ("uploader".equals(namespace)) {
                if (negative) {
                    return ParseResult.fallback(
                            BookmarkGlobalMatcher.FallbackReason.NEGATIVE_UPLOADER);
                }
                parsed.add(new QueryToken(false, namespace, value));
                continue;
            }
            String tag = normalizeTag(namespace + ':' + value);
            if (tag == null) {
                return ParseResult.fallback(
                        BookmarkGlobalMatcher.FallbackReason.UNSUPPORTED_FIELD);
            }
            int tagColon = tag.indexOf(':');
            parsed.add(new QueryToken(
                    negative, tag.substring(0, tagColon), tag.substring(tagColon + 1)));
        }
        return ParseResult.exact(parsed);
    }

    static String normalizeStandaloneTag(String raw) {
        return normalizeStandaloneTag(raw, false);
    }

    static String normalizeStandaloneTag(String raw, boolean allowNamespaceAliases) {
        String expression = unwrapWholeExpression(raw);
        int colon = expression.indexOf(':');
        if (colon <= 0 || colon == expression.length() - 1) return null;
        String namespace = normalizeNamespace(expression.substring(0, colon), allowNamespaceAliases);
        String value = cleanValue(expression.substring(colon + 1));
        if (value.isEmpty() || containsFuzzyOperator(value)) return null;
        return normalizeTag(namespace + ':' + value);
    }

    static String cleanValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = removeTrailingDollar(value);
        if (hasOuterQuotes(value)) {
            value = value.substring(1, value.length() - 1).trim();
            value = removeTrailingDollar(value);
        }
        return value.replaceAll("\\s+", " ");
    }

    static String conservativeKeyword(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    static String canonicalExactQuery(List<QueryToken> tokens) {
        TreeSet<String> sorted = new TreeSet<>();
        for (QueryToken token : tokens) sorted.add(token.canonical());
        return String.join(" ", sorted);
    }

    private static String unwrapWholeExpression(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = removeTrailingDollar(value);
        if (hasOuterQuotes(value)) {
            value = value.substring(1, value.length() - 1).trim();
            value = removeTrailingDollar(value);
        }
        return value;
    }

    private static String removeTrailingDollar(String value) {
        String result = value;
        while (result.endsWith("$")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private static boolean hasOuterQuotes(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"");
    }

    private static String normalizeNamespace(String raw, boolean allowNamespaceAliases) {
        String namespace = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("l".equals(namespace) || "lang".equals(namespace)) return "language";
        if (allowNamespaceAliases) {
            switch (namespace) {
                case "a": return "artist";
                case "c":
                case "char": return "character";
                case "cos": return "cosplayer";
                case "f": return "female";
                case "g":
                case "circle": return "group";
                case "loc": return "location";
                case "m": return "male";
                case "x": return "mixed";
                case "o": return "other";
                case "p":
                case "series": return "parody";
                case "r": return "reclass";
            }
        }
        return namespace;
    }

    private static String normalizeTag(String raw) {
        String tag = SubscriptionRepository.normalizeTagName(raw);
        return LocalFollowJson.isValidStandardTag(tag) ? tag : null;
    }

    private static boolean containsFuzzyOperator(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0
                || value.indexOf('~') >= 0;
    }

    private static TokenizeResult tokenize(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new TokenizeResult(Collections.emptyList(), true);
        }
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                current.append(c);
            } else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) result.add(current.toString());
        return new TokenizeResult(result, !quoted);
    }

    static final class QueryToken {
        final boolean negative;
        final String namespace;
        final String value;

        QueryToken(boolean negative, String namespace, String value) {
            this.negative = negative;
            this.namespace = namespace;
            this.value = value;
        }

        String canonical() {
            return (negative ? "-" : "") + namespace + ':'
                    + value.toLowerCase(Locale.ROOT);
        }
    }

    static final class ParseResult {
        final boolean exact;
        final BookmarkGlobalMatcher.FallbackReason reason;
        final List<QueryToken> tokens;

        private ParseResult(boolean exact, BookmarkGlobalMatcher.FallbackReason reason,
                            List<QueryToken> tokens) {
            this.exact = exact;
            this.reason = reason;
            this.tokens = tokens;
        }

        static ParseResult exact(List<QueryToken> tokens) {
            return new ParseResult(true, BookmarkGlobalMatcher.FallbackReason.NONE,
                    Collections.unmodifiableList(new ArrayList<>(tokens)));
        }

        static ParseResult fallback(BookmarkGlobalMatcher.FallbackReason reason) {
            return new ParseResult(false, reason, Collections.emptyList());
        }
    }

    private static final class TokenizeResult {
        final List<String> tokens;
        final boolean balanced;

        TokenizeResult(List<String> tokens, boolean balanced) {
            this.tokens = tokens;
            this.balanced = balanced;
        }
    }
}
