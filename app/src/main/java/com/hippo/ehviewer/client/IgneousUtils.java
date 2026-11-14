/*
 * Copyright 2026 EhViewer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.client;

import androidx.annotation.Nullable;

import com.hippo.network.InetValidator;

import java.net.InetAddress;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.Request;

/**
 * Helpers for the explicitly requested IGNEOUS refresh.
 */
public final class IgneousUtils {

    public static final String MYSTERY = "mystery";
    public static final String HEADER_CF_CONNECTING_IP = "CF-Connecting-IP";

    private IgneousUtils() {
    }

    public static Request buildRefreshRequest(boolean useCfConnectingIp,
            @Nullable String configuredIp) {
        Request request = new EhRequestBuilder(EhUrl.URL_UCONFIG_EX).build();
        if (!useCfConnectingIp) {
            return request;
        }

        String ip = configuredIp != null ? configuredIp.trim() : "";
        if (!isValidIpAddress(ip)) {
            throw new IllegalArgumentException("Invalid CF-Connecting-IP");
        }
        return request.newBuilder().header(HEADER_CF_CONNECTING_IP, ip).build();
    }

    public static boolean isUsableIgneous(@Nullable String value) {
        return value != null && !value.isEmpty() && !MYSTERY.equalsIgnoreCase(value);
    }

    public static boolean isMysterySetCookie(String header, HttpUrl url) {
        Cookie cookie = Cookie.parse(url, header);
        return cookie != null
                && EhCookieStore.KEY_IGNEOUS.equals(cookie.name())
                && MYSTERY.equalsIgnoreCase(cookie.value());
    }

    public static boolean isValidIpAddress(@Nullable String value) {
        if (value == null) {
            return false;
        }
        String ip = value.trim();
        if (InetValidator.isValidInet4Address(ip)) {
            return true;
        }
        if (ip.isEmpty() || ip.indexOf(':') < 0 || !ip.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            return InetAddress.getByName(ip) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
