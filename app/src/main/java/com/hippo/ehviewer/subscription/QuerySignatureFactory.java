package com.hippo.ehviewer.subscription;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class QuerySignatureFactory {
    private QuerySignatureFactory() {}

    public static String create(String effectiveQuery, boolean chineseActuallyApplied) {
        String input = (effectiveQuery == null ? "" : effectiveQuery) + "\nzh=" + chineseActuallyApplied;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) result.append(String.format("%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
