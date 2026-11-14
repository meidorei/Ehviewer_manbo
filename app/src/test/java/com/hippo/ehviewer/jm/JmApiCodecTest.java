package com.hippo.ehviewer.jm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.alibaba.fastjson.JSONObject;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class JmApiCodecTest {
    @Test
    public void generatesStableHeaders() throws Exception {
        String timestamp = "1720000000";
        assertEquals("403503fb66c4054a488401b1fe330b96",
                JmApiCodec.token(timestamp));
        assertEquals("1720000000,2.0.29",
                JmApiCodec.tokenParam(timestamp, "2.0.29"));
    }

    @Test
    public void decryptsEncryptedJsonWithMatchingTimestamp() throws Exception {
        String timestamp = "1720000000";
        String encrypted = encrypt("{\"id\":350234,\"name\":\"董卓 上+下\"}", timestamp);
        JSONObject result = JmApiCodec.decrypt(encrypted, timestamp);
        assertEquals("350234", result.getString("id"));
        assertEquals("董卓 上+下", result.getString("name"));
    }

    @Test
    public void rejectsInvalidEncryptedResponses() throws Exception {
        assertThrows(GeneralSecurityException.class,
                () -> JmApiCodec.decrypt("not base64", "1720000000"));
        assertThrows(GeneralSecurityException.class,
                () -> JmApiCodec.decrypt(encrypt("[]", "1720000000"), "1720000000"));
        assertThrows(GeneralSecurityException.class,
                () -> JmApiCodec.decrypt(encrypt("{\"ok\":true}", "1720000000"),
                        "1720000001"));
    }

    private static String encrypt(String json, String timestamp) throws Exception {
        byte[] key = JmApiCodec.md5Hex(timestamp + JmApiCodec.SECRET)
                .getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return Base64.getEncoder().encodeToString(
                cipher.doFinal(json.getBytes(StandardCharsets.UTF_8)));
    }
}
