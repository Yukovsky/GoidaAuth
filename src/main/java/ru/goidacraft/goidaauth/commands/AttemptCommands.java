package ru.goidacraft.goidaauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import ru.goidacraft.goidaauth.database.DatabaseManager;
import ru.goidacraft.goidaauth.database.LoginAttempt;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of failed login attempts, for moderation.
 *
 * <p>Answers the question the shared-IP notice cannot: who tried to get into an account and failed.
 * A successful login is already visible through {@code users.last_ip}; a failed one leaves nothing
 * on the account by design, so it is recorded separately and read here.
 *
 * <p>Operators get read and list. Deleting recorded attempts is <b>not</b> here — it lives in
 * {@link ConsoleCommands}, because erasing evidence must stay with whoever controls the machine.
 */
public final class AttemptCommands {

    private static final int DEFAULT_LIMIT = 15;
    private static final String SEP = "§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";

    private AttemptCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d, DatabaseManager db) {
        d.register(Commands.literal("attempts")
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                // /attempts — последние попытки по серверу
                .executes(ctx -> recent(ctx, db, DEFAULT_LIMIT))
                .then(Commands.literal("recent")
                        .executes(ctx -> recent(ctx, db, DEFAULT_LIMIT))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 200))
                                .executes(ctx -> recent(ctx, db,
                                        IntegerArgumentType.getInteger(ctx, "limit")))))
                // /attempts player <ник> — кто ломился в этот аккаунт
                .then(Commands.literal("player")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        ctx.getSource().getServer().getPlayerNames(), b))
                                .executes(ctx -> byAccount(ctx, db, DEFAULT_LIMIT))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 200))
                                        .executes(ctx -> byAccount(ctx, db,
                                                IntegerArgumentType.getInteger(ctx, "limit"))))))
                // /attempts ip <адрес> — куда ломились с этого адреса
                .then(Commands.literal("ip")
                        .then(Commands.argument("address", StringArgumentType.word())
                                .executes(ctx -> byIp(ctx, db, DEFAULT_LIMIT))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 200))
                                        .executes(ctx -> byIp(ctx, db,
                                                IntegerArgumentType.getInteger(ctx, "limit")))))));
    }

    // ------------------------------------------------------------------

    private static int byAccount(CommandContext<CommandSourceStack> ctx, DatabaseManager db, int limit) {
        String name = StringArgumentType.getString(ctx, "player");
        var source = ctx.getSource();
        db.findAttemptsByAccount(name, limit).thenAccept(list -> source.getServer().execute(() -> {
            if (list.isEmpty()) {
                source.sendSuccess(() -> Component.literal(
                        "§eНеудачных попыток входа в §f" + name + "§e не зафиксировано."), false);
                return;
            }
            header(source, "Попытки входа в аккаунт §f" + name, list.size());
            // Aimed at one account, so the interesting axis is where the attempts came from.
            summarise(source, list, LoginAttempt::ip, "адрес");
            details(source, list, a -> "§7от §f" + nz(a.ip()));
        }));
        return 1;
    }

    private static int byIp(CommandContext<CommandSourceStack> ctx, DatabaseManager db, int limit) {
        String ip = DatabaseManager.normalizeIp(StringArgumentType.getString(ctx, "address"));
        var source = ctx.getSource();
        db.findAttemptsByIp(ip, limit).thenAccept(list -> source.getServer().execute(() -> {
            if (list.isEmpty()) {
                source.sendSuccess(() -> Component.literal(
                        "§eС адреса §f" + ip + "§e неудачных попыток не зафиксировано."), false);
                return;
            }
            header(source, "Попытки входа с адреса §f" + ip, list.size());
            // Coming from one address, so the interesting axis is which accounts were targeted.
            summarise(source, list, LoginAttempt::username, "аккаунт");
            details(source, list, a -> "§7на §f" + a.username());
        }));
        return 1;
    }

    private static int recent(CommandContext<CommandSourceStack> ctx, DatabaseManager db, int limit) {
        var source = ctx.getSource();
        db.findRecentAttempts(limit).thenAccept(list -> source.getServer().execute(() -> {
            if (list.isEmpty()) {
                source.sendSuccess(() -> Component.literal(
                        "§eНеудачных попыток входа не зафиксировано."), false);
                return;
            }
            header(source, "Последние неудачные попытки входа", list.size());
            details(source, list, a -> "§f" + a.username() + " §7← §f" + nz(a.ip()));
        }));
        return 1;
    }

    // ------------------------------------------------------------------

    private static void header(CommandSourceStack source, String title, int count) {
        source.sendSuccess(() -> Component.literal(SEP), false);
        source.sendSuccess(() -> Component.literal("  §6§l" + title + " §7(" + count + ")"), false);
        source.sendSuccess(() -> Component.literal(SEP), false);
    }

    /** Counts repeats along one axis, so a persistent source stands out from a one-off typo. */
    private static void summarise(CommandSourceStack source, List<LoginAttempt> list,
                                  java.util.function.Function<LoginAttempt, String> axis, String label) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LoginAttempt a : list) {
            counts.merge(nz(axis.apply(a)), 1, Integer::sum);
        }
        if (counts.size() < 2) return;
        StringBuilder sb = new StringBuilder("§7Всего по " + label + "у: ");
        counts.entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                .forEach(e -> sb.append("§f").append(e.getKey())
                        .append(" §8×").append(e.getValue()).append("§7, "));
        String line = sb.substring(0, sb.length() - 2);
        source.sendSuccess(() -> Component.literal(line), false);
        source.sendSuccess(() -> Component.literal(""), false);
    }

    private static void details(CommandSourceStack source, List<LoginAttempt> list,
                                java.util.function.Function<LoginAttempt, String> subject) {
        for (LoginAttempt a : list) {
            String line = "§8• §7" + ago(a.at()) + "  " + subject.apply(a)
                    + "  §8(" + a.reasonLabel() + ")";
            source.sendSuccess(() -> Component.literal(line), false);
        }
        source.sendSuccess(() -> Component.literal(SEP), false);
    }

    /** Relative time reads better than a timestamp when scanning a list for a pattern. */
    private static String ago(Instant at) {
        if (at == null) return "когда-то";
        long min = Duration.between(at, Instant.now()).toMinutes();
        if (min < 1) return "только что";
        if (min < 60) return min + " мин назад";
        long hours = min / 60;
        if (hours < 24) return hours + " ч назад";
        return (hours / 24) + " дн назад";
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "?" : s;
    }
}
