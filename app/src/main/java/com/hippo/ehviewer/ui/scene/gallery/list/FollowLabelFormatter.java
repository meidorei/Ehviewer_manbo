package com.hippo.ehviewer.ui.scene.gallery.list;

import com.hippo.ehviewer.client.EhTagDatabase;

/** Builds a readable follow label without changing the raw query used by scanning. */
final class FollowLabelFormatter {

    private FollowLabelFormatter() {
    }

    static Presentation present(String rawQuery, EhTagDatabase database) {
        return present(rawQuery, database, null);
    }

    static Presentation present(String rawQuery, EhTagDatabase database,
                                String namespaceFallback) {
        String query = rawQuery == null ? "" : rawQuery;
        int separator = query.indexOf(':');
        if (separator <= 0 || separator >= query.length() - 1) {
            return new Presentation(query, query);
        }

        String namespace = query.substring(0, separator);
        String value = query.substring(separator + 1);
        String namespaceTranslation = null;
        String valueTranslation = null;
        if (database != null) {
            String canonicalNamespace = EhTagDatabase.prefixToNamespace(namespace + ":");
            if (canonicalNamespace == null) canonicalNamespace = namespace;
            namespaceTranslation = database.getTranslation("n:" + canonicalNamespace);

            String prefix = EhTagDatabase.namespaceToPrefix(namespace);
            if (namespace.length() == 1 && namespace.matches("^[a-z]+$")) {
                prefix = namespace + ":";
            }
            if (prefix != null) {
                valueTranslation = database.getTranslation(prefix + value);
            }
        }
        if (isEmpty(namespaceTranslation)) {
            namespaceTranslation = namespaceFallback;
        }

        return new Presentation(
                composeDisplayName(query, namespaceTranslation, valueTranslation),
                query);
    }

    static String composeDisplayName(String rawQuery, String namespaceTranslation,
                                     String valueTranslation) {
        String query = rawQuery == null ? "" : rawQuery;
        int separator = query.indexOf(':');
        if (separator <= 0 || separator >= query.length() - 1) return query;

        String namespace = query.substring(0, separator);
        String value = query.substring(separator + 1);
        String displayNamespace = isEmpty(namespaceTranslation)
                ? namespace : namespaceTranslation;
        String displayValue = isEmpty(valueTranslation) ? value : valueTranslation;
        return displayNamespace + ":" + displayValue;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    static final class Presentation {
        final String displayName;
        final String rawQuery;

        Presentation(String displayName, String rawQuery) {
            this.displayName = displayName;
            this.rawQuery = rawQuery;
        }
    }
}
