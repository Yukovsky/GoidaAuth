package ru.goidacraft.goidaauth.mojang;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;

public final class SessionHasher {
    private SessionHasher() {}

    public static String compute(String serverId, SecretKey sharedSecret, PublicKey publicKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
            md.update(sharedSecret.getEncoded());
            md.update(publicKey.getEncoded());
            return new BigInteger(md.digest()).toString(16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
