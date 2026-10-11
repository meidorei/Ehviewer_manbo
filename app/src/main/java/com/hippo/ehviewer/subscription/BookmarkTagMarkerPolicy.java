package com.hippo.ehviewer.subscription;

import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.QuickSearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only tag hints for browsing. Deliberately independent of update-query matching. */
public final class BookmarkTagMarkerPolicy {
    public static final BookmarkTagMarkerPolicy EMPTY =
            new BookmarkTagMarkerPolicy(Collections.emptyList());

    private final List<Rule> rules;

    private BookmarkTagMarkerPolicy(List<Rule> rules) {
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
    }

    /** Copies the bookmark values so later DAO mutations cannot change this snapshot. */
    public static BookmarkTagMarkerPolicy compile(List<QuickSearch> bookmarks,
                                                  boolean autoAppendChinese) {
        if (bookmarks == null || bookmarks.isEmpty()) return EMPTY;
        List<Rule> rules = new ArrayList<>();
        for (QuickSearch bookmark : bookmarks) {
            if (bookmark == null) continue;
            Set<String> positiveTags = new LinkedHashSet<>();
            if (bookmark.mode == ListUrlBuilder.MODE_TAG) {
                String tag = BookmarkQueryNormalizer.normalizeStandaloneTag(bookmark.keyword);
                if (tag == null) continue;
                positiveTags.add(tag);
            } else if (bookmark.mode == ListUrlBuilder.MODE_NORMAL
                    || bookmark.mode == ListUrlBuilder.MODE_FILTER) {
                BookmarkQueryNormalizer.ParseResult parsed =
                        BookmarkQueryNormalizer.parseExactQuery(bookmark.keyword);
                if (!parsed.exact || hasUploader(parsed)) continue;
                for (BookmarkQueryNormalizer.QueryToken token : parsed.tokens) {
                    if (!token.negative) positiveTags.add(token.canonical());
                }
            } else {
                continue;
            }
            if (positiveTags.isEmpty()) continue;

            // Use the browsing language contract, not the fixed-Chinese update contract.
            SearchQueryPolicy.Result query = SearchQueryPolicy.resolve(
                    bookmark.keyword, bookmark.mode, autoAppendChinese);
            Set<String> languages = new HashSet<>();
            for (String tag : positiveTags) {
                if (tag.startsWith("language:")) languages.add(tag);
            }
            if (query.chineseActuallyApplied) languages.add("language:chinese");
            String name = positiveTags.size() == 1 && bookmark.name != null
                    && !bookmark.name.trim().isEmpty() ? bookmark.name.trim() : null;
            rules.add(new Rule(positiveTags, languages, name));
        }
        return rules.isEmpty() ? EMPTY : new BookmarkTagMarkerPolicy(rules);
    }

    private static boolean hasUploader(BookmarkQueryNormalizer.ParseResult parsed) {
        for (BookmarkQueryNormalizer.QueryToken token : parsed.tokens) {
            if ("uploader".equals(token.namespace)) return true;
        }
        return false;
    }

    public int getRuleCount() {
        return rules.size();
    }

    /** Compact/extended HTML tags and API tags are both already available browsing data. */
    public List<Match> matchGallery(GalleryInfo gallery) {
        if (gallery == null) return Collections.emptyList();
        if (gallery.tgList == null || gallery.tgList.isEmpty()) return match(gallery.simpleTags);
        List<String> tags = new ArrayList<>(gallery.tgList);
        if (gallery.simpleTags != null) Collections.addAll(tags, gallery.simpleTags);
        return match(tags.toArray(new String[0]));
    }

    /** Null or incomplete metadata is never inferred from a title or fetched over the network. */
    public List<Match> match(String[] galleryTags) {
        if (galleryTags == null || galleryTags.length == 0 || rules.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> tags = new HashSet<>();
        for (String raw : galleryTags) {
            String tag = SubscriptionRepository.normalizeTagName(raw);
            if (LocalFollowJson.isValidStandardTag(tag)) tags.add(tag);
        }
        Map<String, Match> matches = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (!tags.containsAll(rule.languages)) continue;
            for (String tag : rule.tags) {
                if (!tags.contains(tag)) continue;
                Match previous = matches.get(tag);
                if (previous == null || (previous.bookmarkName == null && rule.name != null)) {
                    // Replacing a name preserves the tag's first matching bookmark position.
                    matches.put(tag, new Match(tag, rule.name));
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(matches.values()));
    }

    public interface TagTranslator {
        String translate(String tag);

        default String translateNamespace(String namespace) {
            return null;
        }
    }

    public static final class Match {
        public final String tag;
        /** Non-null only when a matching single-tag bookmark supplies its saved name. */
        public final String bookmarkName;

        private Match(String tag, String bookmarkName) {
            this.tag = tag;
            this.bookmarkName = bookmarkName;
        }

        /** Translate tag-shaped names without replacing a user's custom bookmark title. */
        public String displayLabel(boolean showTranslations, TagTranslator translator) {
            String original = bookmarkName != null ? bookmarkName : tag;
            if (!showTranslations || (bookmarkName != null
                    && !isTagName(translator))) {
                return original;
            }
            String translated = translator.translate(tag);
            return translated == null || translated.trim().isEmpty() || tag.equals(translated)
                    ? original : translated;
        }

        private boolean isTagName(TagTranslator translator) {
            if (tag.equals(BookmarkQueryNormalizer.normalizeStandaloneTag(bookmarkName))) {
                return true;
            }
            // Older saved names may translate only the namespace, e.g. 女性:focus handjob.
            int nameColon = bookmarkName.indexOf(':');
            if (nameColon < 0) nameColon = bookmarkName.indexOf('：');
            if (nameColon <= 0) return false;
            String namespace = tag.substring(0, tag.indexOf(':'));
            String restoredTag = BookmarkQueryNormalizer.normalizeStandaloneTag(
                    namespace + ':' + bookmarkName.substring(nameColon + 1));
            if (!tag.equals(restoredTag)) return false;
            String translatedNamespace = translator.translateNamespace(namespace);
            return translatedNamespace != null && translatedNamespace.trim().equals(
                    bookmarkName.substring(0, nameColon).trim());
        }
    }

    private static final class Rule {
        final Set<String> tags;
        final Set<String> languages;
        final String name;

        Rule(Set<String> tags, Set<String> languages, String name) {
            this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(tags));
            this.languages = Collections.unmodifiableSet(new HashSet<>(languages));
            this.name = name;
        }
    }
}
