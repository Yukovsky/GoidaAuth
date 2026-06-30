package ru.goidacraft.goidaauth.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.EventHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.goidacraft.goidaauth.compat.LuckPermsLoginGate;

/**
 * Intercepts the firePlayerLoggedIn call inside PlayerList.placeNewPlayer.
 *
 * <p>Targeting PlayerList (a Minecraft class) rather than EventHooks (a NeoForge class) because
 * NeoForge classes live in a separate transformer context that mixin cannot inject into from mods.
 *
 * <p>When LuckPerms is present as a NeoForge mod the event is deferred via LuckPermsLoginGate
 * so the capability has time to initialise before the event fires. Without LuckPerms the call
 * goes through normally — no behaviour change.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Redirect(
        method = "placeNewPlayer",
        at = @At(value = "INVOKE",
                 target = "Lnet/neoforged/neoforge/event/EventHooks;firePlayerLoggedIn(Lnet/minecraft/world/entity/player/Player;)V")
    )
    private void goidaauth$interceptFirePlayerLoggedIn(Player player) {
        if (!ModList.get().isLoaded("luckperms") || !(player instanceof ServerPlayer sp)) {
            EventHooks.firePlayerLoggedIn(player);
            return;
        }
        LuckPermsLoginGate.defer(sp);
    }
}
