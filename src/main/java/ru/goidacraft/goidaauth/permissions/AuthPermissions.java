package ru.goidacraft.goidaauth.permissions;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionType;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import ru.goidacraft.goidaauth.GoidaAuth;

import java.util.ArrayList;
import java.util.function.Predicate;

public final class AuthPermissions {
    private static final ArrayList<PermissionNode<?>> NODES = new ArrayList<>();
    private static final PermissionNode.PermissionResolver<Boolean> DEFAULT_ALLOW =
            (player, uuid, context) -> true;

    public static final CommandPermissionNode LOGIN = nodeAllCommand("login");
    public static final CommandPermissionNode REGISTER = nodeAllCommand("register");
    public static final CommandPermissionNode PREMIUM = nodeAllCommand("premium");

    private AuthPermissions() {}

    private static CommandPermissionNode nodeAllCommand(String nodeName) {
        PermissionNode<Boolean> node = node("command." + nodeName, PermissionTypes.BOOLEAN, DEFAULT_ALLOW);
        return new CommandPermissionNode(node, Commands.LEVEL_ALL);
    }

    private static <T> PermissionNode<T> node(String nodeName, PermissionType<T> type,
                                              PermissionNode.PermissionResolver<T> defaultResolver) {
        PermissionNode<T> node = new PermissionNode<>(GoidaAuth.MODID, nodeName, type, defaultResolver);
        NODES.add(node);
        return node;
    }

    public record CommandPermissionNode(PermissionNode<Boolean> node, int fallbackLevel)
            implements Predicate<CommandSourceStack> {
        @Override
        public boolean test(CommandSourceStack source) {
            if (source.getEntity() instanceof ServerPlayer player) {
                return PermissionAPI.getPermission(player, node);
            }
            return source.hasPermission(fallbackLevel);
        }
    }

    public static void registerPermissionNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(NODES);
    }
}
