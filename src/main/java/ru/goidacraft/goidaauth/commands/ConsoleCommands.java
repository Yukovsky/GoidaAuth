package ru.goidacraft.goidaauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import ru.goidacraft.goidaauth.GoidaAuth;
import ru.goidacraft.goidaauth.database.DatabaseManager;
import ru.goidacraft.goidaauth.database.UserRecord;

import java.util.List;
import java.util.Optional;

/**
 * Commands that are reachable <b>only from the server console</b> — not from an operator, a command
 * block, a function or RCON.
 *
 * <p>The point is separation of trust: a server administrator holds OP but not necessarily shell
 * access, and the commands here can erase evidence of account linkage. Keeping them at the console
 * means only someone who already controls the machine can use them, so OP alone cannot cover tracks.
 *
 * <p>The gate is {@code source instanceof MinecraftServer}: the console's command source is the
 * server itself, while a command block is a {@code BaseCommandBlock}, a function runs under a
 * wrapped source and RCON uses {@code RconConsoleSource}. Because the check lives in
 * {@code requires()}, the command is also invisible in tab-completion for players.
 */
public final class ConsoleCommands {

    private ConsoleCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d, DatabaseManager db) {
        d.register(Commands.literal("goidaauth")
                .requires(ConsoleCommands::isConsole)
                .then(Commands.literal("forget")
                        // /goidaauth forget <у_кого> <кого> [confirm]
                        .then(Commands.argument("holder", StringArgumentType.word())
                                .suggests(ConsoleCommands::suggestOnline)
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .suggests(ConsoleCommands::suggestOnline)
                                        .executes(ctx -> forgetAccount(ctx, db, false))
                                        .then(Commands.literal("confirm")
                                                .executes(ctx -> forgetAccount(ctx, db, true))))
                                // /goidaauth forget <у_кого> ip <адрес> [confirm]
                                .then(Commands.literal("ip")
                                        .then(Commands.argument("address", StringArgumentType.word())
                                                .executes(ctx -> forgetIp(ctx, db, false))
                                                .then(Commands.literal("confirm")
                                                        .executes(ctx -> forgetIp(ctx, db, true))))))));
    }

    private static boolean isConsole(CommandSourceStack src) {
        return src.source instanceof MinecraftServer;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestOnline(CommandContext<CommandSourceStack> ctx,
                          com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        return SharedSuggestionProvider.suggest(ctx.getSource().getServer().getPlayerNames(), b);
    }

    // ------------------------------------------------------------------
    // /goidaauth forget <holder> <target>
    // ------------------------------------------------------------------

    /**
     * Clears the linkage data of {@code target} so it stops appearing in {@code holder}'s shared-IP
     * report. Only {@code target} is touched — the direction is which of the two names gets wiped,
     * so {@code forget A B} and {@code forget B A} are different operations. {@code holder} is used
     * to show what exactly is being unlinked, which catches a mistyped name before anything is lost.
     */
    private static int forgetAccount(CommandContext<CommandSourceStack> ctx, DatabaseManager db,
                                     boolean confirmed) {
        String holder = StringArgumentType.getString(ctx, "holder");
        String target = StringArgumentType.getString(ctx, "target");
        var source = ctx.getSource();
        var server = source.getServer();

        if (holder.equalsIgnoreCase(target)) {
            source.sendFailure(Component.literal("§cОба ника совпадают — укажите разные аккаунты."));
            return 0;
        }

        db.findByName(holder).thenCombine(db.findByName(target), (holderOpt, targetOpt) -> {
            server.execute(() -> {
                if (targetOpt.isEmpty()) {
                    source.sendFailure(Component.literal(
                            "§cАккаунт §f" + target + "§c не найден в базе."));
                    return;
                }
                UserRecord rec = targetOpt.get();
                if (!confirmed) {
                    previewAccount(source, holder, holderOpt, target, rec);
                    return;
                }
                db.clearLinkage(target).thenAccept(changed -> server.execute(() -> {
                    if (!changed) {
                        source.sendFailure(Component.literal("§cНичего не изменилось — запись не найдена."));
                        return;
                    }
                    GoidaAuth.LOGGER.info("CONSOLE: linkage of '{}' wiped (unlinked from '{}')", target, holder);
                    source.sendSuccess(() -> Component.literal(
                            "§aГотово. §f" + target + "§a больше не связан с §f" + holder + "§a по IP.\n"
                            + "§7Связь вернётся, если §f" + target + "§7 снова войдёт с того же адреса."), true);
                }));
            });
            return null;
        }).exceptionally(ex -> {
            GoidaAuth.LOGGER.error("console forget lookup failed", ex);
            server.execute(() -> source.sendFailure(Component.literal("§cОшибка обращения к базе.")));
            return null;
        });
        return 1;
    }

    private static void previewAccount(CommandSourceStack source, String holder,
                                       Optional<UserRecord> holderOpt, String target, UserRecord rec) {
        String targetIp = DatabaseManager.normalizeIp(rec.lastIp());
        String holderIp = holderOpt.map(r -> DatabaseManager.normalizeIp(r.lastIp())).orElse(null);

        source.sendSuccess(() -> Component.literal(
                "§eУ аккаунта §f" + target + "§e будут очищены last_ip и hwid."), false);
        source.sendSuccess(() -> Component.literal(
                "§7  текущий last_ip: §f" + (targetIp == null ? "§8(пусто)" : targetIp)), false);

        if (holderOpt.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "§6⚠ Аккаунт §f" + holder + "§6 не найден в базе — проверьте ник."), false);
        } else if (holderIp == null) {
            source.sendSuccess(() -> Component.literal(
                    "§6⚠ У §f" + holder + "§6 не сохранён IP — связи и так не видно."), false);
        } else if (!holderIp.equals(targetIp)) {
            source.sendSuccess(() -> Component.literal(
                    "§6⚠ Эти аккаунты сейчас НЕ связаны: у §f" + holder + "§6 адрес §f" + holderIp
                    + "§6. Проверьте, тот ли ник."), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "§aСвязь подтверждена: общий адрес §f" + holderIp), false);
        }

        source.sendSuccess(() -> Component.literal(
                "§7Подтвердите: §f/goidaauth forget " + holder + " " + target + " confirm"), false);
    }

    // ------------------------------------------------------------------
    // /goidaauth forget <holder> ip <address>
    // ------------------------------------------------------------------

    /** Same operation applied to every account on an address, except the holder itself. */
    private static int forgetIp(CommandContext<CommandSourceStack> ctx, DatabaseManager db,
                                boolean confirmed) {
        String holder = StringArgumentType.getString(ctx, "holder");
        String ip = DatabaseManager.normalizeIp(StringArgumentType.getString(ctx, "address"));
        var source = ctx.getSource();
        var server = source.getServer();

        db.findNamesByIp(ip).thenAccept(names -> server.execute(() -> {
            List<String> targets = names.stream()
                    .filter(n -> n != null && !n.equalsIgnoreCase(holder))
                    .toList();
            if (targets.isEmpty()) {
                source.sendSuccess(() -> Component.literal(
                        "§eНа адресе §f" + ip + "§e нет других аккаунтов, кроме §f" + holder + "§e."), false);
                return;
            }
            if (!confirmed) {
                source.sendSuccess(() -> Component.literal(
                        "§eБудет очищен last_ip и hwid у §f" + targets.size() + "§e аккаунтов:"), false);
                source.sendSuccess(() -> Component.literal(
                        "§f" + String.join("§7, §f", targets)), false);
                source.sendSuccess(() -> Component.literal(
                        "§7Аккаунт §f" + holder + "§7 не затрагивается."), false);
                source.sendSuccess(() -> Component.literal(
                        "§7Подтвердите: §f/goidaauth forget " + holder + " ip " + ip + " confirm"), false);
                return;
            }
            for (String name : targets) {
                db.clearLinkage(name);
            }
            GoidaAuth.LOGGER.info("CONSOLE: linkage wiped for {} accounts on {} (kept '{}')",
                    targets.size(), ip, holder);
            source.sendSuccess(() -> Component.literal(
                    "§aОчищено аккаунтов: §f" + targets.size() + "§a. §f" + holder + "§a не затронут.\n"
                    + "§7Связь вернётся у тех, кто снова войдёт с этого адреса."), true);
        }));
        return 1;
    }
}
