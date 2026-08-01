package ru.goidacraft.goidaauth.database;

import java.time.Instant;
import java.util.UUID;

public record UserRecord(
        UUID uuid,
        String username,
        String passwordHash,
        boolean premium,
        String lastIp,
        Instant lastSeen,
        Instant registeredAt,
        boolean rulesAccepted
) {
    /**
     * True when the stored hash is the {@code premium:<uuid>} placeholder written for accounts that
     * were registered through a Mojang session and therefore never had a password. No password can
     * ever match it, so login must say so explicitly instead of reporting a wrong password.
     */
    public boolean hasNoPassword() {
        return passwordHash == null || passwordHash.startsWith("premium:");
    }
}
