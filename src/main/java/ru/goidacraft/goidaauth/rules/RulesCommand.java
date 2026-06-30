package ru.goidacraft.goidaauth.rules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class RulesCommand {

    private static final String SEP = "§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";

    private final RulesConfig config;

    public RulesCommand(RulesConfig config) {
        this.config = config;
    }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rules")
                .requires(src -> true)
                .executes(ctx -> showPage(ctx, 1))
                .then(Commands.literal("page")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .executes(ctx -> showPage(ctx, IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("categories")
                        .executes(this::showCategories))
                .then(Commands.literal("links")
                        .executes(this::showLinks))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(this::handleReload)));
    }

    private int showPage(CommandContext<CommandSourceStack> ctx, int page) {
        ServerPlayer player = sourcePlayer(ctx);
        if (player == null) return 0;

        List<RulesConfig.Category> cats = config.categories();
        if (cats.isEmpty()) {
            send(player, "§cПравила не загружены. Обратитесь к администрации.");
            return 0;
        }

        int total = cats.size();
        int p = Math.max(1, Math.min(page, total));
        RulesConfig.Category cat = cats.get(p - 1);

        send(player, SEP);
        send(player, "  §6§lПРАВИЛА GOIDACRAFT §8│ §e" + p + " §8/ §e" + total);
        send(player, SEP);
        send(player, "  §e§l" + cat.title());
        send(player, "");
        for (String rule : cat.rules()) {
            send(player, "§f  • " + rule);
        }
        send(player, "");
        send(player, SEP);
        sendNav(player, p, total);
        return 1;
    }

    private int showCategories(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = sourcePlayer(ctx);
        if (player == null) return 0;

        List<RulesConfig.Category> cats = config.categories();
        if (cats.isEmpty()) {
            send(player, "§cПравила не загружены. Обратитесь к администрации.");
            return 0;
        }

        send(player, SEP);
        send(player, "  §6§lПРАВИЛА GOIDACRAFT §8│ §eКатегории");
        send(player, SEP);
        for (int i = 0; i < cats.size(); i++) {
            final int page = i + 1;
            MutableComponent line = Component.literal("  §7" + page + ". ").append(
                    clickable("§f" + cats.get(i).title(), "/rules page " + page,
                            "§7Открыть раздел §f" + page));
            player.sendSystemMessage(line);
        }
        send(player, SEP);
        return 1;
    }

    private int showLinks(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = sourcePlayer(ctx);
        if (player == null) return 0;

        List<RulesConfig.Link> links = config.links();

        send(player, SEP);
        send(player, "  §6§lGOIDACRAFT §8│ §eСсылки");
        send(player, SEP);

        if (links.isEmpty()) {
            send(player, "§7  Ссылки не настроены.");
        } else {
            for (RulesConfig.Link link : links) {
                MutableComponent line = Component.literal("  ")
                        .append(openUrl("§f§n" + link.label(), link.url(), "§7Открыть: §f" + link.url()));
                if (link.note() != null) {
                    line.append(Component.literal("  " + link.note()));
                }
                player.sendSystemMessage(line);
            }
        }

        send(player, SEP);
        MutableComponent footer = Component.literal("  ")
                .append(clickable("§8[§6☰ Правила§8]", "/rules categories", "§7Разделы правил"));
        player.sendSystemMessage(footer);
        return 1;
    }

    private int handleReload(CommandContext<CommandSourceStack> ctx) {
        config.reload();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aПравила перезагружены из §f" + config.path().getFileName() + "§a."), true);
        return 1;
    }

    private void sendNav(ServerPlayer player, int page, int total) {
        MutableComponent nav = Component.literal("  ");

        if (page > 1) {
            nav.append(clickable("§8[§7◀§8]", "/rules page " + (page - 1), "§7Страница " + (page - 1)));
        } else {
            nav.append(Component.literal("§8[§7◀§8]"));
        }

        nav.append(Component.literal("  "));
        nav.append(clickable("§8[§6☰ Разделы§8]", "/rules categories", "§7Список всех разделов"));
        nav.append(Component.literal("  "));
        nav.append(clickable("§8[§b🔗 Ссылки§8]", "/rules links", "§7Сайт, Дискорд, соцсети"));
        nav.append(Component.literal("  "));

        if (page < total) {
            nav.append(clickable("§8[§7▶§8]", "/rules page " + (page + 1), "§7Страница " + (page + 1)));
        } else {
            nav.append(Component.literal("§8[§7▶§8]"));
        }

        player.sendSystemMessage(nav);
    }

    private static MutableComponent clickable(String text, String command, String hoverText) {
        return Component.literal(text).withStyle(s ->
                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                 .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                         Component.literal(hoverText))));
    }

    private static MutableComponent openUrl(String text, String url, String hoverText) {
        return Component.literal(text).withStyle(s ->
                s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                 .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                         Component.literal(hoverText))));
    }

    private static ServerPlayer sourcePlayer(CommandContext<CommandSourceStack> ctx) {
        try {
            return ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return null;
        }
    }

    private static void send(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text));
    }
}
