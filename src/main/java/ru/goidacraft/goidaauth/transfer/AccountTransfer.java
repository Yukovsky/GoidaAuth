package ru.goidacraft.goidaauth.transfer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.goidacraft.goidaauth.Config;
import ru.goidacraft.goidaauth.database.DatabaseManager;
import ru.goidacraft.goidaauth.database.UserRecord;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Transfers a player's world progress (inventory, position, stats, advancements,
 * playerdata sidecar files), scoreboard scores and LuckPerms nodes from one account to another.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code playerdata/<uuid>.dat} <b>and every sidecar</b> {@code playerdata/<uuid>.*}
 *       (e.g. {@code .cosarmor} from CosmeticArmorReworked) — caught generically so mods that
 *       drop a per-player file next to the vanilla one are covered automatically.</li>
 *   <li>{@code stats/<uuid>.json}, {@code advancements/<uuid>.json}.</li>
 *   <li>Scoreboard scores (keyed by player name).</li>
 *   <li>LuckPerms nodes (async, optional).</li>
 *   <li>Any extra world-relative per-player files configured in
 *       {@link Config#TRANSFER_EXTRA_FILES} (template with {@code {uuid}}).</li>
 * </ul>
 *
 * <p>Correctness guarantees:
 * <ul>
 *   <li>Online players are kicked first and the copy is deferred until they have fully left the
 *       player list — otherwise their disconnect-time save would overwrite the freshly copied
 *       target files.</li>
 *   <li>The target's existing files and the source's are backed up (path-mirrored) under
 *       {@code <world>/goidaauth/transfer-backups/<id>} so a transfer can be reverted via
 *       {@link #restore}.</li>
 *   <li>LuckPerms is transferred without blocking the server thread.</li>
 * </ul>
 */
public final class AccountTransfer {

    private static final Logger LOG = LoggerFactory.getLogger(AccountTransfer.class);

    private static final LevelResource PLAYERDATA   = new LevelResource("playerdata");
    private static final LevelResource STATS        = new LevelResource("stats");
    private static final LevelResource ADVANCEMENTS = new LevelResource("advancements");
    private static final LevelResource MOD_ROOT     = new LevelResource("goidaauth");

    private static final DateTimeFormatter ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int WAIT_TIMEOUT_TICKS = 100; // 5s safety cap waiting for logouts

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private AccountTransfer() {}

    /**
     * Hook fired after a successful {@code /transferaccount}. Lets other mods (e.g. GoidaDI) move
     * their own per-account data alongside the world progress. Because the registry lives inside
     * {@code AccountTransfer}, only GoidaAuth's own transfer command triggers it — a {@code
     * transferaccount} command added by a different mod will never fire these hooks.
     */
    @FunctionalInterface
    public interface PostTransferHook {
        void onTransferred(UUID fromUuid, UUID toUuid, boolean deletedSource);
    }

    private static final List<PostTransferHook> POST_TRANSFER_HOOKS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addPostTransferHook(PostTransferHook hook) {
        if (hook != null) POST_TRANSFER_HOOKS.add(hook);
    }

    public static void removePostTransferHook(PostTransferHook hook) {
        POST_TRANSFER_HOOKS.remove(hook);
    }

    public record Result(List<String> copied, List<String> skipped,
                         boolean lpTransferred, boolean scoreboardTransferred,
                         String backupId, boolean sourceDeleted) {}

    public record RestoreResult(String fromName, String toName, boolean sourceReRegistered) {}

    public record PurgeResult(String name, UUID uuid, List<String> wiped,
                              boolean lpCleared, boolean dbDeleted, String backupId) {}

    /** A single source->target file move, with a human-friendly label for the report. */
    private record FileMove(Path src, Path dst, String label) {}

    // ------------------------------------------------------------------
    // Transfer
    // ------------------------------------------------------------------

    /**
     * Kicks the involved online players, waits for them to leave, then performs the transfer.
     * Must be called on the server thread. The returned future may complete on an off-thread
     * executor (DB / LuckPerms), so callers should re-dispatch UI work via {@code server.execute}.
     */
    public static CompletableFuture<Result> execute(MinecraftServer server, DatabaseManager db,
                                                     String fromName, String toName,
                                                     UUID fromUuid, UUID toUuid,
                                                     UserRecord fromRecord, boolean deleteSource) {
        CompletableFuture<Result> future = new CompletableFuture<>();

        List<UUID> waitFor = kickIfOnline(server, fromName, fromUuid, toName, toUuid,
                "§eВаш прогресс перенесён. Войдите снова.",
                "§eВаш аккаунт получил перенесённый прогресс. Войдите снова.");

        whenPlayersOffline(server, waitFor, () -> {
            try {
                doTransfer(server, db, fromName, toName, fromUuid, toUuid, fromRecord, deleteSource)
                        .whenComplete((res, err) -> {
                            if (err != null) future.completeExceptionally(err);
                            else future.complete(res);
                        });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private static CompletableFuture<Result> doTransfer(MinecraftServer server, DatabaseManager db,
                                                        String fromName, String toName,
                                                        UUID fromUuid, UUID toUuid,
                                                        UserRecord fromRecord, boolean deleteSource) {
        Path worldDir = worldDir(server);
        String from = fromUuid.toString();
        String to   = toUuid.toString();

        // --- back up both accounts (path-mirrored) before touching anything ---
        Path backupDir = createBackupDir(server, fromName, toName);
        String backupId = backupDir.getFileName().toString();

        List<Path> fromFiles = playerFiles(server, worldDir, from);
        List<Path> toFiles   = playerFiles(server, worldDir, to);
        backupScope(worldDir, backupDir, "from", fromFiles);
        backupScope(worldDir, backupDir, "to", toFiles);

        Map<String, Integer> fromScores = readScores(server, fromName);
        Map<String, Integer> toScores   = readScores(server, toName);
        writeScores(backupDir.resolve("scoreboard-from.properties"), fromScores);
        writeScores(backupDir.resolve("scoreboard-to.properties"), toScores);

        writeManifest(backupDir, fromName, toName, fromUuid, toUuid, deleteSource, fromRecord);

        // --- copy world files source -> target ---
        List<String> copied  = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (FileMove move : transferMoves(server, worldDir, from, to)) {
            copyFile(move, copied, skipped);
        }

        // --- scoreboard (name-keyed) ---
        boolean sbTransferred = transferScores(server, fromName, toName);

        // --- optionally wipe the source so nothing is duplicated ---
        if (deleteSource) {
            for (Path f : fromFiles) deleteQuietly(f);
            clearScores(server, fromName);
        }

        // --- async tail: LuckPerms + DB row removal ---
        CompletableFuture<Boolean> lpFuture = transferLuckPerms(fromUuid, toUuid, deleteSource);
        CompletableFuture<Boolean> delFuture = deleteSource
                ? db.deleteUser(fromName)
                : CompletableFuture.completedFuture(false);

        return lpFuture.thenCombine(delFuture, (lp, deleted) -> {
            // Fire post-transfer hooks (e.g. GoidaDI moving the Discord link). Hooks re-dispatch to
            // the server thread themselves; failures here must not fail the whole transfer.
            for (PostTransferHook hook : POST_TRANSFER_HOOKS) {
                try {
                    hook.onTransferred(fromUuid, toUuid, deleteSource);
                } catch (Throwable t) {
                    LOG.error("PostTransferHook threw", t);
                }
            }
            return new Result(copied, skipped, lp, sbTransferred, backupId, deleteSource && deleted);
        });
    }

    // ------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------

    public static CompletableFuture<RestoreResult> restore(MinecraftServer server,
                                                           DatabaseManager db, String backupId) {
        CompletableFuture<RestoreResult> future = new CompletableFuture<>();

        Path backupDir = backupsRoot(server).resolve(backupId);
        if (!Files.isDirectory(backupDir)) {
            future.completeExceptionally(new IllegalArgumentException("бэкап не найден: " + backupId));
            return future;
        }

        Properties man = readManifest(backupDir);
        if (man == null) {
            future.completeExceptionally(new IllegalStateException("повреждён manifest бэкапа " + backupId));
            return future;
        }

        if ("purge".equals(man.getProperty("kind", "transfer"))) {
            restorePurgeInto(future, server, db, backupDir, man);
            return future;
        }

        String fromName = man.getProperty("fromName", "?");
        String toName   = man.getProperty("toName", "?");
        UUID fromUuid, toUuid;
        try {
            fromUuid = UUID.fromString(man.getProperty("fromUuid"));
            toUuid   = UUID.fromString(man.getProperty("toUuid"));
        } catch (Exception e) {
            future.completeExceptionally(new IllegalStateException("в manifest нет корректных UUID"));
            return future;
        }
        boolean deletedSource = Boolean.parseBoolean(man.getProperty("deletedSource", "false"));
        UserRecord srcRecord = readSourceRecord(man);
        Path worldDir = worldDir(server);

        List<UUID> waitFor = kickIfOnline(server, fromName, fromUuid, toName, toUuid,
                "§eВаши данные восстанавливаются из бэкапа. Войдите снова.",
                "§eВаши данные восстанавливаются из бэкапа. Войдите снова.");

        whenPlayersOffline(server, waitFor, () -> {
            try {
                restoreScope(server, worldDir, backupDir, "to", toUuid.toString());
                restoreScope(server, worldDir, backupDir, "from", fromUuid.toString());
                setScoresExact(server, fromName, readScoresFile(backupDir.resolve("scoreboard-from.properties")));
                setScoresExact(server, toName, readScoresFile(backupDir.resolve("scoreboard-to.properties")));

                boolean reRegister = deletedSource && srcRecord != null;
                if (reRegister) {
                    db.restoreUser(srcRecord).whenComplete((v, err) -> {
                        if (err != null) future.completeExceptionally(err);
                        else future.complete(new RestoreResult(fromName, toName, true));
                    });
                } else {
                    future.complete(new RestoreResult(fromName, toName, false));
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    public static List<String> listBackups(MinecraftServer server) {
        Path dir = backupsRoot(server);
        if (!Files.isDirectory(dir)) return List.of();
        List<String> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                  .map(p -> p.getFileName().toString())
                  .sorted()
                  .forEach(out::add);
        } catch (IOException e) {
            LOG.warn("listBackups failed", e);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Resource enumeration
    // ------------------------------------------------------------------

    private static Path worldDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT);
    }

    /** All playerdata sidecar files {@code playerdata/<uuid>.*} that currently exist. */
    private static List<Path> playerdataSidecars(MinecraftServer server, String uuid) {
        Path pd = server.getWorldPath(PLAYERDATA);
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(pd)) return out;
        String prefix = uuid + ".";
        try (Stream<Path> s = Files.list(pd)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().startsWith(prefix))
             .forEach(out::add);
        } catch (IOException e) {
            LOG.warn("Listing playerdata sidecars failed for {}", uuid, e);
        }
        return out;
    }

    /** Every per-player file for a UUID: playerdata sidecars + stats + advancements + configured extras. */
    private static List<Path> playerFiles(MinecraftServer server, Path worldDir, String uuid) {
        List<Path> files = new ArrayList<>(playerdataSidecars(server, uuid));
        files.add(server.getWorldPath(STATS).resolve(uuid + ".json"));
        files.add(server.getWorldPath(ADVANCEMENTS).resolve(uuid + ".json"));
        for (String tmpl : Config.TRANSFER_EXTRA_FILES.get()) {
            Path extra = resolveExtra(worldDir, tmpl, uuid);
            if (extra != null) files.add(extra);
        }
        return files;
    }

    /** Source->target moves applied during a transfer (target UUID in the destination names). */
    private static List<FileMove> transferMoves(MinecraftServer server, Path worldDir, String from, String to) {
        List<FileMove> moves = new ArrayList<>();
        Path pd = server.getWorldPath(PLAYERDATA);
        String prefix = from + ".";
        for (Path src : playerdataSidecars(server, from)) {
            String ext = src.getFileName().toString().substring(prefix.length());
            if (ext.equals("dat_old")) continue; // regenerated by the game on next save
            moves.add(new FileMove(src, pd.resolve(to + "." + ext), "playerdata/*." + ext));
        }
        moves.add(new FileMove(server.getWorldPath(STATS).resolve(from + ".json"),
                               server.getWorldPath(STATS).resolve(to + ".json"), "stats"));
        moves.add(new FileMove(server.getWorldPath(ADVANCEMENTS).resolve(from + ".json"),
                               server.getWorldPath(ADVANCEMENTS).resolve(to + ".json"), "advancements"));
        for (String tmpl : Config.TRANSFER_EXTRA_FILES.get()) {
            Path src = resolveExtra(worldDir, tmpl, from);
            Path dst = resolveExtra(worldDir, tmpl, to);
            if (src != null && dst != null) {
                moves.add(new FileMove(src, dst, tmpl.replace("{uuid}", "*")));
            }
        }
        return moves;
    }

    /** Resolves an extra-file template against the world dir; rejects paths escaping the world dir. */
    private static Path resolveExtra(Path worldDir, String template, String uuid) {
        try {
            Path resolved = worldDir.resolve(template.replace("{uuid}", uuid)).normalize();
            if (!resolved.startsWith(worldDir.normalize())) {
                LOG.warn("Ignoring transfer extra path outside world dir: {}", template);
                return null;
            }
            return resolved;
        } catch (Exception e) {
            LOG.warn("Bad transfer extra path template: {}", template, e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Player kick / wait helpers
    // ------------------------------------------------------------------

    private static List<UUID> kickIfOnline(MinecraftServer server, String fromName, UUID fromUuid,
                                           String toName, UUID toUuid, String fromMsg, String toMsg) {
        List<UUID> waitFor = new ArrayList<>();
        ServerPlayer fromOnline = onlinePlayer(server, fromUuid, fromName);
        ServerPlayer toOnline   = onlinePlayer(server, toUuid, toName);
        if (fromOnline != null) {
            fromOnline.connection.disconnect(Component.literal(fromMsg));
            waitFor.add(fromOnline.getUUID());
        }
        if (toOnline != null) {
            toOnline.connection.disconnect(Component.literal(toMsg));
            waitFor.add(toOnline.getUUID());
        }
        return waitFor;
    }

    private static ServerPlayer onlinePlayer(MinecraftServer server, UUID uuid, String name) {
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        return p != null ? p : server.getPlayerList().getPlayerByName(name);
    }

    /**
     * Runs {@code action} on the server thread once every listed player has left the player list
     * (their disconnect-time save has flushed), or after a short timeout. If nobody is online the
     * action runs immediately.
     */
    private static void whenPlayersOffline(MinecraftServer server, List<UUID> uuids, Runnable action) {
        if (uuids.isEmpty() || uuids.stream().allMatch(u -> server.getPlayerList().getPlayer(u) == null)) {
            action.run();
            return;
        }
        new OfflineWaiter(server, uuids, action).register();
    }

    /**
     * Public so NeoForge's ASM-generated event handler can access it. One-shot: it unregisters
     * itself from the event bus as soon as the wait completes.
     */
    public static final class OfflineWaiter {
        private final MinecraftServer server;
        private final List<UUID> uuids;
        private final Runnable action;
        private int ticksLeft = WAIT_TIMEOUT_TICKS;
        private boolean done;

        OfflineWaiter(MinecraftServer server, List<UUID> uuids, Runnable action) {
            this.server = server;
            this.uuids = uuids;
            this.action = action;
        }

        void register() {
            NeoForge.EVENT_BUS.register(this);
        }

        @SubscribeEvent
        public void onTick(ServerTickEvent.Post event) {
            if (done) return;
            boolean allGone = uuids.stream().allMatch(u -> server.getPlayerList().getPlayer(u) == null);
            if (allGone || --ticksLeft <= 0) {
                done = true;
                NeoForge.EVENT_BUS.unregister(this);
                try {
                    action.run();
                } catch (Exception e) {
                    LOG.error("Account transfer continuation failed", e);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // File helpers
    // ------------------------------------------------------------------

    private static void copyFile(FileMove move, List<String> copied, List<String> skipped) {
        if (!Files.exists(move.src())) {
            skipped.add(move.label());
            return;
        }
        try {
            Files.createDirectories(move.dst().getParent());
            Files.copy(move.src(), move.dst(), StandardCopyOption.REPLACE_EXISTING);
            copied.add(move.label());
        } catch (IOException e) {
            LOG.error("Transfer copy failed: {} -> {}", move.src(), move.dst(), e);
            skipped.add(move.label() + " (I/O error)");
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            LOG.warn("Could not delete {}", p, e);
        }
    }

    private static Path backupsRoot(MinecraftServer server) {
        return server.getWorldPath(MOD_ROOT).resolve("transfer-backups");
    }

    private static Path createBackupDir(MinecraftServer server, String fromName, String toName) {
        String base = LocalDateTime.now().format(ID_FMT) + "_" + sanitize(fromName) + "_to_" + sanitize(toName);
        Path root = backupsRoot(server);
        Path dir = root.resolve(base);
        int n = 1;
        while (Files.exists(dir)) {
            dir = root.resolve(base + "-" + n++);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.error("Could not create backup dir {}", dir, e);
        }
        return dir;
    }

    private static String sanitize(String name) {
        return name == null ? "unknown" : name.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    /** Backs up each world-relative file under {@code <backupDir>/<scope>/<relative-path>}. */
    private static void backupScope(Path worldDir, Path backupDir, String scope, List<Path> files) {
        Path scopeRoot = backupDir.resolve(scope);
        for (Path f : files) {
            if (!Files.exists(f)) continue;
            Path rel;
            try {
                rel = worldDir.relativize(f);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (rel.startsWith("..")) continue; // outside world dir — skip
            backupCopy(f, scopeRoot.resolve(rel));
        }
    }

    private static void backupCopy(Path src, Path dst) {
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Backup copy failed {} -> {}", src, dst, e);
        }
    }

    /**
     * Restores a backed-up scope: first removes the account's current per-player files (so files
     * created by the transfer disappear), then copies the backup tree back to the world dir.
     */
    private static void restoreScope(MinecraftServer server, Path worldDir,
                                     Path backupDir, String scope, String uuid) {
        for (Path f : playerFiles(server, worldDir, uuid)) {
            deleteQuietly(f);
        }
        copyTree(backupDir.resolve(scope), worldDir);
    }

    /** Copies every regular file under {@code scopeRoot} back to {@code baseDir}, preserving structure. */
    private static void copyTree(Path scopeRoot, Path baseDir) {
        if (!Files.isDirectory(scopeRoot)) return;
        try (Stream<Path> walk = Files.walk(scopeRoot)) {
            walk.filter(Files::isRegularFile).forEach(src -> {
                Path rel = scopeRoot.relativize(src);
                Path dst = baseDir.resolve(rel);
                try {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOG.warn("Restore copy failed for {}", dst, e);
                }
            });
        } catch (IOException e) {
            LOG.warn("Restore walk failed for {}", scopeRoot, e);
        }
    }

    // ------------------------------------------------------------------
    // Manifest
    // ------------------------------------------------------------------

    private static void writeManifest(Path dir, String fromName, String toName,
                                      UUID fromUuid, UUID toUuid, boolean deleteSource, UserRecord src) {
        Properties p = new Properties();
        p.setProperty("kind", "transfer");
        p.setProperty("timestamp", Instant.now().toString());
        p.setProperty("fromName", fromName);
        p.setProperty("toName", toName);
        p.setProperty("fromUuid", fromUuid.toString());
        p.setProperty("toUuid", toUuid.toString());
        p.setProperty("deletedSource", Boolean.toString(deleteSource));
        if (src != null) {
            p.setProperty("src.uuid", src.uuid().toString());
            p.setProperty("src.username", src.username());
            p.setProperty("src.passwordHash", src.passwordHash());
            p.setProperty("src.premium", Boolean.toString(src.premium()));
            if (src.lastIp() != null) p.setProperty("src.lastIp", src.lastIp());
            if (src.lastSeen() != null) p.setProperty("src.lastSeen", Long.toString(src.lastSeen().toEpochMilli()));
            if (src.registeredAt() != null) p.setProperty("src.registeredAt", Long.toString(src.registeredAt().toEpochMilli()));
        }
        try (Writer w = Files.newBufferedWriter(dir.resolve("manifest.properties"))) {
            p.store(w, "GoidaAuth account transfer backup");
        } catch (IOException e) {
            LOG.warn("Could not write transfer manifest", e);
        }
    }

    private static Properties readManifest(Path dir) {
        Path file = dir.resolve("manifest.properties");
        if (!Files.exists(file)) return null;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
            return p;
        } catch (IOException e) {
            LOG.warn("Could not read transfer manifest", e);
            return null;
        }
    }

    private static UserRecord readSourceRecord(Properties man) {
        if (!man.containsKey("src.username") || !man.containsKey("src.uuid")) return null;
        try {
            Instant lastSeen = man.containsKey("src.lastSeen")
                    ? Instant.ofEpochMilli(Long.parseLong(man.getProperty("src.lastSeen"))) : null;
            Instant registeredAt = man.containsKey("src.registeredAt")
                    ? Instant.ofEpochMilli(Long.parseLong(man.getProperty("src.registeredAt"))) : Instant.now();
            return new UserRecord(
                    UUID.fromString(man.getProperty("src.uuid")),
                    man.getProperty("src.username"),
                    man.getProperty("src.passwordHash"),
                    Boolean.parseBoolean(man.getProperty("src.premium", "false")),
                    man.getProperty("src.lastIp"),
                    lastSeen,
                    registeredAt);
        } catch (Exception e) {
            LOG.warn("Could not parse source record from manifest", e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Scoreboard (name-keyed) helpers
    // ------------------------------------------------------------------

    private static Map<String, Integer> readScores(MinecraftServer server, String name) {
        Map<String, Integer> out = new LinkedHashMap<>();
        guarded(() -> {
            Scoreboard sb = server.getScoreboard();
            ScoreHolder holder = ScoreHolder.forNameOnly(name);
            for (Objective obj : sb.getObjectives()) {
                ReadOnlyScoreInfo info = sb.getPlayerScoreInfo(holder, obj);
                if (info != null) out.put(obj.getName(), info.value());
            }
            return null;
        }, "readScores");
        return out;
    }

    private static boolean transferScores(MinecraftServer server, String fromName, String toName) {
        Boolean any = guarded(() -> {
            Scoreboard sb = server.getScoreboard();
            ScoreHolder fromHolder = ScoreHolder.forNameOnly(fromName);
            ScoreHolder toHolder = ScoreHolder.forNameOnly(toName);
            boolean copied = false;
            for (Objective obj : sb.getObjectives()) {
                ReadOnlyScoreInfo info = sb.getPlayerScoreInfo(fromHolder, obj);
                if (info != null) {
                    sb.getOrCreatePlayerScore(toHolder, obj).set(info.value());
                    copied = true;
                }
            }
            return copied;
        }, "transferScores");
        return any != null && any;
    }

    private static void clearScores(MinecraftServer server, String name) {
        guarded(() -> {
            Scoreboard sb = server.getScoreboard();
            ScoreHolder holder = ScoreHolder.forNameOnly(name);
            for (Objective obj : sb.getObjectives()) {
                sb.resetSinglePlayerScore(holder, obj);
            }
            return null;
        }, "clearScores");
    }

    private static void setScoresExact(MinecraftServer server, String name, Map<String, Integer> scores) {
        guarded(() -> {
            Scoreboard sb = server.getScoreboard();
            ScoreHolder holder = ScoreHolder.forNameOnly(name);
            for (Objective obj : sb.getObjectives()) {
                sb.resetSinglePlayerScore(holder, obj);
            }
            for (Map.Entry<String, Integer> e : scores.entrySet()) {
                Objective obj = sb.getObjective(e.getKey());
                if (obj != null) sb.getOrCreatePlayerScore(holder, obj).set(e.getValue());
            }
            return null;
        }, "setScoresExact");
    }

    private static void writeScores(Path file, Map<String, Integer> scores) {
        Properties p = new Properties();
        scores.forEach((k, v) -> p.setProperty(k, Integer.toString(v)));
        try (Writer w = Files.newBufferedWriter(file)) {
            p.store(w, "scoreboard scores");
        } catch (IOException e) {
            LOG.warn("Could not write scoreboard backup {}", file, e);
        }
    }

    private static Map<String, Integer> readScoresFile(Path file) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!Files.exists(file)) return out;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
            for (String key : p.stringPropertyNames()) {
                try {
                    out.put(key, Integer.parseInt(p.getProperty(key)));
                } catch (NumberFormatException ignored) {
                    // skip malformed entries
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not read scoreboard backup {}", file, e);
        }
        return out;
    }

    private static <T> T guarded(Supplier<T> body, String label) {
        try {
            return body.get();
        } catch (Throwable t) {
            LOG.warn("Scoreboard op '{}' failed: {}", label, t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // LuckPerms (fully async, optional dependency)
    // ------------------------------------------------------------------

    /**
     * Clones all LuckPerms nodes from {@code from} to {@code to}, optionally clearing the source.
     * Returns a future completing with {@code false} when LuckPerms is absent or the call fails.
     * Never blocks the calling (server) thread.
     */
    private static CompletableFuture<Boolean> transferLuckPerms(UUID from, UUID to, boolean clearSource) {
        try {
            var lp = net.luckperms.api.LuckPermsProvider.get();
            var um = lp.getUserManager();
            return um.loadUser(from).thenCompose(fromUser ->
                    um.loadUser(to).thenCompose(toUser -> {
                        if (fromUser == null || toUser == null) {
                            return CompletableFuture.completedFuture(Boolean.FALSE);
                        }
                        var nodes = List.copyOf(fromUser.getNodes());
                        toUser.data().clear();
                        nodes.forEach(n -> toUser.data().add(n));
                        CompletableFuture<Void> save = um.saveUser(toUser);
                        if (clearSource) {
                            fromUser.data().clear();
                            save = save.thenCompose(v -> um.saveUser(fromUser));
                        }
                        return save.thenApply(v -> Boolean.TRUE);
                    }))
                    .exceptionally(e -> {
                        LOG.debug("LuckPerms transfer failed: {}", e.getMessage());
                        return Boolean.FALSE;
                    });
        } catch (NoClassDefFoundError | Exception e) {
            LOG.debug("LuckPerms transfer skipped: {}", e.getMessage());
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
    }

    /** Clears all LuckPerms nodes of a single user (async). */
    private static CompletableFuture<Boolean> clearLuckPerms(UUID uuid) {
        try {
            var lp = net.luckperms.api.LuckPermsProvider.get();
            var um = lp.getUserManager();
            return um.loadUser(uuid).thenCompose(user -> {
                if (user == null) return CompletableFuture.completedFuture(Boolean.FALSE);
                user.data().clear();
                return um.saveUser(user).thenApply(v -> Boolean.TRUE);
            }).exceptionally(e -> {
                LOG.debug("LuckPerms clear failed: {}", e.getMessage());
                return Boolean.FALSE;
            });
        } catch (NoClassDefFoundError | Exception e) {
            LOG.debug("LuckPerms clear skipped: {}", e.getMessage());
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
    }

    // ------------------------------------------------------------------
    // Purge — wipe an account so it looks like it never played
    // ------------------------------------------------------------------

    /**
     * Fully wipes a player: world files + scoreboard + LuckPerms + GoidaAuth DB row, plus the
     * server-side traces the mod can reach (HWID logs, name caches, op/whitelist entries). Bans
     * and Bukkit plugin data are intentionally left untouched. Everything is backed up and can be
     * reverted via {@link #restore}. Must be called on the server thread.
     */
    public static CompletableFuture<PurgeResult> purge(MinecraftServer server, DatabaseManager db,
                                                       String name, UserRecord record, UUID uuid) {
        CompletableFuture<PurgeResult> future = new CompletableFuture<>();

        List<UUID> waitFor = new ArrayList<>();
        ServerPlayer online = onlinePlayer(server, uuid, name);
        if (online != null) {
            online.connection.disconnect(Component.literal("§cВаш аккаунт удалён администратором."));
            waitFor.add(online.getUUID());
        }

        whenPlayersOffline(server, waitFor, () -> {
            try {
                doPurge(server, db, name, record, uuid).whenComplete((res, err) -> {
                    if (err != null) future.completeExceptionally(err);
                    else future.complete(res);
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private static CompletableFuture<PurgeResult> doPurge(MinecraftServer server, DatabaseManager db,
                                                          String name, UserRecord record, UUID uuid) {
        Path worldDir = worldDir(server);
        Path serverRoot = Path.of("").toAbsolutePath().normalize();
        String u = uuid.toString();

        Path backupDir = createBackupDir(server, name, "PURGE");
        String backupId = backupDir.getFileName().toString();

        // --- backup ---
        List<Path> files = playerFiles(server, worldDir, u);
        backupScope(worldDir, backupDir, "purge", files);
        writeScores(backupDir.resolve("scoreboard-purge.properties"), readScores(server, name));
        writePurgeManifest(backupDir, name, uuid, record);

        // --- wipe ---
        List<String> wiped = new ArrayList<>();
        for (Path f : files) {
            if (Files.exists(f)) {
                try {
                    wiped.add(worldDir.relativize(f).toString().replace('\\', '/'));
                } catch (IllegalArgumentException ignored) {
                    wiped.add(f.getFileName().toString());
                }
            }
            deleteQuietly(f);
        }
        clearScores(server, name);
        wiped.addAll(wipeAuxiliary(serverRoot, backupDir, uuid, name));

        CompletableFuture<Boolean> lpFuture = clearLuckPerms(uuid);
        CompletableFuture<Boolean> delFuture = db.deleteUser(name);
        return lpFuture.thenCombine(delFuture, (lp, deleted) ->
                new PurgeResult(name, uuid, wiped, lp, deleted, backupId));
    }

    private static void restorePurgeInto(CompletableFuture<RestoreResult> future, MinecraftServer server,
                                         DatabaseManager db, Path backupDir, Properties man) {
        String name = man.getProperty("name", "?");
        UUID uuid;
        try {
            uuid = UUID.fromString(man.getProperty("uuid"));
        } catch (Exception e) {
            future.completeExceptionally(new IllegalStateException("в manifest purge нет корректного UUID"));
            return;
        }
        UserRecord record = readSourceRecord(man);
        Path worldDir = worldDir(server);
        Path serverRoot = Path.of("").toAbsolutePath().normalize();

        List<UUID> waitFor = new ArrayList<>();
        ServerPlayer online = onlinePlayer(server, uuid, name);
        if (online != null) {
            online.connection.disconnect(Component.literal("§eВаши данные восстанавливаются. Войдите снова."));
            waitFor.add(online.getUUID());
        }

        whenPlayersOffline(server, waitFor, () -> {
            try {
                restoreScope(server, worldDir, backupDir, "purge", uuid.toString());
                copyTree(backupDir.resolve("serverroot"), serverRoot);
                setScoresExact(server, name, readScoresFile(backupDir.resolve("scoreboard-purge.properties")));
                if (record != null) {
                    db.restoreUser(record).whenComplete((v, err) -> {
                        if (err != null) future.completeExceptionally(err);
                        else future.complete(new RestoreResult(name, name, true));
                    });
                } else {
                    future.complete(new RestoreResult(name, name, false));
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
    }

    private static void writePurgeManifest(Path dir, String name, UUID uuid, UserRecord src) {
        Properties p = new Properties();
        p.setProperty("kind", "purge");
        p.setProperty("timestamp", Instant.now().toString());
        p.setProperty("name", name);
        p.setProperty("uuid", uuid.toString());
        if (src != null) {
            p.setProperty("src.uuid", src.uuid().toString());
            p.setProperty("src.username", src.username());
            p.setProperty("src.passwordHash", src.passwordHash());
            p.setProperty("src.premium", Boolean.toString(src.premium()));
            if (src.lastIp() != null) p.setProperty("src.lastIp", src.lastIp());
            if (src.lastSeen() != null) p.setProperty("src.lastSeen", Long.toString(src.lastSeen().toEpochMilli()));
            if (src.registeredAt() != null) p.setProperty("src.registeredAt", Long.toString(src.registeredAt().toEpochMilli()));
        }
        try (Writer w = Files.newBufferedWriter(dir.resolve("manifest.properties"))) {
            p.store(w, "GoidaAuth account purge backup");
        } catch (IOException e) {
            LOG.warn("Could not write purge manifest", e);
        }
    }

    // ---- server-side trace wiping (HWID, name caches, op/whitelist) ----

    /** Removes server-side traces the mod can reach; backs up each file before editing. */
    private static List<String> wipeAuxiliary(Path serverRoot, Path backupDir, UUID uuid, String name) {
        List<String> wiped = new ArrayList<>();
        String uuidStr = uuid.toString();

        // HWID anti-alt logs: config/hwid/<hash>.txt, one bare UUID per line
        Path hwidDir = serverRoot.resolve("config").resolve("hwid");
        if (Files.isDirectory(hwidDir)) {
            try (Stream<Path> s = Files.list(hwidDir)) {
                s.filter(Files::isRegularFile)
                 .filter(p -> p.getFileName().toString().endsWith(".txt"))
                 .forEach(p -> {
                     if (removeUuidLine(serverRoot, backupDir, p, uuidStr)) {
                         wiped.add("hwid/" + p.getFileName());
                     }
                 });
            } catch (IOException e) {
                LOG.warn("HWID wipe listing failed", e);
            }
        }

        if (removeFromJsonArray(serverRoot, backupDir, serverRoot.resolve("usercache.json"), uuidStr, name)) {
            wiped.add("usercache.json");
        }
        if (removeFromJsonObject(serverRoot, backupDir, serverRoot.resolve("usernamecache.json"), uuidStr)) {
            wiped.add("usernamecache.json");
        }
        if (removeFromJsonArray(serverRoot, backupDir, serverRoot.resolve("ops.json"), uuidStr, name)) {
            wiped.add("ops.json");
        }
        if (removeFromJsonArray(serverRoot, backupDir, serverRoot.resolve("whitelist.json"), uuidStr, name)) {
            wiped.add("whitelist.json");
        }
        return wiped;
    }

    private static boolean removeUuidLine(Path serverRoot, Path backupDir, Path file, String uuidStr) {
        try {
            List<String> lines = Files.readAllLines(file);
            List<String> kept = new ArrayList<>();
            boolean changed = false;
            for (String line : lines) {
                if (line.trim().equalsIgnoreCase(uuidStr)) changed = true;
                else kept.add(line);
            }
            if (!changed) return false;
            backupServerFile(serverRoot, backupDir, file);
            if (kept.isEmpty()) Files.deleteIfExists(file);
            else Files.write(file, kept);
            return true;
        } catch (IOException e) {
            LOG.warn("HWID line removal failed for {}", file, e);
            return false;
        }
    }

    private static boolean removeFromJsonArray(Path serverRoot, Path backupDir, Path file,
                                               String uuidStr, String name) {
        if (!Files.exists(file)) return false;
        try {
            JsonElement root;
            try (Reader r = Files.newBufferedReader(file)) {
                root = JsonParser.parseReader(r);
            }
            if (root == null || !root.isJsonArray()) return false;
            JsonArray kept = new JsonArray();
            boolean changed = false;
            for (JsonElement el : root.getAsJsonArray()) {
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    String u = o.has("uuid") ? o.get("uuid").getAsString() : null;
                    String n = o.has("name") ? o.get("name").getAsString() : null;
                    if ((u != null && u.equalsIgnoreCase(uuidStr))
                            || (name != null && n != null && n.equalsIgnoreCase(name))) {
                        changed = true;
                        continue;
                    }
                }
                kept.add(el);
            }
            if (!changed) return false;
            backupServerFile(serverRoot, backupDir, file);
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(kept, w);
            }
            return true;
        } catch (Exception e) {
            LOG.warn("JSON array wipe failed for {}", file, e);
            return false;
        }
    }

    private static boolean removeFromJsonObject(Path serverRoot, Path backupDir, Path file, String uuidStr) {
        if (!Files.exists(file)) return false;
        try {
            JsonElement root;
            try (Reader r = Files.newBufferedReader(file)) {
                root = JsonParser.parseReader(r);
            }
            if (root == null || !root.isJsonObject()) return false;
            JsonObject obj = root.getAsJsonObject();
            String matchKey = null;
            for (String key : obj.keySet()) {
                if (key.equalsIgnoreCase(uuidStr)) {
                    matchKey = key;
                    break;
                }
            }
            if (matchKey == null) return false;
            backupServerFile(serverRoot, backupDir, file);
            obj.remove(matchKey);
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(obj, w);
            }
            return true;
        } catch (Exception e) {
            LOG.warn("JSON object wipe failed for {}", file, e);
            return false;
        }
    }

    private static void backupServerFile(Path serverRoot, Path backupDir, Path file) {
        try {
            Path abs = file.toAbsolutePath().normalize();
            Path rel = serverRoot.relativize(abs);
            if (rel.startsWith("..")) return; // outside server root — skip
            backupCopy(abs, backupDir.resolve("serverroot").resolve(rel));
        } catch (Exception e) {
            LOG.warn("Aux backup failed for {}", file, e);
        }
    }
}
