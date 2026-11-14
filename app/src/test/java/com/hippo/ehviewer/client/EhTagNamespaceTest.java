package com.hippo.ehviewer.client;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/** Namespace mapping is independent of Android and the downloaded translation database. */
public class EhTagNamespaceTest {
    @Test public void locationUsesMultiLetterPrefix() {
        assertEquals("loc:", EhTagDatabase.namespaceToPrefix("location"));
        assertEquals("location", EhTagDatabase.prefixToNamespace("loc:"));
        assertEquals("l:", EhTagDatabase.namespaceToPrefix("language"));
        assertEquals("language", EhTagDatabase.prefixToNamespace("l:"));
    }
}
