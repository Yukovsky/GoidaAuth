package ru.goidacraft.goidaauth.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthSessionManager {
    private final ConcurrentHashMap<UUID, AuthSession> byUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> pendingPremium = new ConcurrentHashMap<>();

    public void put(AuthSession session) {
        byUuid.put(session.uuid, session);
    }

    public Optional<AuthSession> get(UUID uuid) {
        return Optional.ofNullable(byUuid.get(uuid));
    }

    public AuthSession remove(UUID uuid) {
        return byUuid.remove(uuid);
    }

    public boolean isAuthorized(UUID uuid) {
        AuthSession s = byUuid.get(uuid);
        return s != null && s.isAuthorized();
    }

    public void markPremiumPending(String username, boolean premium) {
        pendingPremium.put(username.toLowerCase(Locale.ROOT), premium);
    }

    public Boolean consumePremiumPending(String username) {
        return pendingPremium.remove(username.toLowerCase(Locale.ROOT));
    }

    public void clear() {
        byUuid.clear();
        pendingPremium.clear();
    }
}
