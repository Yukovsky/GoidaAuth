package ru.goidacraft.goidaauth.twink;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import ru.goidacraft.goidaauth.Config;
import ru.goidacraft.goidaauth.GoidaAuth;
import ru.goidacraft.goidaauth.database.DatabaseManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blocks players who connect from an IP (or hardware fingerprint) that is already
 * associated with a different registered account.
 *
 * <p>Three modes, controlled by {@link Config#TWINK_MODE}:
 * <ul>
 *   <li>{@code disabled} — feature is off (default).</li>
 *   <li>{@code ip} — checks {@code last_ip} column; blocks if another username
 *       has the same IP.</li>
 *   <li>{@code hardware} — checks {@code hwid} column; falls back to IP when no
 *       hardware fingerprint was received from the client.</li>
 * </ul>
 *
 * <p>The HWID is populated only when the player's client sends a
 * {@link HwidPayload} (requires a companion client mod). Without it, HARDWARE
 * mode behaves identically to IP mode.
 */
public final class TwinkProtection {

    /** Per-session HWID cache; populated when the client sends {@link HwidPayload}. */
    private static final ConcurrentHashMap<UUID, String> sessionHwids = new ConcurrentHashMap<>();

    private TwinkProtection() {}

    // ------------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------------

    /** Called on player disconnect to free the in-memory HWID entry. */
    public static void clearSession(UUID uuid) {
        sessionHwids.remove(uuid);
    }

    // ------------------------------------------------------------------
    // Payload handler (registered in GoidaAuth via RegisterPayloadHandlersEvent)
    // ------------------------------------------------------------------

    public static void handleHwidPayload(HwidPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            String hwid = payload.hwid();
            if (hwid == null || hwid.isBlank() || hwid.length() > 64) return;
            sessionHwids.put(player.getUUID(), hwid);
            GoidaAuth.get().database().updateHwid(player.getGameProfile().getName(), hwid);
            GoidaAuth.LOGGER.debug("HWID received for {}", player.getGameProfile().getName());
        });
    }

    // ------------------------------------------------------------------
    // Core check
    // ------------------------------------------------------------------

    /**
     * Returns a future that resolves to {@code true} when the connecting player
     * should be blocked as a twink (i.e. another registered account shares their
     * IP or hardware fingerprint).
     *
     * <p>Safe to call from any thread; DB work runs on the DB executor. Errors
     * are swallowed and logged internally — on failure the future resolves to
     * {@code false} (allow-through), which is the safe default.
     *
     * @param username    connecting player's username
     * @param normalizedIp pre-normalised IP from {@link DatabaseManager#normalizeIp}
     * @param uuid        player UUID (used to look up session HWID)
     * @param db          database manager
     */
    public static CompletableFuture<Boolean> checkAsync(
            String username, String normalizedIp, UUID uuid, DatabaseManager db) {

        String mode = Config.TWINK_MODE.get();
        if (mode == null || mode.isBlank() || "disabled".equalsIgnoreCase(mode)) {
            return CompletableFuture.completedFuture(false);
        }

        if ("ip".equalsIgnoreCase(mode)) {
            return checkByIp(username, normalizedIp, db);
        }

        if ("hardware".equalsIgnoreCase(mode)) {
            String hwid = sessionHwids.get(uuid);
            if (hwid != null && !hwid.isBlank()) {
                return db.findNamesByHwid(hwid)
                        .thenApply(names -> hasOtherAccount(names, username))
                        .exceptionally(ex -> {
                            GoidaAuth.LOGGER.warn("Twink HWID check failed for {}", username, ex);
                            return false;
                        });
            }
            // Client hasn't sent a fingerprint — fall back to IP
            return checkByIp(username, normalizedIp, db);
        }

        return CompletableFuture.completedFuture(false);
    }

    private static CompletableFuture<Boolean> checkByIp(
            String username, String normalizedIp, DatabaseManager db) {
        if (normalizedIp == null || normalizedIp.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        return db.findNamesByIp(normalizedIp)
                .thenApply(names -> hasOtherAccount(names, username))
                .exceptionally(ex -> {
                    GoidaAuth.LOGGER.warn("Twink IP check failed for {}", username, ex);
                    return false;
                });
    }

    private static boolean hasOtherAccount(List<String> names, String username) {
        for (String name : names) {
            if (name != null && !name.equalsIgnoreCase(username)) {
                return true;
            }
        }
        return false;
    }
}
