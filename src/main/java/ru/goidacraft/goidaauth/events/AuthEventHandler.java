package ru.goidacraft.goidaauth.events;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import ru.goidacraft.goidaauth.Config;
import ru.goidacraft.goidaauth.GoidaAuth;
import ru.goidacraft.goidaauth.GoidaAuthApi;
import ru.goidacraft.goidaauth.auth.AuthSession;
import ru.goidacraft.goidaauth.auth.AuthSessionManager;
import ru.goidacraft.goidaauth.auth.LoginDecision;
import ru.goidacraft.goidaauth.commands.AuthCommands;
import ru.goidacraft.goidaauth.compat.LuckPermsLoginGate;
import ru.goidacraft.goidaauth.database.DatabaseManager;
import ru.goidacraft.goidaauth.database.LoginAttempt;
import ru.goidacraft.goidaauth.database.UserRecord;

import ru.goidacraft.goidaauth.twink.TwinkProtection;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AuthEventHandler {
    private final AuthSessionManager sessions;

    public AuthEventHandler(AuthSessionManager sessions) {
        this.sessions = sessions;
    }

    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // The login event may be posted more than once (LuckPerms defer fallback). Only the
        // first delivery sets up the session.
        if (sessions.get(player.getUUID()).isPresent()) return;

        var server = player.server;
        String username = player.getGameProfile().getName();

        AuthSession session = new AuthSession(player.getUUID(), username, server.getTickCount());
        session.storePosition(player.position(), player.getYRot(), player.getXRot(),
                player.level().dimension().location().toString());
        sessions.put(session);

        // Everyone is locked down on arrival, licensed or not. Nothing is released before the
        // database has answered — the account state in the DB is the only authority on who may
        // skip the password.
        applyLockdown(player);
        server.getCommands().sendCommands(player);
        notifySharedIpAccounts(player);

        var db = GoidaAuth.get().database();
        String normalizedIp = DatabaseManager.normalizeIp(player.getIpAddress());
        CompletableFuture<Optional<UserRecord>> dbFuture = db.findByName(username);
        CompletableFuture<Boolean> twinkFuture =
                TwinkProtection.checkAsync(username, normalizedIp, player.getUUID(), db);

        CompletableFuture.allOf(dbFuture, twinkFuture).thenRun(() -> server.execute(() -> {
            if (!isCurrentSession(player, session)) return;

            if (twinkFuture.join()) {
                player.connection.disconnect(Component.literal(Config.MSG_TWINK_KICK.get()));
                return;
            }
            resolveIdentity(player, session, dbFuture.join());
        })).exceptionally(ex -> {
            GoidaAuth.LOGGER.error("Login flow failed for {}", username, ex);
            server.execute(() -> {
                if (sessions.get(player.getUUID()).isPresent()) {
                    player.connection.disconnect(Component.literal("Ошибка авторизации, попробуйте снова."));
                }
            });
            return null;
        });
    }

    /**
     * Decides how this connection proves who it is. Runs on the server thread once the DB row (if
     * any) is known.
     *
     * <p>The premium proof is the connection's UUID, not its profile properties. Velocity assigns
     * the real Mojang UUID after an online-mode handshake and the derived offline UUID after
     * {@code forceOfflineMode}, so the two are distinguishable and cannot be chosen by the client.
     * A signed {@code textures} property — what this code used to trust — proves nothing: skin
     * plugins (SkinsRestorer and friends) attach genuine Mojang-signed textures to offline players,
     * which handed every such pirate a password-free login into whatever account they named.
     */
    private void resolveIdentity(ServerPlayer player, AuthSession session, Optional<UserRecord> dbOpt) {
        String username = session.username;
        boolean mojangVerified = !player.getUUID().equals(AuthSession.offlineUuid(username));

        session.setRegistered(dbOpt.isPresent());
        session.setRulesAccepted(dbOpt.map(UserRecord::rulesAccepted).orElse(false));

        LoginDecision decision = LoginDecision.of(
                mojangVerified, dbOpt.isPresent(), dbOpt.map(UserRecord::premium).orElse(false));

        switch (decision) {
            case REJECT_IMPOSTOR -> {
                // A licensed name arriving over an offline connection: an impostor, or the proxy
                // routed offline because the shared DB was briefly unreachable. Either way the
                // account is not handed out — the owner reconnects and is routed online.
                GoidaAuth.get().database().recordFailedAttempt(username, player.getIpAddress(),
                        player.getUUID(), LoginAttempt.PREMIUM_IMPOSTOR);
                player.connection.disconnect(Component.literal(Config.MSG_PREMIUM_KICK.get()));
                return;
            }
            case PREMIUM_LOGIN, PREMIUM_FIRST_JOIN -> {
                completePremiumLogin(player, session, dbOpt);
                return;
            }
            case PASSWORD -> {
                if (mojangVerified) {
                    GoidaAuth.LOGGER.warn("{} arrived Mojang-verified but the DB has premium=false "
                            + "— applying the password gate.", username);
                }
            }
        }

        if (dbOpt.isPresent() && trySessionAutoLogin(player, session, dbOpt.get())) return;

        if (dbOpt.isEmpty()) {
            promptRules(player, session);
        } else if (dbOpt.get().hasNoPassword()) {
            AuthCommands.send(player, "§cУ этого аккаунта нет пароля (он был лицензионным).");
            AuthCommands.send(player, "§cОбратитесь к администрации — пароль ставится командой §f/setpassword§c.");
        } else {
            AuthCommands.send(player, Config.MSG_LOGIN_PROMPT.get());
        }
    }

    /** Writes/reconciles the DB row for a Mojang-verified connection, then lets the player in. */
    private void completePremiumLogin(ServerPlayer player, AuthSession session, Optional<UserRecord> dbOpt) {
        var db = GoidaAuth.get().database();
        String username = session.username;

        if (dbOpt.isEmpty()) {
            // First join of a licensed player we have never seen. The password column gets a
            // placeholder that no password can ever match (see UserRecord.hasNoPassword).
            db.register(player.getUUID(), username, "premium:" + player.getUUID(),
                    true, player.getIpAddress(), false);
            // The row exists from here on, so /acceptrules can persist against it.
            session.setRegistered(true);
        } else {
            // Reconcile the stored UUID to the real Mojang UUID on the first online login after a
            // self-service /premium, where the DB still holds the offline UUID.
            // (PlayerDataMigrationMixin has already moved the playerdata offline -> real.)
            UUID oldUuid = dbOpt.get().uuid();
            if (!oldUuid.equals(player.getUUID())) {
                db.setPremium(username, true, player.getUUID())
                  .thenRun(() -> GoidaAuthApi.fireUuidChanged(oldUuid, player.getUUID(), username));
            } else {
                db.updateLastSeen(username, player.getIpAddress());
            }
        }

        AuthCommands.send(player, Config.MSG_PREMIUM_AUTOLOGIN.get());
        grantAccess(player, session, true);
    }

    /**
     * Single exit point for every successful identity check — premium session, password, session
     * auto-login and the admin force commands all land here.
     *
     * <p>The rules gate is applied uniformly: a licensed player has read the rules exactly as often
     * as a cracked one (never), so both are held until they accept. Previously the premium path
     * returned straight from the login event and skipped rules entirely.
     */
    public static void grantAccess(ServerPlayer player, AuthSession session, boolean premium) {
        session.setPremium(premium);
        session.setIdentityVerified(true);

        if (!session.isRulesAccepted()) {
            session.resetTimeout(player.server.getTickCount());
            promptRules(player, session);
            return;
        }
        session.setAuthorized(true);
        onAuthorized(player, session);
    }

    private boolean isCurrentSession(ServerPlayer player, AuthSession session) {
        return sessions.get(player.getUUID()).map(s -> s == session).orElse(false);
    }

    public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LuckPermsLoginGate.abort(player.getUUID());
        TwinkProtection.clearSession(player.getUUID());
        sessions.remove(player.getUUID());
    }

    public void onTick(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LuckPermsLoginGate.tick(player);
        AuthSession s = sessions.get(player.getUUID()).orElse(null);
        if (s == null || s.isAuthorized()) return;

        if (Config.FREEZE_PLAYER.get() && s.storedPosition() != null) {
            Vec3 stored = s.storedPosition();
            if (player.position().distanceToSqr(stored) > 0.0625) {
                player.connection.teleport(stored.x, stored.y, stored.z, s.storedYaw(), s.storedPitch());
            }
        }

        // Reading the rules takes longer than typing a password, so the rules stage gets its own
        // (larger) budget. Applies to everyone waiting on the rules, licensed or cracked.
        int limitSec = s.isRulesAccepted() ? Config.LOGIN_TIMEOUT_SEC.get() : Config.RULES_TIMEOUT_SEC.get();
        long elapsedTicks = player.server.getTickCount() - s.timeoutBaseTick();
        if (elapsedTicks > limitSec * 20L) {
            player.connection.disconnect(Component.literal(Config.MSG_TIMEOUT_KICK.get()));
        }
    }

    public void cancelIfUnauth(ICancellableEvent event, Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (sessions.isAuthorized(sp.getUUID())) return;
        event.setCanceled(true);
    }

    public void onCommand(CommandEvent event) {
        var source = event.getParseResults().getContext().getSource();
        ServerPlayer sp;
        try {
            sp = source.getPlayerOrException();
        } catch (Exception e) {
            return;
        }
        if (sessions.isAuthorized(sp.getUUID())) return;

        var parse = event.getParseResults();
        boolean hasErrors = !parse.getExceptions().isEmpty();
        boolean hasNodes = !parse.getContext().getNodes().isEmpty();
        if (hasErrors || !hasNodes) {
            String raw = parse.getReader().getString().trim();
            boolean handled = AuthCommands.tryHandleFallback(
                    sp, raw, GoidaAuth.get().database(), GoidaAuth.get().hasher(), sessions);
            if (handled) {
                event.setCanceled(true);
                return;
            }
        }

        // Prefer the parsed node name; fall back to raw input when parse failed
        // (e.g. LuckPerms throws in canUse before nodes are populated).
        String root;
        var nodes = parse.getContext().getNodes();
        if (hasNodes) {
            root = nodes.get(0).getNode().getName();
        } else {
            String raw = parse.getReader().getString().trim();
            int space = raw.indexOf(' ');
            root = space < 0 ? raw : raw.substring(0, space);
        }

        // Auth commands must always pass through for unauthenticated players. /premium is included
        // so a brand-new (unregistered) player can use it instead of /register — AuthCommands itself
        // still refuses it for anyone whose account already has a DB row (see premiumMode()).
        if (root.equalsIgnoreCase("login") || root.equalsIgnoreCase("l") ||
                root.equalsIgnoreCase("register") || root.equalsIgnoreCase("reg") ||
                root.equalsIgnoreCase("rules") || root.equalsIgnoreCase("acceptrules") ||
                root.equalsIgnoreCase("premium")) return;

        for (String allowed : Config.ALLOWED_COMMANDS.get()) {
            if (allowed.equalsIgnoreCase(root)) return;
        }
        event.setCanceled(true);
        AuthCommands.send(sp, Config.MSG_BLOCKED_ACTION.get());
    }

    public void onChat(ServerChatEvent event) {
        Player p = event.getPlayer();
        if (!(p instanceof ServerPlayer sp)) return;
        if (sessions.isAuthorized(sp.getUUID())) return;
        event.setCanceled(true);
        AuthCommands.send(sp, Config.MSG_BLOCKED_ACTION.get());
    }

    public void onDamage(LivingIncomingDamageEvent event) {
        if (!Config.GOD_MODE.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (sessions.isAuthorized(sp.getUUID())) return;
        event.setCanceled(true);
    }

    /** Prevents an unauthenticated player from dropping items; the stack is returned to avoid loss. */
    public void onItemToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;
        if (sessions.isAuthorized(sp.getUUID())) return;
        event.setCanceled(true);
        sp.getInventory().add(event.getEntity().getItem());
    }

    public static void applyLockdown(ServerPlayer player) {
        // Cover the longest stage a player can legitimately sit in (rules reading), so the effects
        // never lapse while they are still locked down.
        int duration = (Math.max(Config.LOGIN_TIMEOUT_SEC.get(), Config.RULES_TIMEOUT_SEC.get()) + 5) * 20;
        if (Config.APPLY_BLINDNESS.get()) {
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, duration, 0, false, false, false));
        }
        if (Config.APPLY_SLOWNESS.get()) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, 255, false, false, false));
        }
        if (Config.HIDE_FROM_OTHER_PLAYERS.get()) {
            player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, duration, 0, false, false, false));
        }
        if (Config.TELEPORT_TO_SAFE_ROOM.get()) {
            ServerLevel overworld = player.server.overworld();
            var spawn = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    java.util.EnumSet.noneOf(RelativeMovement.class), 0f, 0f);
        }
    }

    public static void onAuthorized(ServerPlayer player, AuthSession session) {
        player.removeEffect(MobEffects.BLINDNESS);
        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        player.removeEffect(MobEffects.INVISIBILITY);

        if (Config.TELEPORT_TO_SAFE_ROOM.get() && session.storedPosition() != null) {
            Vec3 pos = session.storedPosition();
            String dimKey = session.storedDimension();
            ServerLevel target = null;
            if (dimKey != null) {
                var key = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(dimKey));
                target = player.server.getLevel(key);
            }
            if (target == null) {
                // The stored dimension no longer resolves (removed/renamed datapack or mod dimension).
                // Do NOT fall back to the overworld with the foreign coordinates — that would drop the
                // player at a meaningless/dangerous spot (e.g. nether coords in the overworld). Leave
                // them at the safe spawn instead.
                GoidaAuth.LOGGER.warn(
                        "onAuthorized: stored dimension '{}' for {} no longer exists; leaving player at spawn.",
                        dimKey, player.getGameProfile().getName());
            } else {
                player.teleportTo(target, pos.x, pos.y, pos.z,
                        java.util.EnumSet.noneOf(RelativeMovement.class), session.storedYaw(), session.storedPitch());
            }
        }

        player.server.getCommands().sendCommands(player);

        // Only now is anything about this connection written to the account. A failed attempt on
        // someone else's nickname must leave no trace on that account (IP, last_seen, fingerprint),
        // otherwise the twink/IP checks start firing on the victim rather than the intruder.
        TwinkProtection.persistHwid(player);

        // Notify downstream mods (e.g. GoidaDI) that the player may now play. Every authorization
        // path routes through here, so this fires exactly once per session.
        GoidaAuthApi.fireAuthorized(player, session.isPremium(), session.isRegistered());
    }

    private boolean trySessionAutoLogin(ServerPlayer player, AuthSession session, UserRecord record) {
        if (!Config.SESSION_ENABLED.get()) return false;
        if (record.premium()) return false;
        if (record.hasNoPassword()) return false;
        if (record.lastSeen() == null) return false;
        int timeoutMin = Config.SESSION_TIMEOUT_MIN.get();
        if (timeoutMin <= 0) return false;

        Instant cutoff = Instant.now().minusSeconds(timeoutMin * 60L);
        if (record.lastSeen().isBefore(cutoff)) return false;

        if (Config.SESSION_REQUIRE_SAME_IP.get()) {
            String currentIp = DatabaseManager.normalizeIp(player.getIpAddress());
            String lastIp = DatabaseManager.normalizeIp(record.lastIp());
            if (currentIp == null || lastIp == null || !lastIp.equals(currentIp)) return false;
        }

        GoidaAuth.get().database().updateLastSeen(record.username(), player.getIpAddress());
        AuthCommands.send(player, Config.MSG_SESSION_AUTOLOGIN.get());
        grantAccess(player, session, false);
        return true;
    }

    private static final String SEP = "§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";

    /**
     * The one rules prompt, shown to every player who has not accepted them yet. The closing line
     * differs only in what happens after {@code /acceptrules}: a player whose identity is already
     * proven starts playing, a new one goes on to register.
     */
    public static void promptRules(ServerPlayer player, AuthSession session) {
        AuthCommands.send(player, SEP);
        AuthCommands.send(player, "  §6§lДобро пожаловать на GoidaCraft!");
        AuthCommands.send(player, SEP);
        AuthCommands.send(player, "§fНеобходимо ознакомиться с правилами сервера и принять их.");

        MutableComponent readLine = Component.literal("§7Введите ").append(
                Component.literal("§f/rules")
                        .withStyle(s -> s
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rules"))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("§7Открыть правила")))))
                .append(Component.literal("§7 чтобы прочитать правила."));
        player.sendSystemMessage(readLine);

        String tail = session.isIdentityVerified()
                ? "§7 чтобы начать игру."
                : "§7 для продолжения регистрации.";
        MutableComponent acceptLine = Component.literal("§7Прочитав правила, введите ").append(
                Component.literal("§f/acceptrules")
                        .withStyle(s -> s
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/acceptrules"))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("§7Принять правила")))))
                .append(Component.literal(tail));
        player.sendSystemMessage(acceptLine);

        AuthCommands.send(player, "");
        MutableComponent discordLine = Component.literal("§c⚠ §fПеред привязкой §c§lОБЯЗАТЕЛЬНО §fзайдите на наш Дискорд-сервер: ").append(
                Component.literal("§b§n[discord.gg/prJwFwy5ns]")
                        .withStyle(s -> s
                                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://discord.gg/prJwFwy5ns"))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("§7Нажмите чтобы открыть: §fdiscord.gg/prJwFwy5ns")))));
        player.sendSystemMessage(discordLine);

        AuthCommands.send(player, "§7Бота для привязки можно найти: §fпо ссылке выше §7или §fсреди ботов на самом сервере§7.");
        AuthCommands.send(player, "§7Авторизация через Дискорд §cобязательна§7! Дано §f3 дня §7с момента регистрации.");

        AuthCommands.send(player, SEP);
    }

    private void notifySharedIpAccounts(ServerPlayer player) {
        String ip = DatabaseManager.normalizeIp(player.getIpAddress());
        if (ip == null || ip.isBlank()) return;
        GoidaAuth.get().database().findNamesByIp(ip).thenAccept(names -> player.server.execute(() -> {
            if (sessions.get(player.getUUID()).isEmpty()) return;
            if (names == null || names.isEmpty()) return;
            String username = player.getGameProfile().getName();
            java.util.ArrayList<String> others = new java.util.ArrayList<>();
            for (String name : names) {
                if (name != null && !name.equalsIgnoreCase(username)) {
                    others.add(name);
                }
            }
            if (others.isEmpty()) return;
            String list = String.join(", ", others);
            String msg = "§eIP §f" + ip + "§e ранее использовался аккаунтами: §f" + list;
            for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
                if (online.hasPermissions(Commands.LEVEL_GAMEMASTERS)) {
                    online.sendSystemMessage(Component.literal(msg));
                }
            }
            GoidaAuth.LOGGER.info("Shared IP {} for {} -> {}", ip, username, list);
        }));
    }
}
