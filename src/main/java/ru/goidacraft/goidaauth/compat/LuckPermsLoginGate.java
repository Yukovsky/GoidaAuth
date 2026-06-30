package ru.goidacraft.goidaauth.compat;

import net.luckperms.api.LuckPermsProvider;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import ru.goidacraft.goidaauth.Config;
import ru.goidacraft.goidaauth.GoidaAuth;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works around a LuckPerms/NeoForge race: {@code EventHooks.firePlayerLoggedIn} is invoked at the
 * end of {@code PlayerList.placeNewPlayer}, but LuckPerms' per-player capability is not yet
 * initialised at that point. Its {@code PlayerLoggedInEvent} listener then throws
 * "Capability has not been initialised", which aborts the event bus and the player is kicked
 * with "Invalid player data".
 *
 * <p>When LuckPerms is present the login event is intercepted (see {@code EventHooksMixin}) and
 * delivered here instead. It is posted exactly once, a few server ticks later, by which time the
 * capability is ready and every listener — LuckPerms, GoidaAuth and the rest — receives it cleanly.
 *
 * <p>When LuckPerms is loaded as a Bukkit plugin (on a hybrid server) rather than a NeoForge mod,
 * {@code ModList.get().isLoaded("luckperms")} returns {@code false} in the mixin, so this gate is
 * never activated and {@code firePlayerLoggedIn} runs normally.
 */
public final class LuckPermsLoginGate {

    private static final ConcurrentHashMap<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private LuckPermsLoginGate() {}

    private static final class Pending {
        final ServerPlayer player;
        int ticksWaited;

        Pending(ServerPlayer player) {
            this.player = player;
        }
    }

    /** Queue a player's login event for delayed delivery. Called from the mixin. */
    public static void defer(ServerPlayer player) {
        PENDING.put(player.getUUID(), new Pending(player));
    }

    /** Drop a queued player without delivering (player disconnected before the event fired). */
    public static void abort(UUID uuid) {
        PENDING.remove(uuid);
    }

    /**
     * Called every server tick for each player. Waits until LuckPerms has finished loading the
     * player's user data (checked via the LuckPerms API), then posts the deferred event exactly once.
     *
     * <p>A minimum delay of {@code luckperms_login_defer_ticks} is always observed so the player
     * entity is fully in the world before listeners run. After that, the gate polls every tick
     * until the user is ready (or up to 200 ticks total, whichever comes first).
     */
    public static void tick(ServerPlayer player) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null) return;

        if (++pending.ticksWaited < Config.LUCKPERMS_DEFER_TICKS.get()) return;

        // Wait until LuckPerms has loaded this player's user data.
        // Polling is cheap — getUser() is an in-memory ConcurrentHashMap lookup.
        try {
            boolean ready = LuckPermsProvider.get()
                    .getUserManager()
                    .getUser(player.getUUID()) != null;
            if (!ready && pending.ticksWaited < 200) return;
        } catch (Throwable ignored) {
            // LP API unavailable or class not accessible — fire without waiting.
        }

        PENDING.remove(player.getUUID());
        try {
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
            player.server.getCommands().sendCommands(player);
        } catch (IllegalStateException e) {
            // GoidaAuth at HIGHEST already ran, so auth session exists.
            // LuckPerms storage may be broken — permissions will not work for this session.
            GoidaAuth.LOGGER.error("Deferred PlayerLoggedInEvent for {} failed after {} ticks. "
                    + "Check LuckPerms storage configuration.",
                    player.getGameProfile().getName(), pending.ticksWaited, e);
        }
    }
}
