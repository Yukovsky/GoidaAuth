package ru.goidacraft.goidaauth.mixin;

import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.goidacraft.goidaauth.GoidaAuth;
import ru.goidacraft.goidaauth.commands.AuthCommands;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void goidaauth$handleChatCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        if (player == null) return;
        var sessions = GoidaAuth.get().sessions();
        if (sessions.isAuthorized(player.getUUID())) return;
        boolean handled = AuthCommands.tryHandleFallback(
                player, packet.command(), GoidaAuth.get().database(), GoidaAuth.get().hasher(), sessions);
        if (handled) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void goidaauth$handleSignedChatCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        if (player == null) return;
        var sessions = GoidaAuth.get().sessions();
        if (sessions.isAuthorized(player.getUUID())) return;
        boolean handled = AuthCommands.tryHandleFallback(
                player, packet.command(), GoidaAuth.get().database(), GoidaAuth.get().hasher(), sessions);
        if (handled) {
            ci.cancel();
        }
    }

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void goidaauth$handleChat(ServerboundChatPacket packet, CallbackInfo ci) {
        if (player == null) return;
        String msg = packet.message();
        if (msg == null || !msg.startsWith("/")) return;
        var sessions = GoidaAuth.get().sessions();
        if (sessions.isAuthorized(player.getUUID())) return;
        boolean handled = AuthCommands.tryHandleFallback(
                player, msg, GoidaAuth.get().database(), GoidaAuth.get().hasher(), sessions);
        if (handled) {
            ci.cancel();
        }
    }

    @Inject(method = "tryHandleChat", at = @At("HEAD"), cancellable = true)
    private void goidaauth$tryHandleChat(String message, Runnable task, CallbackInfo ci) {
        if (player == null) return;
        if (message == null) return;
        var sessions = GoidaAuth.get().sessions();
        if (sessions.isAuthorized(player.getUUID())) return;
        boolean handled = AuthCommands.tryHandleFallback(
                player, message, GoidaAuth.get().database(), GoidaAuth.get().hasher(), sessions);
        if (handled) {
            ci.cancel();
        }
    }

    /**
     * Blocks all inventory slot interaction (dragging, moving, picking up and dropping items via the
     * inventory screen) for players who are not allowed to play yet. This covers both GoidaAuth's own
     * pre-login lockdown and any external gate (e.g. GoidaDI's Discord-link lockdown) that registers a
     * {@link ru.goidacraft.goidaauth.GoidaAuthApi.InteractionBlocker}. The container is re-synced to the
     * client afterwards so the cancelled move does not leave a visual desync.
     */
    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void goidaauth$handleContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (player == null) return;
        var sessions = GoidaAuth.get().sessions();
        boolean blocked = !sessions.isAuthorized(player.getUUID())
                || ru.goidacraft.goidaauth.GoidaAuthApi.isExternallyBlocked(player);
        if (!blocked) return;
        ci.cancel();
        try {
            player.containerMenu.sendAllDataToRemote();
        } catch (Throwable ignored) {
            // best-effort resync; never propagate from a packet handler
        }
    }
}
