package com.senyalert.remote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Independent client support key; the developer account password never leaves its laptop. */
final class SupportAuthentication {
    private SupportAuthentication() { }

    static String sign(String key, String method, String path, String timestamp, String nonce, byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            String text = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n"
                    + Base64.getEncoder().encodeToString(digest);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not authenticate support request", failure);
        }
    }

    static boolean equal(String expected, String supplied) {
        return supplied != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
