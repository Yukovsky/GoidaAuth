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
import ru.goidacraft.goidaauth.commands.AuthCommands;
import ru.goidacraft.goidaauth.compat.LuckPermsLoginGate;
import ru.goidacraft.goidaauth.database.DatabaseManager;
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

        // Premium proof behind a proxy: Velocity forced online-mode and NeoVelocity forwarded the
        // Mojang-signed `textures` property. No signed textures => cracked/offline connection.
        boolean sessionVerified = hasSignedTextures(player.getGameProfile());

        AuthSession session = new AuthSession(player.getUUID(), username, sessionVerified, server.getTickCount());
        session.storePosition(player.position(), player.getYRot(), player.getXRot(),
                player.level().dimension().location().toString());
        sessions.put(session);
        player.server.getCommands().sendCommands(player);
        notifySharedIpAccounts(player);

        var db = GoidaAuth.get().database();

        if (sessionVerified) {
            // Full Mojang session verified — authorize immediately, no lockdown needed
            session.setAuthorized(true);
            AuthCommands.send(player, Config.MSG_PREMIUM_AUTOLOGIN.get());
            db.findByName(username).thenAccept(opt -> server.execute(() -> {
                if (!sessions.get(player.getUUID()).map(s -> s == session).orElse(false)) return;
                session.setRegistered(opt.isPresent());
                if (opt.isEmpty()) {
                    db.register(player.getUUID(), username,
                            "premium:" + player.getUUID(), true, player.getIpAddress());
                } else if (!opt.get().premium()) {
                    // DB says premium=false (admin forced cracked) but the player reached us via
                    // Mojang auth. This only happens during a race: admin ran /unpremium while the
                    // player reconnected before the DB write committed, so Velocity still saw the
                    // old premium=true and forced online-mode. Do NOT auto-promote — that would
                    // silently undo the admin's command. Just update last_seen; Velocity will
                    // correctly route them to offline-mode on the next connection.
                    GoidaAuth.LOGGER.warn(
                        "onLoggedIn: {} connected with Mojang session but DB has premium=false " +
                        "(probable race with /unpremium). Skipping auto-promote.", username);
                    db.updateLastSeen(username, player.getIpAddress());
                } else {
                    // Already premium. Reconcile the stored UUID to the real Mojang UUID — this is
                    // the first online login after a self /premium, where the DB still holds the
                    // offline UUID. (PlayerDataMigrationMixin already moved playerdata offline→real.)
                    UUID oldUuid = opt.get().uuid();
                    if (!oldUuid.equals(player.getUUID())) {
                        db.setPremium(username, true, player.getUUID())
                          .thenRun(() -> GoidaAuthApi.fireUuidChanged(oldUuid, player.getUUID(), username));
                    } else {
                        db.updateLastSeen(username, player.getIpAddress());
                    }
                }
                ru.goidacraft.goidaauth.GoidaAuthApi.fireAuthorized(player, true, opt.isPresent());
            }));
            return;
        }

        applyLockdown(player);

        String normalizedIp = DatabaseManager.normalizeIp(player.getIpAddress());
        CompletableFuture<Optional<UserRecord>> dbFuture = db.findByName(username);
        CompletableFuture<Boolean> twinkFuture =
                TwinkProtection.checkAsync(username, normalizedIp, player.getUUID(), db);

        CompletableFuture.allOf(dbFuture, twinkFuture).thenRun(() -> server.execute(() -> {
            if (!sessions.get(player.getUUID()).map(s -> s == session).orElse(false)) return;

            if (twinkFuture.join()) {
                player.connection.disconnect(Component.literal(Config.MSG_TWINK_KICK.get()));
                return;
            }

            Optional<UserRecord> dbOpt = dbFuture.join();
            if (dbOpt.isPresent() && dbOpt.get().premium()) {
                player.connection.disconnect(Component.literal(Config.MSG_PREMIUM_KICK.get()));
                return;
            }
            session.setRegistered(dbOpt.isPresent());
            session.setRulesAccepted(dbOpt.isPresent());
            if (dbOpt.isPresent() && trySessionAutoLogin(player, session, dbOpt.get())) return;
            if (dbOpt.isPresent()) {
                AuthCommands.send(player, Config.MSG_LOGIN_PROMPT.get());
            } else {
                sendRulesWelcome(player);
            }
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

    /** True if the forwarded GameProfile carries a Mojang-signed `textures` property. */
    private static boolean hasSignedTextures(com.mojang.authlib.GameProfile profile) {
        for (com.mojang.authlib.properties.Property p : profile.getProperties().get("textures")) {
            // authlib 1.20.2+ (used by 1.21.1) models Property as a record: signature().
            String signature = p.signature();
            if (signature != null && !signature.isBlank()) return true;
        }
        return false;
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

        long elapsedTicks = player.server.getTickCount() - s.joinTickStamp;
        if (elapsedTicks > Config.LOGIN_TIMEOUT_SEC.get() * 20L) {
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

        // Auth commands must always pass through for unauthenticated players
        if (root.equalsIgnoreCase("login") || root.equalsIgnoreCase("l") ||
                root.equalsIgnoreCase("register") || root.equalsIgnoreCase("reg") ||
                root.equalsIgnoreCase("rules") || root.equalsIgnoreCase("acceptrules")) return;

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
        int duration = (Config.LOGIN_TIMEOUT_SEC.get() + 5) * 20;
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

        // Notify downstream mods (e.g. GoidaDI) that the player may now play. Covers the cracked
        // login/register/session/force paths; the premium auto-login branch fires separately.
        ru.goidacraft.goidaauth.GoidaAuthApi.fireAuthorized(player, session.premiumVerified, session.isRegistered());
    }

    private boolean trySessionAutoLogin(ServerPlayer player, AuthSession session, UserRecord record) {
        if (!Config.SESSION_ENABLED.get()) return false;
        if (record.premium()) return false;
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

        session.setAuthorized(true);
        session.setRegistered(true);
        GoidaAuth.get().database().updateLastSeen(record.username(), player.getIpAddress());
        onAuthorized(player, session);
        AuthCommands.send(player, Config.MSG_SESSION_AUTOLOGIN.get());
        return true;
    }

    private static final String SEP = "§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";

    public static void sendRulesWelcome(ServerPlayer player) {
        AuthCommands.send(player, SEP);
        AuthCommands.send(player, "  §6§lДобро пожаловать на GoidaCraft!");
        AuthCommands.send(player, SEP);
        AuthCommands.send(player, "§fПеред регистрацией необходимо ознакомиться с правилами сервера.");

        MutableComponent readLine = Component.literal("§7Введите ").append(
                Component.literal("§f/rules")
                        .withStyle(s -> s
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rules"))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("§7Открыть правила")))))
                .append(Component.literal("§7 чтобы прочитать правила."));
        player.sendSystemMessage(readLine);

        MutableComponent acceptLine = Component.literal("§7Прочитав правила, введите ").append(
                Component.literal("§f/acceptrules")
                        .withStyle(s -> s
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/acceptrules"))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("§7Принять правила и начать регистрацию")))))
                .append(Component.literal("§7 для продолжения."));
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
