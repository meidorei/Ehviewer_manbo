package com.hippo.ehviewer.jm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import okio.ByteString;

public final class JmApiCodec {
    static final String SECRET = "185Hcomic3PAPP7R";

    private JmApiCodec() {
    }

    public static String md5Hex(String text) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    public static String token(String timestamp) throws GeneralSecurityException {
        return md5Hex(timestamp + SECRET);
    }

    public static String tokenParam(String timestamp, String appVersion) {
        return timestamp + "," + appVersion;
    }

    public static JSONObject decrypt(String encodedData, String timestamp)
            throws GeneralSecurityException {
        ByteString decoded = ByteString.decodeBase64(encodedData);
        if (decoded == null) {
            throw new GeneralSecurityException("Invalid Base64 response");
        }
        byte[] ciphertext = decoded.toByteArray();
        if (ciphertext.length == 0 || ciphertext.length % 16 != 0) {
            throw new GeneralSecurityException("Invalid encrypted response length");
        }

        byte[] key = md5Hex(timestamp + SECRET).getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] plaintext = cipher.doFinal(ciphertext);
        try {
            Object object = JSON.parse(new String(plaintext, StandardCharsets.UTF_8));
            if (!(object instanceof JSONObject)) {
                throw new GeneralSecurityException("Decrypted response is not an object");
            }
            return (JSONObject) object;
        } catch (RuntimeException e) {
            throw new GeneralSecurityException("Invalid decrypted JSON", e);
        }
    }
}
