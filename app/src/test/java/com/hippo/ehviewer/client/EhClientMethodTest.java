package com.hippo.ehviewer.client;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

public class EhClientMethodTest {
    @Test
    public void requestMethodsHaveUniqueIds() throws Exception {
        Set<Integer> ids = new HashSet<>();
        for (Field field : EhClient.class.getFields()) {
            if (field.getName().startsWith("METHOD_") && field.getType() == int.class) {
                assertTrue("Duplicate request ID: " + field.getName(), ids.add(field.getInt(null)));
            }
        }
        assertTrue("Request methods should be discovered", ids.size() > 20);
    }

    @Test
    public void editingCommentsCannotDispatchSubscriptionScan() {
        assertNotEquals(EhClient.METHOD_SCAN_SUBSCRIPTIONS, EhClient.METHOD_GET_EDIT_COMMENT);
    }
}
