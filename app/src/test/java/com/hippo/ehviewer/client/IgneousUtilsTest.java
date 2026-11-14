/*
 * Copyright 2026 EhViewer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import okhttp3.Request;
import org.junit.Test;

public class IgneousUtilsTest {

    @Test
    public void validatesIpAddresses() {
        assertTrue(IgneousUtils.isValidIpAddress("192.0.2.1"));
        assertTrue(IgneousUtils.isValidIpAddress("2a09:bac1:7680::1"));
        assertTrue(IgneousUtils.isValidIpAddress("::ffff:192.0.2.1"));
        assertFalse(IgneousUtils.isValidIpAddress(""));
        assertFalse(IgneousUtils.isValidIpAddress("999.1.1.1"));
        assertFalse(IgneousUtils.isValidIpAddress("example.com"));
        assertFalse(IgneousUtils.isValidIpAddress("2a09:not-an-ip"));
    }

    @Test
    public void disabledRefreshDoesNotAddHeader() {
        Request request = IgneousUtils.buildRefreshRequest(false, "not-an-ip");

        assertNull(request.header(IgneousUtils.HEADER_CF_CONNECTING_IP));
        assertEquals(EhUrl.DOMAIN_EX, request.url().host());
    }

    @Test
    public void enabledRefreshAddsConfiguredHeaderImmediately() {
        Request request =
                IgneousUtils.buildRefreshRequest(true, " 2a09:bac1:7680::1 ");

        assertEquals("2a09:bac1:7680::1",
                request.header(IgneousUtils.HEADER_CF_CONNECTING_IP));
    }

    @Test
    public void invalidEnabledConfigurationDoesNotBuildRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> IgneousUtils.buildRefreshRequest(true, "not-an-ip"));
    }

    @Test
    public void onlyAcceptsNonMysteryIgneous() {
        assertTrue(IgneousUtils.isUsableIgneous("fresh-token"));
        assertFalse(IgneousUtils.isUsableIgneous(null));
        assertFalse(IgneousUtils.isUsableIgneous(""));
        assertFalse(IgneousUtils.isUsableIgneous("mystery"));
        assertFalse(IgneousUtils.isUsableIgneous("MYSTERY"));
    }
}
