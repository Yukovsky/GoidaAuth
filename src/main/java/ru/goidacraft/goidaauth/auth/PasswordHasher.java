package ru.goidacraft.goidaauth.auth;

import org.mindrot.jbcrypt.BCrypt;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.spec.KeySpec;
import java.util.Base64;

public final class PasswordHasher {
    private static final int BCRYPT_COST = 12;
    private static final String PBKDF2_PREFIX = "pbkdf2$";

    public String hash(char[] password) {
        try {
            return BCrypt.hashpw(new String(password), BCrypt.gensalt(BCRYPT_COST));
        } finally {
            wipe(password);
        }
    }

    public boolean verify(String hash, char[] password) {
        try {
            if (hash == null) return false;
            if (isBcrypt(hash)) {
                return BCrypt.checkpw(new String(password), hash);
            }
            if (hash.startsWith(PBKDF2_PREFIX)) {
                return verifyPbkdf2(hash, password);
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        } finally {
            wipe(password);
        }
    }

    /** True if the stored hash was produced by the old PBKDF2 hasher and should be upgraded. */
    public boolean needsRehash(String hash) {
        return hash != null && hash.startsWith(PBKDF2_PREFIX);
    }

    private static boolean isBcrypt(String hash) {
        return hash.startsWith("$2a$") || hash.startsWith("$2y$") || hash.startsWith("$2b$");
    }

    private static boolean verifyPbkdf2(String hash, char[] password) {
        String[] parts = hash.split("\\$");
        if (parts.length != 4) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt     = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual   = pbkdf2(password, salt, iterations, expected.length * 8);
            return constantTimeEquals(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLengthBits) {
        try {
            KeySpec spec = new PBEKeySpec(password, salt, iterations, keyLengthBits);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 failed", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    private static void wipe(char[] password) {
        for (int i = 0; i < password.length; i++) password[i] = 0;
    }
}
