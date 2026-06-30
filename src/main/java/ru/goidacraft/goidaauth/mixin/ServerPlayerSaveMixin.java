package ru.goidacraft.goidaauth.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.goidacraft.goidaauth.Config;
import ru.goidacraft.goidaauth.GoidaAuth;
import ru.goidacraft.goidaauth.auth.AuthSession;

/**
 * Keeps the on-disk position of an unauthenticated player at their real (join) location.
 *
 * <p>When {@code teleport_to_spawn} is enabled, {@link ru.goidacraft.goidaauth.events.AuthEventHandler}
 * physically moves the player entity to spawn until they log in. The original position is held only in
 * the {@link AuthSession}. The problem: Minecraft serialises the player's <b>current</b> position to
 * {@code playerdata} on disconnect, during world auto-save, and on {@code save-all}. If the player
 * never authenticates (closes the client, times out, is kicked) — or the server crashes after an
 * auto-save — the spawn position gets persisted and the real location is lost forever. On the next
 * join the mod re-captures that spawn position as the "original", so the loss is permanent.
 *
 * <p>This mirrors the limbo handling in mature auth mods: while the session is present and not yet
 * authorised, we overwrite the {@code Pos}/{@code Rotation}/{@code Dimension} that vanilla just wrote
 * with the stored original. The live entity is untouched (it stays at spawn for the lockdown); only
 * the serialised form is corrected, so any save at any moment writes the real location to disk.
 *
 * <p>Hook point: {@code ServerPlayer.addAdditionalSaveData(CompoundTag)} TAIL. {@code Entity.saveWithoutId}
 * writes {@code Pos}/{@code Rotation} before calling this, and {@code ServerPlayer} writes
 * {@code Dimension} inside it, so all three keys are present in the tag and can be replaced here.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerSaveMixin {

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void goidaauth$persistOriginalPosition(CompoundTag tag, CallbackInfo ci) {
        if (!Config.TELEPORT_TO_SAFE_ROOM.get()) return;

        ServerPlayer self = (ServerPlayer) (Object) this;
        AuthSession session;
        try {
            session = GoidaAuth.get().sessions().get(self.getUUID()).orElse(null);
        } catch (Throwable ignored) {
            // GoidaAuth may not be fully initialised yet during early saves — never break a save.
            return;
        }
        if (session == null || session.isAuthorized()) return;

        Vec3 pos = session.storedPosition();
        String dim = session.storedDimension();
        if (pos == null) return;

        ListTag posTag = new ListTag();
        posTag.add(DoubleTag.valueOf(pos.x));
        posTag.add(DoubleTag.valueOf(pos.y));
        posTag.add(DoubleTag.valueOf(pos.z));
        tag.put("Pos", posTag);

        ListTag rotTag = new ListTag();
        rotTag.add(FloatTag.valueOf(session.storedYaw()));
        rotTag.add(FloatTag.valueOf(session.storedPitch()));
        tag.put("Rotation", rotTag);

        if (dim != null) {
            // PlayerList.load reads this via DimensionType.parseLegacy, which accepts a plain
            // "namespace:path" string — the same form vanilla writes here.
            tag.putString("Dimension", dim);
        }
    }
}
