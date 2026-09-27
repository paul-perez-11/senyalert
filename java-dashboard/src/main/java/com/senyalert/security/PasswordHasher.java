package com.senyalert.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2-HMAC-SHA256, random 128-bit salt and 256-bit output; no recoverable passwords. */
public final class PasswordHasher {
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private PasswordHasher() { }

    public static void validateNewPassword(char[] password) {
        if (password == null || password.length < 12 || password.length > 128) {
            throw new IllegalArgumentException("Use a password of 12 to 128 characters.");
        }
    }

    public static String hash(char[] password) {
        validateNewPassword(password);
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt, ITERATIONS);
        try {
            return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                    + "$" + Base64.getEncoder().encodeToString(key);
        } finally { Arrays.fill(key, (byte) 0); }
    }

    public static boolean isEncodedHash(String value) {
        try { parse(value); return true; } catch (RuntimeException invalid) { return false; }
    }

    public static boolean verify(char[] password, String encoded) {
        if (password == null || password.length > 128) return false;
        try {
            String[] parts = parse(encoded);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password, Base64.getDecoder().decode(parts[2]), Integer.parseInt(parts[1]));
            try { return MessageDigest.isEqual(expected, actual); }
            finally { Arrays.fill(actual, (byte) 0); Arrays.fill(expected, (byte) 0); }
        } catch (RuntimeException invalid) { return false; }
    }

    private static String[] parse(String encoded) {
        if (encoded == null || encoded.length() > 300) throw new IllegalArgumentException("Invalid password hash");
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) throw new IllegalArgumentException("Invalid password hash");
        int iterations = Integer.parseInt(parts[1]);
        if (iterations < ITERATIONS || iterations > 2_000_000
                || Base64.getDecoder().decode(parts[2]).length < 16
                || Base64.getDecoder().decode(parts[3]).length != 32) throw new IllegalArgumentException("Invalid password hash");
        return parts;
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException failure) { throw new IllegalStateException("Password hashing unavailable", failure); }
        finally { spec.clearPassword(); }
    }
}
