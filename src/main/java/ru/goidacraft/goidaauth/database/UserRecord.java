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
        Instant registeredAt
) {}
