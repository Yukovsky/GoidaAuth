package ru.goidacraft.goidaauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.storage.LevelResource;
import ru.goidacraft.goidaauth.Config;
import ru.goidacraft.goidaauth.auth.AuthSession;
import ru.goidacraft.goidaauth.auth.AuthSessionManager;
import ru.goidacraft.goidaauth.auth.PasswordHasher;
import ru.goidacraft.goidaauth.database.DatabaseManager;
import ru.goidacraft.goidaauth.transfer.AccountTransfer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class AuthCommands {
    private AuthCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d, DatabaseManager db,
                                PasswordHasher hasher, AuthSessionManager sessions) {
        registerLogin(d, "login", db, hasher, sessions);
        registerLogin(d, "l", db, hasher, sessions);
        registerRegister(d, "register", db, hasher, sessions);
        registerRegister(d, "reg", db, hasher, sessions);
        registerPremium(d, "premium", db, sessions);
        registerUnpremium(d, "unpremium", db, hasher, sessions);
        registerForceRegister(d, "forceregister", db, hasher, sessions);
        registerForceLogin(d, "forcelogin", sessions);
        registerChangePassword(d, "changepassword", db, hasher, sessions);
        registerSetPassword(d, "setpassword", db, hasher);
        registerImportAuthMe(d, "importauthme", db);
        registerUnregister(d, "unregister", db);
        registerClearAuthEffects(d, "clearautheffects");
        registerAccountLookup(d, "account", db);
        registerAccountIp(d, "accountip", db);
        registerMultiAccounts(d, "multiaccounts", db);
        registerTransferAccount(d, "transferaccount", db);
        registerPurgeAccount(d, "purgeaccount", db);
        registerAcceptRules(d, "acceptrules", sessions);
    }

    // ---- Command registration ----

    private static void registerLogin(CommandDispatcher<CommandSourceStack> d, String name,
                                      DatabaseManager db, PasswordHasher hasher, AuthSessionManager sessions) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> true)
                .then(Commands.argument("password", StringArgumentType.greedyString())
                        .executes(ctx -> handleLogin(ctx, db, hasher, sessions))));
    }

    private static void registerRegister(CommandDispatcher<CommandSourceStack> d, String name,
                                         DatabaseManager db, PasswordHasher hasher, AuthSessionManager sessions) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> true)
                .executes(ctx -> handleRegisterUsage(ctx))
                .then(Commands.argument("password", StringArgumentType.word())
                        .executes(ctx -> handleRegisterSingle(ctx, db, hasher, sessions))
                        .then(Commands.argument("confirm", StringArgumentType.word())
                                .executes(ctx -> handleRegister(ctx, db, hasher, sessions)))));
    }

    private static void registerPremium(CommandDispatcher<CommandSourceStack> d, String name,
                                        DatabaseManager db, AuthSessionManager sessions) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> true)
                // Player self-service: /premium → warning, /premium confirm → apply.
                .executes(ctx -> handlePremiumSelfPrompt(ctx, sessions))
                .then(Commands.literal("confirm")
                        .executes(ctx -> handlePremiumSelfConfirm(ctx, db, sessions)))
                // Admin: /premium <player> (unchanged).
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerNames(), b))
                        .executes(ctx -> handlePremiumAdmin(ctx, db))));
    }

    private static void registerUnpremium(CommandDispatcher<CommandSourceStack> d, String name,
                                          DatabaseManager db, PasswordHasher hasher, AuthSessionManager sessions) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> true)
                // Player self-service: /unpremium → warning, /unpremium confirm → apply.
                .executes(ctx -> handleUnpremiumSelfPrompt(ctx, sessions))
                .then(Commands.literal("confirm")
                        .executes(ctx -> handleUnpremiumSelfConfirm(ctx, db, sessions)))
                // Admin: /unpremium <player> [password] (unchanged).
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerNames(), b))
                        .executes(ctx -> handleUnpremium(ctx, db, hasher, null))
                        .then(Commands.argument("password", StringArgumentType.word())
                                .executes(ctx -> handleUnpremium(ctx, db, hasher,
                                        StringArgumentType.getString(ctx, "password"))))));
    }

    private static void registerForceRegister(CommandDispatcher<CommandSourceStack> d, String name,
                                              DatabaseManager db, PasswordHasher hasher,
                                              AuthSessionManager sessions) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerNames(), b))
                        .then(Commands.argument("password", StringArgumentType.word())
                                .executes(ctx -> handleForceRegister(ctx, db, hasher, sessions)))));
    }

    private static void registerForceLogin(CommandDispatcher<CommandSourceStack> d, String name,
                                           AuthSessionManager sessions) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerNames(), b))
                        .executes(ctx -> handleForceLogin(ctx, sessions))));
    }

    private static void registerChangePassword(CommandDispatcher<CommandSourceStack> d, String name,
                                               DatabaseManager db, PasswordHasher hasher,
                                               AuthSessionManager sessions) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> true)
                .then(Commands.argument("old_password", StringArgumentType.word())
                        .then(Commands.argument("new_password", StringArgumentType.word())
                                .then(Commands.argument("confirm", StringArgumentType.word())
                                        .executes(ctx -> handleChangePassword(ctx, db, hasher, sessions))))));
    }

    private static void registerImportAuthMe(CommandDispatcher<CommandSourceStack> d, String name,
                                            DatabaseManager db) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> handleImportAuthMe(ctx, db, "plugins/AuthMe/authme.db"))
                .then(Commands.argument("path", StringArgumentType.greedyString())
                        .executes(ctx -> handleImportAuthMe(ctx, db,
                                StringArgumentType.getString(ctx, "path")))));
    }

    private static void registerUnregister(CommandDispatcher<CommandSourceStack> d, String name,
                                           DatabaseManager db) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerNames(), b))
                        .executes(ctx -> handleUnregister(ctx, db))));
    }

    private static void registerClearAuthEffects(CommandDispatcher<CommandSourceStack> d, String name) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerNames(), b))
                        .executes(ctx -> handleClearAuthEffects(ctx))));
    }

    private static void registerSetPassword(CommandDispatcher<CommandSourceStack> d, String name,
                                            DatabaseManager db, PasswordHasher hasher) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerNames(), b))
                        .then(Commands.argument("password", StringArgumentType.word())
                                .executes(ctx -> handleSetPassword(ctx, db, hasher)))));
    }

    private static void registerAccountLookup(CommandDispatcher<CommandSourceStack> d, String name,
                                              DatabaseManager db) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("ip")
                        .then(Commands.argument("ip_address", StringArgumentType.word())
                                .executes(ctx -> handleAccountByIp(ctx, db))))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerNames(), b))
                        .executes(ctx -> handleAccountByPlayer(ctx, db))));
    }

    private static void registerAccountIp(CommandDispatcher<CommandSourceStack> d, String name,
                                          DatabaseManager db) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerNames(), b))
                        .executes(ctx -> handleAccountIp(ctx, db))));
    }

    private static void registerMultiAccounts(CommandDispatcher<CommandSourceStack> d, String name,
                                              DatabaseManager db) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("min_accounts", IntegerArgumentType.integer(1))
                        .executes(ctx -> handleMultiAccounts(ctx, db))));
    }

    // ---- Handlers ----

    private static int handleLogin(CommandContext<CommandSourceStack> ctx,
                                   DatabaseManager db, PasswordHasher hasher, AuthSessionManager sessions) {
        ServerPlayer player = sourcePlayer(ctx);
        if (player == null) return 0;
        String password = StringArgumentType.getString(ctx, "password");
        return handleLoginForPlayer(player, password, db, hasher, sessions);
    }

    private static int handleRegisterUsage(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = sourcePlayer(ctx);
        if (player == null) return 0;
        send(player, Config.MSG_REGISTER_PROMPT.get());
        return 0;
    }

    private static int handleRegisterSingle(CommandContext<CommandSourceStack> ctx,
                                            DatabaseManager db, PasswordHasher hasher,
                                            AuthSessionManager sessions) {
        if (Config.REGISTER_CONFIRM_REQUIRED.get()) {
            return handleRegisterUsage(ctx);
        }
        String password = StringArgumentType.getString(ctx, "password");
        return handleRegisterWithConfirm(ctx, db, hasher, sessions, password, password);
    }

    private static int handleRegister(CommandContext<CommandSourceStack> ctx,
                                      DatabaseManager db, PasswordHasher hasher,
                                      AuthSessionManager sessions) {
        String password = StringArgumentType.getString(ctx, "password");
        String confirm = StringArgumentType.getString(ctx, "confirm");
        return handleRegisterWithConfirm(ctx, db, hasher, sessions, password, confirm);
    }

    private static int handleRegisterWithConfirm(CommandContext<CommandSourceStack> ctx,
                                                 DatabaseManager db, PasswordHasher hasher,
                                                 AuthSessionManager sessions,
                                                 String password, String confirm) {
        ServerPlayer player = sourcePlayer(ctx);
        if (player == null) return 0;
        return handleRegisterForPlayer(player, password, confirm, db, hasher, sessions);
    }

    private static int handlePremiumAdmin(CommandContext<CommandSourceStack> ctx, DatabaseManager db) {
        String targetName = StringArgumentType.getString(ctx, "player");
        var source = ctx.getSource();
        db.findByName(targetName).thenAccept(opt -> source.getServer().execute(() -> {
            if (opt.isEmpty()) {
                source.sendFailure(Component.literal("§cИгрок §f" + targetName + "§c не найден в базе."));
                return;
            }
            db.setPremium(targetName, true, opt.get().uuid())
              .whenComplete((v, err) -> source.getServer().execute(() -> {
                  if (err != null) {
                      ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("setPremium failed for {}", targetName, err);
                      source.sendFailure(Component.literal("§cОшибка записи в БД."));
                      return;
                  }
                  source.sendSuccess(() -> Component.literal(
                          "§aАккаунт §f" + targetName + "§a помечен как лицензионный."), true);
              }));
        }));
        return 1;
    }

    // ---- Player self-service /premium and /unpremium ----

    private static final ConcurrentHashMap<UUID, Long> PENDING_PREMIUM = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> PENDING_UNPREMIUM = new ConcurrentHashMap<>();
    private static final long CONFIRM_TTL_MS = 60_000L;

    private static boolean requireAuthorized(ServerPlayer player, AuthSessionManager sessions) {
        if (sessions.isAuthorized(player.getUUID())) return true;
        // Gate: self-service premium toggles are only available after /login or /register.
        send(player, Config.MSG_BLOCKED_ACTION.get());
        return false;
    }

    private static boolean consumePending(ConcurrentHashMap<UUID, Long> map, UUID id) {
        Long ts = map.remove(id);
        return ts != null && (System.currentTimeMillis() - ts) <= CONFIRM_TTL_MS;
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static int handlePremiumSelfPrompt(CommandContext<CommandSourceStack> ctx,
                                               AuthSessionManager sessions) {
        ServerPlayer player = sourcePlayer(ctx);
        if (player == null) return 0; // console must use /premium <player>
        if (!requireAuthorized(player, sessions)) return 0;

        PENDING_PREMIUM.put(player.getUUID(), System.currentTimeMillis());
        send(player, "§6§l⚠ Внимание!");
        send(player, "§eКоманда §f/premium §eпомечает ваш аккаунт как §aлицензионный§e.");
        send(player, "§eПосле подтверждения вход будет §cТОЛЬКО §eс лицензионного клиента Mojang.");
        send(player, "§c§lОтменить сможете только вы сами через /unpremium, пока имеете доступ.");
        send(player, "§cЕсли вы НЕ владелец лицензии этого ника — вы потеряете доступ к аккаунту!");
        send(player, "§7Подтверждайте §cТОЛЬКО §7если реально играете с лицензией.");
        MutableComponent confirm = Component.literal("§a§l[ Подтвердить ]")
                .withStyle(s -> s
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/premium confirm"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("§7Нажмите, чтобы подтвердить"))));
        player.sendSystemMessage(Component.literal("§7Нажмите ").append(confirm)
                .append(Component.literal(" §7или введите §f/premium confirm §7(60 секунд).")));
        return 1;
    }

    private static int handlePremiumSelfConfirm(CommandContext<CommandSourceStack> ctx,
                                                DatabaseManager db, AuthSessionManager sessions) {
        ServerPlayer player = sourcePlayer(ctx);
        if (player == null) return 0;
        if (!requireAuthorized(player, sessions)) return 0;
        if (!consumePending(PENDING_PREMIUM, player.getUUID())) {
            send(player, "§eСначала введите §f/premium§e, чтобы запросить подтверждение.");
            return 0;
        }

        String username = player.getGameProfile().getName();
        db.findByName(username).thenAccept(opt -> player.server.execute(() -> {
            if (opt.isEmpty()) {
                send(player, "§cАккаунт не найден в базе. Сначала зарегистрируйтесь.");
                return;
            }
            if (opt.get().premium()) {
                send(player, "§eУ вас уже включён лицензионный режим.");
                return;
            }
            // Keep the existing (offline) UUID for now; it is reconciled to the real Mojang UUID on
            // the next online login (AuthEventHandler premium branch), which also migrates playerdata.
            db.setPremium(username, true, opt.get().uuid())
              .whenComplete((v, err) -> player.server.execute(() -> {
                  if (err != null) {
                      ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("setPremium failed for {}", username, err);
                      send(player, "§cОшибка записи в БД. Попробуйте снова.");
                      return;
                  }
                  ru.goidacraft.goidaauth.GoidaAuth.LOGGER.info("{} self-enabled premium", username);
                  player.connection.disconnect(Component.literal(
                          "§a§lЛицензионный режим включён.\n\n" +
                          "§fЗайдите заново с §aлицензионного§f клиента Mojang —\n" +
                          "§fвход произойдёт автоматически, пароль не нужен."));
              }));
        }));
        return 1;
    }

    private static int handleUnpremiumSelfPrompt(CommandContext<CommandSourceStack> ctx,
                                                 AuthSessionManager sessions) {
        ServerPlayer player = sourcePlayer(ctx);
        if (player == null) return 0; // console must use /unpremium <player>
        if (!requireAuthorized(player, sessions)) return 0;

        PENDING_UNPREMIUM.put(player.getUUID(), System.currentTimeMillis());
        send(player, "§6§l⚠ Внимание!");
        send(player, "§eКоманда §f/unpremium §eвозвращает аккаунт в §6пиратский §eрежим (вход по паролю).");
        send(player, "§7Ваш прогресс сохранится.");
        send(player, "§7Если у вас §cне установлен пароль§7 (вы заходили с лицензии),");
        send(player, "§7после этого нужно будет заново зарегистрироваться через §f/register§7.");
        MutableComponent confirm = Component.literal("§a§l[ Подтвердить ]")
                .withStyle(s -> s
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/unpremium confirm"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("§7Нажмите, чтобы подтвердить"))));
        player.sendSystemMessage(Component.literal("§7Нажмите ").append(confirm)
                .append(Component.literal(" §7или введите §f/unpremium confirm §7(60 секунд).")));
        return 1;
    }

    private static int handleUnpremiumSelfConfirm(CommandContext<CommandSourceStack> ctx,
                                                  DatabaseManager db, AuthSessionManager sessions) {
        ServerPlayer player = sourcePlayer(ctx);
        if (player == null) return 0;
        if (!requireAuthorized(player, sessions)) return 0;
        if (!consumePending(PENDING_UNPREMIUM, player.getUUID())) {
            send(player, "§eСначала введите §f/unpremium§e, чтобы запросить подтверждение.");
            return 0;
        }

        String username = player.getGameProfile().getName();
        db.findByName(username).thenAccept(opt -> player.server.execute(() -> {
            if (opt.isEmpty()) {
                send(player, "§cАккаунт не найден в базе.");
                return;
            }
            if (!opt.get().premium()) {
                send(player, "§eВаш аккаунт уже в пиратском режиме.");
                return;
            }

            UUID real = player.getUUID();
            UUID offline = offlineUuid(username);
            // Carry the player's current (premium) progress down to the offline UUID so they don't
            // lose their inventory when they come back as a cracked player.
            reverseMigrateToOffline(player, real, offline);

            boolean hasRealPassword = !opt.get().passwordHash().startsWith("premium:");
            if (hasRealPassword) {
                db.setPremium(username, false, offline)
                  .thenRun(() -> ru.goidacraft.goidaauth.GoidaAuthApi.fireUuidChanged(real, offline, username))
                  .whenComplete((v, err) -> player.server.execute(() -> {
                      if (err != null) {
                          ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("setPremium failed for {}", username, err);
                          send(player, "§cОшибка записи в БД. Попробуйте снова.");
                          return;
                      }
                      ru.goidacraft.goidaauth.GoidaAuth.LOGGER.info("{} self-disabled premium", username);
                      player.connection.disconnect(Component.literal(
                              "§aВы вернулись в пиратский режим.\n\n" +
                              "§fЗайдите заново и войдите через §a/login <пароль>§f."));
                  }));
            } else {
                // Premium account never had a real password — drop the record so the player can
                // re-register a password on next join.
                db.deleteUser(username)
                  .thenRun(() -> ru.goidacraft.goidaauth.GoidaAuthApi.fireUuidChanged(real, offline, username))
                  .whenComplete((v, err) -> player.server.execute(() -> {
                      if (err != null) {
                          ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("deleteUser failed for {}", username, err);
                          send(player, "§cОшибка записи в БД. Попробуйте снова.");
                          return;
                      }
                      ru.goidacraft.goidaauth.GoidaAuth.LOGGER.info("{} self-disabled premium (no password — deleted)", username);
                      player.connection.disconnect(Component.literal(
                              "§aВы вернулись в пиратский режим.\n\n" +
                              "§fУ вас не было пароля — зайдите заново и зарегистрируйтесь через §a/register§f."));
                  }));
            }
        }));
        return 1;
    }

    /**
     * Copies the player's current real-UUID data down to their offline UUID (overwriting), so a
     * premium→cracked downgrade keeps their progress. Runs on the server thread.
     */
    private static void reverseMigrateToOffline(ServerPlayer player, UUID real, UUID offline) {
        if (real.equals(offline)) return;
        try {
            var server = player.server;
            server.getPlayerList().saveAll(); // flush current in-memory state to the real-UUID files
            Path root = server.getWorldPath(LevelResource.ROOT);
            copyPrefixOverwrite(root.resolve("playerdata"), real.toString(), offline.toString(), player);
            copyOverwrite(root.resolve("advancements").resolve(real + ".json"),
                          root.resolve("advancements").resolve(offline + ".json"), player, "advancements");
            copyOverwrite(root.resolve("stats").resolve(real + ".json"),
                          root.resolve("stats").resolve(offline + ".json"), player, "stats");
            for (String pattern : Config.TRANSFER_EXTRA_FILES.get()) {
                copyOverwrite(root.resolve(pattern.replace("{uuid}", real.toString())),
                              root.resolve(pattern.replace("{uuid}", offline.toString())), player, pattern);
            }
        } catch (Exception e) {
            ru.goidacraft.goidaauth.GoidaAuth.LOGGER.warn("reverse playerdata migration failed for {}: {}",
                    player.getGameProfile().getName(), e.getMessage());
        }
    }

    private static void copyPrefixOverwrite(Path dir, String srcPrefix, String dstPrefix, ServerPlayer player) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().startsWith(srcPrefix)).forEach(src -> {
                String dstName = dstPrefix + src.getFileName().toString().substring(srcPrefix.length());
                copyOverwrite(src, dir.resolve(dstName), player, dir.getFileName().toString());
            });
        } catch (Exception e) {
            ru.goidacraft.goidaauth.GoidaAuth.LOGGER.warn("copyPrefixOverwrite failed in {}: {}",
                    dir.getFileName(), e.getMessage());
        }
    }

    private static void copyOverwrite(Path src, Path dst, ServerPlayer player, String label) {
        try {
            if (!Files.exists(src)) return;
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            ru.goidacraft.goidaauth.GoidaAuth.LOGGER.info("Reverse-migrated {} for {}: {} → {}",
                    label, player.getGameProfile().getName(), src.getFileName(), dst.getFileName());
        } catch (Exception e) {
            ru.goidacraft.goidaauth.GoidaAuth.LOGGER.warn("Could not reverse-migrate {} for {}: {}",
                    label, player.getGameProfile().getName(), e.getMessage());
        }
    }

    private static int handleUnpremium(CommandContext<CommandSourceStack> ctx,
                                       DatabaseManager db, PasswordHasher hasher, String password) {
        String targetName = StringArgumentType.getString(ctx, "player");
        var source = ctx.getSource();
        db.findByName(targetName).thenAccept(opt -> source.getServer().execute(() -> {
            if (opt.isEmpty()) {
                source.sendFailure(Component.literal("§cИгрок §f" + targetName + "§c не найден в базе."));
                return;
            }
            UUID oldUuid = opt.get().uuid();
            UUID offlineUUID = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + targetName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            db.setPremium(targetName, false, offlineUUID)
              .thenRun(() -> ru.goidacraft.goidaauth.GoidaAuthApi.fireUuidChanged(oldUuid, offlineUUID, targetName))
              .whenComplete((v, err) -> source.getServer().execute(() -> {
                  if (err != null) {
                      ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("setPremium failed for {}", targetName, err);
                      source.sendFailure(Component.literal("§cОшибка записи в БД."));
                      return;
                  }
                  if (password != null && !password.isBlank()) {
                      String hash = hasher.hash(password.toCharArray());
                      db.updatePassword(targetName, hash);
                      source.sendSuccess(() -> Component.literal(
                              "§aАккаунт §f" + targetName + "§a переведён в пиратский режим. Пароль установлен."), true);
                  } else {
                      source.sendSuccess(() -> Component.literal(
                              "§aАккаунт §f" + targetName + "§a переведён в пиратский режим. " +
                              "§eУстановите пароль через §f/setpassword " + targetName + " <пароль>"), true);
                  }
                  // Kick the player if they're online — DB write is confirmed, so Velocity
                  // will see the updated premium=false on their very next connection attempt.
                  var online = source.getServer().getPlayerList().getPlayerByName(targetName);
                  if (online != null) {
                      online.connection.disconnect(Component.literal(
                              "Ваш аккаунт переведён в пиратский режим. Войдите заново."));
                  }
              }));
        }));
        return 1;
    }

    private static int handleForceRegister(CommandContext<CommandSourceStack> ctx,
                                           DatabaseManager db, PasswordHasher hasher,
                                           AuthSessionManager sessions) {
        String targetName = StringArgumentType.getString(ctx, "player");
        String password = StringArgumentType.getString(ctx, "password");
        var source = ctx.getSource();
        var server = source.getServer();

        db.findByName(targetName).thenAccept(opt -> {
            if (opt.isPresent()) {
                server.execute(() -> source.sendFailure(
                        Component.literal("§cИгрок §f" + targetName + "§c уже зарегистрирован.")));
                return;
            }
            ServerPlayer online = server.getPlayerList().getPlayerByName(targetName);
            UUID uuid = online != null
                    ? online.getUUID()
                    : UUID.nameUUIDFromBytes(("OfflinePlayer:" + targetName).getBytes(StandardCharsets.UTF_8));
            String hash = hasher.hash(password.toCharArray());
            String ip = online != null ? online.getIpAddress() : null;

            db.register(uuid, targetName, hash, false, ip).thenRun(() -> server.execute(() -> {
                source.sendSuccess(() -> Component.literal(
                        "§aИгрок §f" + targetName + "§a зарегистрирован."), true);
                if (online != null) {
                    AuthSession session = sessions.get(online.getUUID()).orElse(null);
                    if (session != null && !session.isAuthorized()) {
                        session.setAuthorized(true);
                        session.setRegistered(true);
                        ru.goidacraft.goidaauth.events.AuthEventHandler.onAuthorized(online, session);
                        send(online, "§aВас зарегистрировал и авторизовал администратор.");
                    }
                }
            })).exceptionally(ex -> {
                ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("forceregister failed for {}", targetName, ex);
                server.execute(() -> source.sendFailure(Component.literal("§cОшибка при регистрации.")));
                return null;
            });
        }).exceptionally(ex -> {
            ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("forceregister lookup failed for {}", targetName, ex);
            server.execute(() -> source.sendFailure(Component.literal("§cОшибка при проверке базы.")));
            return null;
        });
        return 1;
    }

    private static int handleForceLogin(CommandContext<CommandSourceStack> ctx,
                                        AuthSessionManager sessions) {
        String targetName = StringArgumentType.getString(ctx, "player");
        var source = ctx.getSource();
        var server = source.getServer();

        ServerPlayer online = server.getPlayerList().getPlayerByName(targetName);
        if (online == null) {
            source.sendFailure(Component.literal("§cИгрок §f" + targetName + "§c не в сети."));
            return 0;
        }
        AuthSession session = sessions.get(online.getUUID()).orElse(null);
        if (session == null) {
            source.sendFailure(Component.literal("§cСессия §f" + targetName + "§c не найдена."));
            return 0;
        }
        if (session.isAuthorized()) {
            source.sendFailure(Component.literal("§eИгрок §f" + targetName + "§e уже авторизован."));
            return 0;
        }
        session.setAuthorized(true);
        session.setRegistered(true);
        ru.goidacraft.goidaauth.events.AuthEventHandler.onAuthorized(online, session);
        send(online, "§aВас авторизовал администратор.");
        source.sendSuccess(() -> Component.literal("§aИгрок §f" + targetName + "§a авторизован."), true);
        return 1;
    }

    private static int handleChangePassword(CommandContext<CommandSourceStack> ctx,
                                            DatabaseManager db, PasswordHasher hasher,
                                            AuthSessionManager sessions) {
        ServerPlayer player = sourcePlayer(ctx);
        if (player == null) return 0;
        if (!sessions.isAuthorized(player.getUUID())) {
            send(player, "§cСначала войдите в аккаунт.");
            return 0;
        }

        String oldPass = StringArgumentType.getString(ctx, "old_password");
        String newPass = StringArgumentType.getString(ctx, "new_password");
        String confirm = StringArgumentType.getString(ctx, "confirm");

        if (!newPass.equals(confirm)) {
            send(player, Config.MSG_REGISTER_MISMATCH.get());
            return 0;
        }
        if (newPass.length() < Config.MIN_PASSWORD_LEN.get()) {
            send(player, Config.MSG_PASSWORD_TOO_SHORT.get());
            return 0;
        }
        if (newPass.length() > Config.MAX_PASSWORD_LEN.get()) {
            send(player, Config.MSG_PASSWORD_TOO_LONG.get());
            return 0;
        }

        String username = player.getGameProfile().getName();
        db.findByName(username).thenAccept(opt -> {
            if (opt.isEmpty()) {
                player.server.execute(() -> send(player, "§cАккаунт не найден в базе."));
                return;
            }
            if (opt.get().premium()) {
                player.server.execute(() -> send(player,
                        "§eВам не нужно менять пароль — у вас премиум-аккаунт."));
                return;
            }
            if (!hasher.verify(opt.get().passwordHash(), oldPass.toCharArray())) {
                player.server.execute(() -> send(player, "§cСтарый пароль неверен."));
                return;
            }
            String newHash = hasher.hash(newPass.toCharArray());
            db.updatePassword(username, newHash).thenRun(() ->
                    player.server.execute(() -> send(player, "§aПароль успешно изменён.")));
        }).exceptionally(ex -> {
            ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("changepassword failed for {}", username, ex);
            player.server.execute(() -> send(player, "§cОшибка при смене пароля."));
            return null;
        });
        return 1;
    }

    private static int handleImportAuthMe(CommandContext<CommandSourceStack> ctx,
                                          DatabaseManager db, String rawPath) {
        var source = ctx.getSource();
        var server = source.getServer();

        Path path = Path.of(rawPath);
        if (!path.isAbsolute()) {
            path = Path.of("").toAbsolutePath().resolve(rawPath);
        }
        Path finalPath = path;

        if (!finalPath.toFile().exists()) {
            source.sendFailure(Component.literal("§cФайл не найден: §f" + finalPath));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "§eНачинаю импорт из §f" + finalPath + "§e ..."), false);

        db.importFromAuthMe(finalPath).thenAccept(counts -> server.execute(() ->
                source.sendSuccess(() -> Component.literal(
                        "§aИмпорт завершён. Добавлено: §f" + counts[0] +
                        "§a, пропущено (уже есть / пустые): §f" + counts[1] + "§a."), true)
        )).exceptionally(ex -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("importauthme failed", cause);
            server.execute(() -> source.sendFailure(
                    Component.literal("§cОшибка импорта: " + cause.getMessage())));
            return null;
        });
        return 1;
    }

    private static int handleUnregister(CommandContext<CommandSourceStack> ctx, DatabaseManager db) {
        String targetName = StringArgumentType.getString(ctx, "player");
        var source = ctx.getSource();
        var server = source.getServer();

        db.deleteUser(targetName).thenAccept(deleted -> server.execute(() -> {
            if (!deleted) {
                source.sendFailure(Component.literal("§cИгрок §f" + targetName + "§c не найден в базе."));
                return;
            }
            source.sendSuccess(() -> Component.literal(
                    "§aИгрок §f" + targetName + "§a удалён из базы данных."), true);
            ServerPlayer online = server.getPlayerList().getPlayerByName(targetName);
            if (online != null) {
                online.connection.disconnect(Component.literal(
                        "§cВаш аккаунт был удалён администратором. При следующем входе зарегистрируйтесь снова."));
            }
        }));
        return 1;
    }

    private static int handleClearAuthEffects(CommandContext<CommandSourceStack> ctx) {
        String targetName = StringArgumentType.getString(ctx, "player");
        var source = ctx.getSource();

        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(targetName);
        if (online == null) {
            source.sendFailure(Component.literal("§cИгрок §f" + targetName + "§c не в сети."));
            return 0;
        }
        online.removeEffect(MobEffects.BLINDNESS);
        online.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        online.removeEffect(MobEffects.INVISIBILITY);
        source.sendSuccess(() -> Component.literal(
                "§aЭффекты авторизации сняты с игрока §f" + targetName + "§a."), true);
        return 1;
    }

    private static int handleSetPassword(CommandContext<CommandSourceStack> ctx,
                                         DatabaseManager db, PasswordHasher hasher) {
        String targetName = StringArgumentType.getString(ctx, "player");
        String newPass = StringArgumentType.getString(ctx, "password");
        var source = ctx.getSource();

        db.findByName(targetName).thenAccept(opt -> {
            if (opt.isEmpty()) {
                source.getServer().execute(() -> source.sendFailure(
                        Component.literal("§cИгрок §f" + targetName + "§c не найден в базе.")));
                return;
            }
            if (opt.get().premium()) {
                source.getServer().execute(() -> source.sendFailure(
                        Component.literal("§eАккаунт §f" + targetName + "§e является премиум — пароль не используется.")));
                return;
            }
            String newHash = hasher.hash(newPass.toCharArray());
            db.updatePassword(targetName, newHash).thenRun(() ->
                    source.getServer().execute(() -> source.sendSuccess(() ->
                            Component.literal("§aПароль игрока §f" + targetName + "§a изменён."), true)));
        }).exceptionally(ex -> {
            ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("setpassword failed for {}", targetName, ex);
            source.getServer().execute(() -> source.sendFailure(
                    Component.literal("§cОшибка при смене пароля.")));
            return null;
        });
        return 1;
    }

    private static int handleAccountByPlayer(CommandContext<CommandSourceStack> ctx, DatabaseManager db) {
        String targetName = StringArgumentType.getString(ctx, "player");
        var source = ctx.getSource();
        db.findByName(targetName).thenAccept(opt -> source.getServer().execute(() -> {
            if (opt.isEmpty()) {
                source.sendFailure(Component.literal("§cИгрок §f" + targetName + "§c не найден в базе."));
                return;
            }
            String ip = opt.get().lastIp();
            if (ip == null || ip.isBlank()) {
                source.sendFailure(Component.literal("§cУ игрока §f" + targetName + "§c нет сохранённого IP."));
                return;
            }
            db.findNamesByIp(ip).thenAccept(names -> source.getServer().execute(() ->
                    sendAccountList(source, ip, names, "§eАккаунты на IP §f" + ip +
                            " §e(по игроку §f" + targetName + "§e):")));
        }));
        return 1;
    }

    private static int handleAccountByIp(CommandContext<CommandSourceStack> ctx, DatabaseManager db) {
        String rawIp = StringArgumentType.getString(ctx, "ip_address");
        String ip = DatabaseManager.normalizeIp(rawIp);
        var source = ctx.getSource();
        db.findNamesByIp(ip).thenAccept(names -> source.getServer().execute(() ->
                sendAccountList(source, ip, names, "§eАккаунты на IP §f" + ip + "§e:")));
        return 1;
    }

    private static int handleAccountIp(CommandContext<CommandSourceStack> ctx, DatabaseManager db) {
        String targetName = StringArgumentType.getString(ctx, "player");
        var source = ctx.getSource();
        db.findByName(targetName).thenAccept(opt -> source.getServer().execute(() -> {
            if (opt.isEmpty()) {
                source.sendFailure(Component.literal("§cИгрок §f" + targetName + "§c не найден в базе."));
                return;
            }
            String ip = opt.get().lastIp();
            if (ip == null || ip.isBlank()) {
                source.sendFailure(Component.literal("§cУ игрока §f" + targetName + "§c нет сохранённого IP."));
                return;
            }
            source.sendSuccess(() -> Component.literal(
                    "§eПоследний IP игрока §f" + targetName + "§e: §f" + ip), false);
        }));
        return 1;
    }

    private static int handleMultiAccounts(CommandContext<CommandSourceStack> ctx, DatabaseManager db) {
        int minCount = IntegerArgumentType.getInteger(ctx, "min_accounts");
        var source = ctx.getSource();
        db.findIpsWithMultipleAccounts(minCount).thenAccept(entries -> source.getServer().execute(() -> {
            if (entries.isEmpty()) {
                source.sendSuccess(() -> Component.literal(
                        "§eНет IP с количеством аккаунтов больше §f" + minCount + "§e."), false);
                return;
            }
            source.sendSuccess(() -> Component.literal(
                    "§eIP с более чем §f" + minCount + " §eаккаунтами §7(" + entries.size() + ")§e:"), false);
            for (var entry : entries) {
                String ip = entry.getKey();
                List<String> names = entry.getValue();
                source.sendSuccess(() -> Component.literal(
                        "§f" + ip + " §7(§f" + names.size() + "§7): §a" + String.join("§7, §a", names)), false);
            }
        }));
        return 1;
    }

    private static void sendAccountList(CommandSourceStack source, String ip, List<String> names, String header) {
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§eНет аккаунтов, связанных с IP §f" + ip + "§e."), false);
            return;
        }
        source.sendSuccess(() -> Component.literal(header), false);
        source.sendSuccess(() -> Component.literal("§a" + String.join("§7, §a", names) +
                " §7(всего: §f" + names.size() + "§7)"), false);
    }

    // ---- Shared player-level logic ----

    private static int handleLoginForPlayer(ServerPlayer player, String password,
                                            DatabaseManager db, PasswordHasher hasher,
                                            AuthSessionManager sessions) {
        AuthSession session = sessions.get(player.getUUID()).orElse(null);
        if (session == null) return 0;
        if (session.isAuthorized()) {
            send(player, Config.MSG_ALREADY_AUTH.get());
            return 0;
        }

        String username = player.getGameProfile().getName();

        db.findByName(username).thenAccept(opt -> {
            if (opt.isEmpty()) {
                player.server.execute(() -> send(player, Config.MSG_NOT_REGISTERED.get()));
                return;
            }
            String storedHash = opt.get().passwordHash();
            boolean ok = hasher.verify(storedHash, password.toCharArray());
            // Upgrade BCrypt → PBKDF2 on successful login (still on IO thread)
            if (ok && hasher.needsRehash(storedHash)) {
                db.updatePassword(username, hasher.hash(password.toCharArray()));
            }
            player.server.execute(() -> {
                if (!sessions.get(player.getUUID()).map(s -> s == session).orElse(false)) return;
                if (!ok) {
                    int fails = session.incrementFailures();
                    if (fails >= Config.MAX_LOGIN_ATTEMPTS.get()) {
                        player.connection.disconnect(Component.literal(Config.MSG_LOGIN_FAIL.get()));
                    } else {
                        send(player, Config.MSG_LOGIN_FAIL.get());
                    }
                    return;
                }
                session.setAuthorized(true);
                session.setRegistered(true);
                db.updateLastSeen(username, player.getIpAddress());
                ru.goidacraft.goidaauth.events.AuthEventHandler.onAuthorized(player, session);
                send(player, Config.MSG_LOGIN_SUCCESS.get());
            });
        }).exceptionally(ex -> {
            ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("Login lookup failed for {}", username, ex);
            player.server.execute(() -> send(player, "§cОшибка входа, попробуйте снова."));
            return null;
        });
        return 1;
    }

    private static int handleRegisterForPlayer(ServerPlayer player, String password, String confirm,
                                               DatabaseManager db, PasswordHasher hasher,
                                               AuthSessionManager sessions) {
        AuthSession session = sessions.get(player.getUUID()).orElse(null);
        if (session == null) return 0;
        if (session.isAuthorized()) {
            send(player, Config.MSG_ALREADY_AUTH.get());
            return 0;
        }
        if (!session.isRulesAccepted()) {
            send(player, "§cСначала ознакомьтесь с правилами §f(/rules)§c и введите §f/acceptrules§c.");
            return 0;
        }

        if (!password.equals(confirm)) {
            send(player, Config.MSG_REGISTER_MISMATCH.get());
            return 0;
        }
        if (password.length() < Config.MIN_PASSWORD_LEN.get()) {
            send(player, Config.MSG_PASSWORD_TOO_SHORT.get());
            return 0;
        }
        if (password.length() > Config.MAX_PASSWORD_LEN.get()) {
            send(player, Config.MSG_PASSWORD_TOO_LONG.get());
            return 0;
        }

        String username = player.getGameProfile().getName();
        String ip = player.getIpAddress();

        db.findByName(username).thenAccept(opt -> {
            if (opt.isPresent()) {
                player.server.execute(() -> send(player, Config.MSG_REGISTER_TAKEN.get()));
                return;
            }
            String hash = hasher.hash(password.toCharArray());
            db.register(player.getUUID(), username, hash, false, ip)
                    .thenRun(() -> player.server.execute(() -> {
                        if (!sessions.get(player.getUUID()).map(s -> s == session).orElse(false)) return;
                        session.setAuthorized(true);
                        session.setRegistered(true);
                        ru.goidacraft.goidaauth.events.AuthEventHandler.onAuthorized(player, session);
                        send(player, Config.MSG_REGISTER_SUCCESS.get());
                    }))
                    .exceptionally(ex -> {
                        ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("DB register failed for {}", username, ex);
                        player.server.execute(() -> send(player, "§cОшибка регистрации, попробуйте снова."));
                        return null;
                    });
        }).exceptionally(ex -> {
            ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("Register lookup failed for {}", username, ex);
            player.server.execute(() -> send(player, "§cОшибка регистрации, попробуйте снова."));
            return null;
        });
        return 1;
    }

    private static void registerTransferAccount(CommandDispatcher<CommandSourceStack> d, String name,
                                                DatabaseManager db) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                // /transferaccount backups — list available backups
                .then(Commands.literal("backups")
                        .executes(ctx -> handleListBackups(ctx)))
                // /transferaccount restore <id> — revert a previous transfer
                .then(Commands.literal("restore")
                        .then(Commands.argument("backup_id", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        AccountTransfer.listBackups(ctx.getSource().getServer()), b))
                                .executes(ctx -> handleRestore(ctx, db))))
                // /transferaccount <from> <to> [keep_source]
                .then(Commands.argument("from", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerNames(), b))
                        .then(Commands.argument("to", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        ctx.getSource().getServer().getPlayerNames(), b))
                                // default: source is removed so nothing is duplicated
                                .executes(ctx -> handleTransferAccount(ctx, db, true))
                                // keep_source: leave the source intact (may duplicate items)
                                .then(Commands.literal("keep_source")
                                        .executes(ctx -> handleTransferAccount(ctx, db, false))))));
    }

    private static int handleTransferAccount(CommandContext<CommandSourceStack> ctx,
                                              DatabaseManager db, boolean deleteSource) {
        String fromName = StringArgumentType.getString(ctx, "from");
        String toName   = StringArgumentType.getString(ctx, "to");
        var source = ctx.getSource();
        var server = source.getServer();

        if (fromName.equalsIgnoreCase(toName)) {
            source.sendFailure(Component.literal("§cИмена источника и цели совпадают."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "§eПеренос прогресса §f" + fromName + " §e→ §f" + toName + "§e..."), false);

        db.findByName(fromName).thenAcceptBoth(db.findByName(toName), (fromOpt, toOpt) ->
                server.execute(() -> {
                    if (fromOpt.isEmpty()) {
                        source.sendFailure(Component.literal(
                                "§cИгрок §f" + fromName + "§c не зарегистрирован в GoidaAuth."));
                        return;
                    }

                    UUID fromUuid = fromOpt.get().uuid();
                    // Prefer the authoritative join UUID of an online target, then the DB record,
                    // and only fall back to the deterministic offline UUID as a last resort.
                    ServerPlayer toOnline = server.getPlayerList().getPlayerByName(toName);
                    UUID toUuid = toOnline != null
                            ? toOnline.getUUID()
                            : toOpt.map(ru.goidacraft.goidaauth.database.UserRecord::uuid)
                                   .orElseGet(() -> UUID.nameUUIDFromBytes(
                                           ("OfflinePlayer:" + toName).getBytes(StandardCharsets.UTF_8)));

                    if (fromUuid.equals(toUuid)) {
                        source.sendFailure(Component.literal(
                                "§cОба аккаунта имеют одинаковый UUID — перенос невозможен."));
                        return;
                    }

                    // execute() kicks online players, waits for them to leave, backs up both
                    // accounts, then copies. Completion may arrive on an off-thread executor.
                    AccountTransfer.execute(server, db, fromName, toName, fromUuid, toUuid,
                                    fromOpt.get(), deleteSource)
                            .whenComplete((result, err) -> server.execute(() -> {
                                if (err != null) {
                                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                                    ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("transferaccount failed", cause);
                                    source.sendFailure(Component.literal(
                                            "§cОшибка при переносе: " + cause.getMessage()));
                                    return;
                                }
                                source.sendSuccess(() -> Component.literal(
                                        buildTransferReport(fromName, toName, result, deleteSource)), true);
                            }));
                })
        ).exceptionally(ex -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("transferaccount lookup failed", cause);
            server.execute(() -> source.sendFailure(Component.literal(
                    "§cОшибка при переносе: " + cause.getMessage())));
            return null;
        });

        return 1;
    }

    private static int handleRestore(CommandContext<CommandSourceStack> ctx, DatabaseManager db) {
        String backupId = StringArgumentType.getString(ctx, "backup_id");
        var source = ctx.getSource();
        var server = source.getServer();

        source.sendSuccess(() -> Component.literal(
                "§eВосстановление из бэкапа §f" + backupId + "§e..."), false);

        AccountTransfer.restore(server, db, backupId)
                .whenComplete((res, err) -> server.execute(() -> {
                    if (err != null) {
                        Throwable cause = err.getCause() != null ? err.getCause() : err;
                        ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("transfer restore failed", cause);
                        source.sendFailure(Component.literal(
                                "§cОшибка восстановления: " + cause.getMessage()));
                        return;
                    }
                    String msg = "§aВосстановление завершено: §f" + res.fromName() + " §a/ §f" + res.toName()
                            + (res.sourceReRegistered()
                                ? "\n§7Аккаунт §f" + res.fromName() + "§7 возвращён в GoidaAuth."
                                : "")
                            + "\n§7Игроки, если были онлайн, кикнуты — данные применятся при следующем входе.";
                    source.sendSuccess(() -> Component.literal(msg), true);
                }));
        return 1;
    }

    private static void registerAcceptRules(CommandDispatcher<CommandSourceStack> d, String name,
                                            AuthSessionManager sessions) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> true)
                .executes(ctx -> handleAcceptRules(ctx, sessions)));
    }

    private static int handleAcceptRules(CommandContext<CommandSourceStack> ctx, AuthSessionManager sessions) {
        ServerPlayer player = sourcePlayer(ctx);
        if (player == null) return 0;
        AuthSession session = sessions.get(player.getUUID()).orElse(null);
        if (session == null) return 0;
        if (session.isAuthorized()) {
            send(player, "§eВы уже авторизованы на сервере.");
            return 0;
        }
        if (session.isRulesAccepted()) {
            send(player, "§eВы уже приняли правила. " + Config.MSG_REGISTER_PROMPT.get());
            return 0;
        }
        session.setRulesAccepted(true);
        send(player, "§aВы приняли правила сервера. Добро пожаловать!");
        send(player, Config.MSG_REGISTER_PROMPT.get());
        return 1;
    }

    private static void registerPurgeAccount(CommandDispatcher<CommandSourceStack> d, String name,
                                             DatabaseManager db) {
        d.register(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerNames(), b))
                        .executes(ctx -> handlePurgeAccount(ctx, db))));
    }

    private static int handlePurgeAccount(CommandContext<CommandSourceStack> ctx, DatabaseManager db) {
        String name = StringArgumentType.getString(ctx, "player");
        var source = ctx.getSource();
        var server = source.getServer();

        source.sendSuccess(() -> Component.literal(
                "§eПолная зачистка §f" + name + "§e — игрок будет удалён, как будто не играл..."), false);

        db.findByName(name).thenAccept(opt -> server.execute(() -> {
            ServerPlayer online = server.getPlayerList().getPlayerByName(name);
            UUID uuid = opt.map(ru.goidacraft.goidaauth.database.UserRecord::uuid)
                    .orElseGet(() -> online != null
                            ? online.getUUID()
                            : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)));
            ru.goidacraft.goidaauth.database.UserRecord record = opt.orElse(null);

            AccountTransfer.purge(server, db, name, record, uuid)
                    .whenComplete((res, err) -> server.execute(() -> {
                        if (err != null) {
                            Throwable cause = err.getCause() != null ? err.getCause() : err;
                            ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("purgeaccount failed", cause);
                            source.sendFailure(Component.literal("§cОшибка зачистки: " + cause.getMessage()));
                            return;
                        }
                        source.sendSuccess(() -> Component.literal(buildPurgeReport(res)), true);
                    }));
        })).exceptionally(ex -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            ru.goidacraft.goidaauth.GoidaAuth.LOGGER.error("purgeaccount lookup failed", cause);
            server.execute(() -> source.sendFailure(Component.literal("§cОшибка зачистки: " + cause.getMessage())));
            return null;
        });
        return 1;
    }

    private static String buildPurgeReport(AccountTransfer.PurgeResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("§aЗачистка §f").append(r.name())
          .append(" §aзавершена — следов в зоне мода не осталось.");
        if (!r.wiped().isEmpty()) {
            sb.append("\n§a✔ §fУдалено: §7").append(String.join("§f, §7", r.wiped()));
        }
        sb.append("\n§eLuckPerms: ").append(r.lpCleared() ? "§aправа очищены" : "§7нет / пропущен");
        sb.append("\n§eБД GoidaAuth: ").append(r.dbDeleted() ? "§aзапись удалена" : "§7записи не было");
        sb.append("\n§7Плагины (CoreProtect / GoidaVote / скины) не затронуты.");
        sb.append("\n§7Бэкап: §f").append(r.backupId())
          .append(" §7(откат: §f/transferaccount restore ").append(r.backupId()).append("§7)");
        return sb.toString();
    }

    private static int handleListBackups(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        List<String> backups = AccountTransfer.listBackups(source.getServer());
        if (backups.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§eБэкапов переноса пока нет."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                "§eДоступные бэкапы переноса §7(" + backups.size() + ")§e:"), false);
        for (String id : backups) {
            source.sendSuccess(() -> Component.literal("§7• §f" + id), false);
        }
        source.sendSuccess(() -> Component.literal(
                "§7Откат: §f/transferaccount restore <id>"), false);
        return 1;
    }

    private static String buildTransferReport(String fromName, String toName,
                                               AccountTransfer.Result result, boolean deleteSource) {
        StringBuilder sb = new StringBuilder();
        sb.append("§aПеренос §f").append(fromName)
          .append(" §a→ §f").append(toName).append(" §aзавершён.");

        if (!result.copied().isEmpty()) {
            sb.append("\n§a✔ §fСкопировано: §7").append(String.join("§f, §7", result.copied()));
        }
        if (!result.skipped().isEmpty()) {
            sb.append("\n§7✘ Не найдено: ").append(String.join(", ", result.skipped()));
        }
        sb.append("\n§eСкорборд: ").append(
                result.scoreboardTransferred() ? "§aочки перенесены" : "§7очков не найдено");
        sb.append("\n§eLuckPerms: ").append(
                result.lpTransferred() ? "§aправа перенесены" : "§7не установлен / пропущен");

        if (deleteSource) {
            sb.append("\n§7Источник §f").append(fromName).append("§7: ").append(
                    result.sourceDeleted() ? "§aудалён (БД + файлы)" : "§cне удалён из БД");
        } else {
            sb.append("\n§6⚠ Источник §f").append(fromName)
              .append("§6 оставлен — возможно дублирование предметов.");
        }

        sb.append("\n§7Бэкап: §f").append(result.backupId())
          .append(" §7(откат: §f/transferaccount restore ").append(result.backupId()).append("§7)");
        return sb.toString();
    }

    public static boolean tryHandleFallback(ServerPlayer player, String raw,
                                            DatabaseManager db, PasswordHasher hasher,
                                            AuthSessionManager sessions) {
        if (raw == null || raw.isBlank()) return false;
        String trimmed = raw.trim();
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        int space = trimmed.indexOf(' ');
        String root = space < 0 ? trimmed : trimmed.substring(0, space);
        String args = space < 0 ? "" : trimmed.substring(space + 1).trim();

        if (root.equalsIgnoreCase("login") || root.equalsIgnoreCase("l")) {
            if (args.isEmpty()) {
                send(player, Config.MSG_LOGIN_PROMPT.get());
                return true;
            }
            handleLoginForPlayer(player, args, db, hasher, sessions);
            return true;
        }
        if (root.equalsIgnoreCase("register") || root.equalsIgnoreCase("reg")) {
            if (args.isEmpty()) {
                send(player, Config.MSG_REGISTER_PROMPT.get());
                return true;
            }
            String[] parts = args.split("\\s+");
            String password = parts.length > 0 ? parts[0] : "";
            String confirm = parts.length > 1 ? parts[1] : "";
            if (confirm.isEmpty()) {
                if (Config.REGISTER_CONFIRM_REQUIRED.get()) {
                    send(player, Config.MSG_REGISTER_PROMPT.get());
                    return true;
                }
                confirm = password;
            }
            handleRegisterForPlayer(player, password, confirm, db, hasher, sessions);
            return true;
        }
        return false;
    }

    private static ServerPlayer sourcePlayer(CommandContext<CommandSourceStack> ctx) {
        try {
            return ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return null;
        }
    }

    public static void send(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text));
    }
}
