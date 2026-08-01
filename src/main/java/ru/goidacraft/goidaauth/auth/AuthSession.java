package ru.goidacraft.goidaauth.auth;

import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-connection auth state. A session moves through three stages and never skips one:
 *
 * <pre>
 *   joined                    -> nothing proven, full lockdown, no DB answer yet
 *   identityVerified          -> Mojang session or password accepted; still locked if rules pending
 *   authorized                -> identity proven AND rules accepted; the player may play
 * </pre>
 *
 * A session is <b>never</b> constructed already authorized: every path has to go through the
 * database first. The previous code pre-authorized "premium" connections in the constructor,
 * which meant a player was inside before the {@code premium} flag was ever read.
 */
public final class AuthSession {
    public final UUID uuid;
    public final String username;

    private volatile long timeoutBaseTick;
    private volatile boolean premium;
    private volatile boolean identityVerified;
    private volatile boolean authorized;
    private volatile boolean registered;
    private volatile boolean rulesAccepted;
    private volatile Vec3 storedPosition;
    private volatile float storedYaw;
    private volatile float storedPitch;
    private volatile String storedDimension;
    private final AtomicInteger failedAttempts = new AtomicInteger(0);

    public AuthSession(UUID uuid, String username, long joinTick) {
        this.uuid = uuid;
        this.username = username;
        this.timeoutBaseTick = joinTick;
    }

    /**
     * The UUID an offline-mode server (and Velocity's {@code forceOfflineMode}) derives from a
     * username. A connection whose UUID differs from this one went through a real Mojang
     * handshake — that, and only that, is the premium proof available behind a proxy.
     */
    public static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    public boolean isAuthorized() { return authorized; }
    public void setAuthorized(boolean authorized) { this.authorized = authorized; }

    /** True once the Mojang session or the password was accepted, regardless of the rules gate. */
    public boolean isIdentityVerified() { return identityVerified; }
    public void setIdentityVerified(boolean identityVerified) { this.identityVerified = identityVerified; }

    /** True when this player was let in through a verified Mojang session rather than a password. */
    public boolean isPremium() { return premium; }
    public void setPremium(boolean premium) { this.premium = premium; }

    public boolean isRegistered() { return registered; }
    public void setRegistered(boolean registered) { this.registered = registered; }

    public boolean isRulesAccepted() { return rulesAccepted; }
    public void setRulesAccepted(boolean rulesAccepted) { this.rulesAccepted = rulesAccepted; }

    public long timeoutBaseTick() { return timeoutBaseTick; }

    /** Restarts the kick timer — used when the player moves on to the (slower) rules stage. */
    public void resetTimeout(long currentTick) { this.timeoutBaseTick = currentTick; }

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
