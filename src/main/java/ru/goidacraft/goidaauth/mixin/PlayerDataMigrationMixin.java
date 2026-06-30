package ru.goidacraft.goidaauth.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.goidacraft.goidaauth.Config;
import ru.goidacraft.goidaauth.GoidaAuth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Patch 03 — playerdata offline-UUID -> real-UUID migration (Component C-3).
 *
 * <p>Behind Velocity, a licensed player arrives with their <b>real</b> Mojang UUID (stable on every
 * join). Any pre-proxy / cracked data they accumulated lives under the offline UUID
 * ({@code UUID.nameUUIDFromBytes("OfflinePlayer:<name>")}). We copy that data to the real-UUID path
 * <b>before</b> the server reads the player's NBT, so the first premium login does not show an
 * empty inventory.
 *
 * <p>Hook point: {@code PlayerList.load(ServerPlayer)} HEAD. At this moment {@code placeNewPlayer}
 * has constructed the player with the final forwarded profile (real UUID + name), but the
 * playerdata / advancements / stats files have not yet been read. This replaces the old
 * {@code ServerLoginPacketListenerImplMixin.reverseMigrateFromOnline / migratePlayerData}, which
 * ran in {@code handleHello} where the real forwarded UUID is not yet available behind a proxy.
 *
 * <p>Cracked players need no migration: in offline mode Velocity assigns the same offline UUID the
 * data already uses, so {@code real.equals(offline)} is true and the hook is a no-op for them.
 *
 * <p>NOTE: verify the {@code load} method descriptor against your mappings. On 1.21.1 mojmap it is
 * {@code Optional<CompoundTag> load(ServerPlayer)}. If your mapping set differs, adjust the
 * {@code method = "load"} target / return generic accordingly.
 */
@Mixin(PlayerList.class)
public abstract class PlayerDataMigrationMixin {

    @Inject(method = "load", at = @At("HEAD"))
    private void goidaauth$migrateOfflineToReal(ServerPlayer player,
                                                CallbackInfoReturnable<java.util.Optional<CompoundTag>> cir) {
        try {
            String name = player.getGameProfile().getName();
            UUID real = player.getUUID();
            UUID offline = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            if (real.equals(offline)) return; // cracked/offline join — nothing to migrate

            MinecraftServer server = player.server;
            Path root = server.getWorldPath(LevelResource.ROOT);

            // playerdata: copy every sidecar too (.dat, .dat_old, .cosarmor, mod sidecars, ...)
            migrateByPrefix(root.resolve("playerdata"), offline.toString(), real.toString(), name);
            // advancements + stats are single UUID-named files
            copyIfAbsent(root.resolve("advancements").resolve(offline + ".json"),
                         root.resolve("advancements").resolve(real + ".json"), name, "advancements");
            copyIfAbsent(root.resolve("stats").resolve(offline + ".json"),
                         root.resolve("stats").resolve(real + ".json"), name, "stats");

            // Configurable extra per-player files (mirrors /transferaccount TRANSFER_EXTRA_FILES).
            for (String pattern : Config.TRANSFER_EXTRA_FILES.get()) {
                Path src = root.resolve(pattern.replace("{uuid}", offline.toString()));
                Path dst = root.resolve(pattern.replace("{uuid}", real.toString()));
                copyIfAbsent(src, dst, name, pattern);
            }
        } catch (Exception e) {
            GoidaAuth.LOGGER.warn("playerdata migration failed for {}: {}",
                    player.getGameProfile().getName(), e.getMessage());
        }
    }

    /** Copies every file in {@code dir} whose name starts with {@code srcPrefix} to {@code dstPrefix}. */
    private static void migrateByPrefix(Path dir, String srcPrefix, String dstPrefix, String player)
            throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().startsWith(srcPrefix)).forEach(src -> {
                String dstName = dstPrefix + src.getFileName().toString().substring(srcPrefix.length());
                copyIfAbsent(src, dir.resolve(dstName), player, dir.getFileName().toString());
            });
        }
    }

    private static void copyIfAbsent(Path src, Path dst, String player, String label) {
        try {
            if (!Files.exists(src)) return;
            if (Files.exists(dst)) return; // never clobber existing real-UUID data
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst);
            GoidaAuth.LOGGER.info("Migrated {} for {}: {} -> {}",
                    label, player, src.getFileName(), dst.getFileName());
        } catch (Exception e) {
            GoidaAuth.LOGGER.warn("Could not migrate {} for {}: {}", label, player, e.getMessage());
        }
    }
}
