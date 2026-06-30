package ru.goidacraft.goidaauth;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Small, additive integration surface other mods can hook into without a hard dependency.
 *
 * <p>Three extension points live here:
 * <ul>
 *   <li>{@link AuthorizedListener} — fired the moment a player is fully let into the game
 *       (cracked login/register/session/force paths and the premium auto-login branch). This is
 *       the exact "you may now play" signal a downstream mod needs to start its own timers.</li>
 *   <li>{@link InteractionBlocker} — lets another mod tell GoidaAuth's existing interaction guards
 *       (chat, commands, block/entity interaction, attacks, item drops and inventory clicks) to
 *       keep blocking an <em>already authorized</em> player. GoidaDI uses this so its Discord-link
 *       lockdown reuses GoidaAuth's packet-level inventory handling instead of duplicating a mixin.</li>
 *   <li>{@link UuidChangedHook} — fired whenever a player's UUID is changed in-place (pirate→premium
 *       auto-upgrade on login, or {@code /unpremium} downgrade). Lets GoidaDI move the Discord link
 *       to the new UUID without a full {@code /transferaccount}.</li>
 * </ul>
 *
 * <p>Both registries are {@link CopyOnWriteArrayList}s, so registration and firing are safe across
 * threads. They are no-ops when nobody subscribes, so GoidaAuth's behaviour is unchanged on its own.
 */
public final class GoidaAuthApi {
    private GoidaAuthApi() {}

    @FunctionalInterface
    public interface AuthorizedListener {
        /**
         * @param player        the player that just became authorized (on the server thread)
         * @param premium       {@code true} when authorized via a verified Mojang session
         * @param wasRegistered {@code true} when the account already existed in the GoidaAuth DB
         */
        void onAuthorized(ServerPlayer player, boolean premium, boolean wasRegistered);
    }

    @FunctionalInterface
    public interface InteractionBlocker {
        /** @return {@code true} to keep blocking this (otherwise authorized) player's interactions. */
        boolean shouldBlock(ServerPlayer player);
    }

    @FunctionalInterface
    public interface UuidChangedHook {
        /**
         * Fired after GoidaAuth has committed a UUID change to the database (pirate→premium on login,
         * or premium→pirate via {@code /unpremium}). The old UUID is no longer in the DB at this point.
         *
         * @param oldUuid  the UUID that was replaced
         * @param newUuid  the UUID now active for this player
         * @param username the (unchanged) player username
         */
        void onUuidChanged(UUID oldUuid, UUID newUuid, String username);
    }

    private static final List<AuthorizedListener> AUTH_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<InteractionBlocker>  BLOCKERS       = new CopyOnWriteArrayList<>();
    private static final List<UuidChangedHook>     UUID_HOOKS     = new CopyOnWriteArrayList<>();

    public static void addAuthorizedListener(AuthorizedListener listener) {
        if (listener != null) AUTH_LISTENERS.add(listener);
    }

    public static void removeAuthorizedListener(AuthorizedListener listener) {
        AUTH_LISTENERS.remove(listener);
    }

    public static void addInteractionBlocker(InteractionBlocker blocker) {
        if (blocker != null) BLOCKERS.add(blocker);
    }

    public static void removeInteractionBlocker(InteractionBlocker blocker) {
        BLOCKERS.remove(blocker);
    }

    public static void addUuidChangedHook(UuidChangedHook hook) {
        if (hook != null) UUID_HOOKS.add(hook);
    }

    public static void removeUuidChangedHook(UuidChangedHook hook) {
        UUID_HOOKS.remove(hook);
    }

    /** Invoked by GoidaAuth itself the moment a player is authorized. */
    public static void fireAuthorized(ServerPlayer player, boolean premium, boolean wasRegistered) {
        for (AuthorizedListener l : AUTH_LISTENERS) {
            try {
                l.onAuthorized(player, premium, wasRegistered);
            } catch (Throwable t) {
                GoidaAuth.LOGGER.error("AuthorizedListener threw", t);
            }
        }
    }

    /** Invoked by GoidaAuth after committing an in-place UUID change to the database. */
    public static void fireUuidChanged(UUID oldUuid, UUID newUuid, String username) {
        for (UuidChangedHook hook : UUID_HOOKS) {
            try {
                hook.onUuidChanged(oldUuid, newUuid, username);
            } catch (Throwable t) {
                GoidaAuth.LOGGER.error("UuidChangedHook threw", t);
            }
        }
    }

    /** @return {@code true} if any registered blocker wants this authorized player's actions blocked. */
    public static boolean isExternallyBlocked(ServerPlayer player) {
        if (player == null || BLOCKERS.isEmpty()) return false;
        for (InteractionBlocker b : BLOCKERS) {
            try {
                if (b.shouldBlock(player)) return true;
            } catch (Throwable t) {
                GoidaAuth.LOGGER.error("InteractionBlocker threw", t);
            }
        }
        return false;
    }
}
