package ru.goidacraft.goidaauth.auth;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class AuthSession {
    public final UUID uuid;
    public final String username;
    public final long joinTickStamp;
    public final boolean premiumVerified;

    private volatile boolean authorized;
    private volatile boolean registered;
    private volatile boolean rulesAccepted;
    private volatile Vec3 storedPosition;
    private volatile float storedYaw;
    private volatile float storedPitch;
    private volatile String storedDimension;
    private final AtomicInteger failedAttempts = new AtomicInteger(0);

    public AuthSession(UUID uuid, String username, boolean premiumVerified, long joinTickStamp) {
        this.uuid = uuid;
        this.username = username;
        this.premiumVerified = premiumVerified;
        this.joinTickStamp = joinTickStamp;
        this.authorized = premiumVerified;
    }

    public boolean isAuthorized() { return authorized; }
    public void setAuthorized(boolean authorized) { this.authorized = authorized; }

    public boolean isRegistered() { return registered; }
    public void setRegistered(boolean registered) { this.registered = registered; }

    public boolean isRulesAccepted() { return rulesAccepted; }
    public void setRulesAccepted(boolean rulesAccepted) { this.rulesAccepted = rulesAccepted; }

    public Vec3 storedPosition() { return storedPosition; }
    public float storedYaw() { return storedYaw; }
    public float storedPitch() { return storedPitch; }
    public String storedDimension() { return storedDimension; }

    public void storePosition(Vec3 pos, float yaw, float pitch, String dim) {
        this.storedPosition = pos;
        this.storedYaw = yaw;
        this.storedPitch = pitch;
        this.storedDimension = dim;
    }

    public int incrementFailures() { return failedAttempts.incrementAndGet(); }
    public int failures() { return failedAttempts.get(); }
}
