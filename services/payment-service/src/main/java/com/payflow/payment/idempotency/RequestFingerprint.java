package com.payflow.payment.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 over the method, path, and exact body bytes: a retry must resend the identical request. */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(String method, String path, byte[] body) {
        MessageDigest digest = sha256();
        digest.update(method.getBytes(StandardCharsets.US_ASCII));
        // Newline separators keep the parts unambiguous; a URI path cannot contain one.
        digest.update((byte) '\n');
        digest.update(path.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        digest.update(body);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Every Java runtime must provide SHA-256", ex);
        }
    }
}
