package ru.goidacraft.goidaauth.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.goidacraft.goidaauth.Config;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GoidaAuth DB access. Supports two backends, selected by {@code Config.DB_MODE}:
 *   - "h2"    : embedded file DB under world/goidaauth (single-process, legacy/dev default).
 *   - "mysql" : shared MySQL/MariaDB so the Velocity proxy plugin and backend share one `users`
 *               table (required for premium auto-login behind a proxy).
 *
 * Both backends run through a HikariCP {@link DataSource}; every call borrows a connection for the
 * duration of the statement, so the access pattern is identical and thread-safe across the pool.
 *
 * <p>The pre-existing SQL is unchanged except where MySQL needs idempotent DDL (it does not
 * support {@code CREATE INDEX IF NOT EXISTS} / {@code ADD COLUMN IF NOT EXISTS}), which is
 * handled here via information_schema probes.
 */
public final class DatabaseManager {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseManager.class);
    private static final LevelResource ROOT = new LevelResource("goidaauth");

    private final ExecutorService io = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2), r -> {
                var t = new Thread(r, "GoidaAuth-DB");
                t.setDaemon(true);
                return t;
            });

    private volatile HikariDataSource dataSource;
    private volatile boolean mysql;

    public synchronized void start(MinecraftServer server) throws Exception {
        String mode = Config.DB_MODE.get().trim().toLowerCase(Locale.ROOT);
        this.mysql = mode.equals("mysql") || mode.equals("mariadb");

        if (mysql) {
            startMysql();
        } else {
            startH2(server);
        }

        initSchema();

        if (mysql) {
            maybeImportFromH2(server);
        }
        purgeOldAttempts(Config.ATTEMPT_RETENTION_DAYS.get());
        LOG.info("GoidaAuth DB ready (mode={})", mysql ? "mysql" : "h2");
    }

    private void startH2(MinecraftServer server) throws Exception {
        Path dir = server.getWorldPath(ROOT);
        Files.createDirectories(dir);
        Path db = dir.resolve("auth_data");
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("H2 driver missing — jarJar packaging broken", e);
        }
        String url = "jdbc:h2:file:" + db.toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("GoidaAuth-H2");
        hc.setJdbcUrl(url);
        hc.setUsername("sa");
        hc.setPassword("");
        hc.setMaximumPoolSize(1); // embedded H2 file: keep it single-writer
        this.dataSource = new HikariDataSource(hc);
    }

    private void startMysql() {
        // MariaDB Connector/J driver against any MySQL/MariaDB server. The jdbc:mariadb scheme is
        // handled by org.mariadb.jdbc.Driver; sslMode replaces Connector/J's useSSL flag.
        String sslMode = Config.DB_USE_SSL.get() ? "REQUIRED" : "DISABLE";
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("GoidaAuth-MySQL");
        hc.setDriverClassName("org.mariadb.jdbc.Driver");
        hc.setJdbcUrl("jdbc:mariadb://" + Config.DB_HOST.get() + ":" + Config.DB_PORT.get()
                + "/" + Config.DB_NAME.get()
                + "?sslMode=" + sslMode);
        hc.setUsername(Config.DB_USER.get());
        hc.setPassword(Config.DB_PASSWORD.get());
        hc.setMaximumPoolSize(Config.DB_POOL_SIZE.get());
        // Remote DB hosts close idle connections (short wait_timeout). Keep pooled connections
        // fresh so they never go stale ("No operations allowed after connection closed").
        hc.setMinimumIdle(1);
        hc.setKeepaliveTime(30_000);
        hc.setMaxLifetime(180_000);
        hc.setIdleTimeout(60_000);
        hc.setValidationTimeout(3_000);
        hc.setConnectionTestQuery("SELECT 1");
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "128");
        this.dataSource = new HikariDataSource(hc);
    }

    private void initSchema() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS users (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(32) NOT NULL,
                    username_lower VARCHAR(32) NOT NULL UNIQUE,
                    password_hash VARCHAR(512) NOT NULL,
                    premium BOOLEAN NOT NULL DEFAULT FALSE,
                    last_ip VARCHAR(64),
                    last_seen TIMESTAMP NULL DEFAULT NULL,
                    registered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
            """);
            ensureColumn(c, "users", "hwid", "VARCHAR(64)");
            ensureColumn(c, "users", "rules_accepted", "BOOLEAN NOT NULL DEFAULT FALSE");
            ensureIndex(c, "users", "idx_users_name_lower", "username_lower");
            ensureIndex(c, "users", "idx_users_hwid", "hwid");

            // Failed attempts live in their own table: they describe the person who tried, not the
            // account they aimed at, so they must never touch the account's own columns.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS login_attempts (
                    username VARCHAR(32) NOT NULL,
                    username_lower VARCHAR(32) NOT NULL,
                    ip VARCHAR(64),
                    uuid VARCHAR(36),
                    reason VARCHAR(32) NOT NULL,
                    attempted_at TIMESTAMP NOT NULL
                )
            """);
            ensureIndex(c, "login_attempts", "idx_attempts_name", "username_lower");
            ensureIndex(c, "login_attempts", "idx_attempts_ip", "ip");
            ensureIndex(c, "login_attempts", "idx_attempts_at", "attempted_at");
        }
    }

    /** Idempotent ADD COLUMN that works on H2, MySQL 8 and MariaDB regardless of identifier case. */
    private void ensureColumn(Connection c, String table, String column, String ddlType) throws SQLException {
        if (columnExists(c, table, column)) return;
        try (Statement st = c.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddlType);
        } catch (SQLException e) {
            // Defensive: if the column already exists (engine reported it under a different case),
            // don't fail startup — just confirm and move on.
            if (columnExists(c, table, column)) {
                LOG.debug("ensureColumn {} already present: {}", column, e.getMessage());
            } else {
                throw e;
            }
        }
    }

    /** Reliable, case-insensitive column check: asks the engine for the table's real columns. */
    private boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                if (column.equalsIgnoreCase(md.getColumnName(i))) return true;
            }
        }
        return false;
    }

    /** Idempotent CREATE INDEX that works on H2, MySQL 8 and MariaDB. */
    private void ensureIndex(Connection c, String table, String indexName, String columns) throws SQLException {
        try (ResultSet rs = c.getMetaData().getIndexInfo(c.getCatalog(), null, table, false, false)) {
            while (rs.next()) {
                if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) return;
            }
        }
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE INDEX " + indexName + " ON " + table + "(" + columns + ")");
        } catch (SQLException e) {
            // A pre-existing unique constraint on the same column can make a duplicate index
            // redundant on some engines — log and continue rather than failing startup.
            LOG.debug("ensureIndex {} skipped: {}", indexName, e.getMessage());
        }
    }

    /**
     * One-time H2 -> MySQL migration (Component "Миграция"). Runs only when:
     *   mode=mysql, the MySQL `users` table is empty, and an old H2 file exists.
     * Existing username_lower rows are skipped, so re-running is safe (idempotent).
     */
    private void maybeImportFromH2(MinecraftServer server) {
        try {
            Path h2File = server.getWorldPath(ROOT).resolve("auth_data.mv.db");
            if (!Files.exists(h2File)) return;
            if (countUsers() > 0) {
                LOG.info("MySQL users table not empty — skipping H2 auto-import.");
                return;
            }

            // Safety net: copy the H2 file aside before the one-time migration touches it.
            try {
                Path backup = h2File.resolveSibling("auth_data.mv.db.bak-" + System.currentTimeMillis());
                Files.copy(h2File, backup);
                LOG.info("Backed up H2 database to {} before migration.", backup.getFileName());
            } catch (Exception e) {
                LOG.warn("Could not back up H2 file before migration: {} — continuing anyway.", e.getMessage());
            }

            Class.forName("org.h2.Driver");
            String h2Path = server.getWorldPath(ROOT).resolve("auth_data")
                    .toAbsolutePath().toString().replace('\\', '/');
            String url = "jdbc:h2:file:" + h2Path + ";MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

            int imported = 0;
            try (Connection h2 = DriverManager.getConnection(url, "sa", "");
                 PreparedStatement sel = h2.prepareStatement(
                         "SELECT uuid, username, username_lower, password_hash, premium, " +
                         "last_ip, last_seen, registered_at, hwid FROM users");
                 ResultSet rs = sel.executeQuery();
                 Connection my = dataSource.getConnection()) {

                my.setAutoCommit(false);
                try (PreparedStatement ins = my.prepareStatement(
                        "INSERT INTO users (uuid, username, username_lower, password_hash, premium, " +
                        "last_ip, last_seen, registered_at, hwid) VALUES (?,?,?,?,?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE uuid = uuid")) { // skip existing username_lower
                    while (rs.next()) {
                        ins.setString(1, rs.getString("uuid"));
                        ins.setString(2, rs.getString("username"));
                        ins.setString(3, rs.getString("username_lower"));
                        ins.setString(4, rs.getString("password_hash"));
                        ins.setBoolean(5, rs.getBoolean("premium"));
                        ins.setString(6, rs.getString("last_ip"));
                        ins.setTimestamp(7, rs.getTimestamp("last_seen"));
                        Timestamp ra = rs.getTimestamp("registered_at");
                        ins.setTimestamp(8, ra != null ? ra : Timestamp.from(Instant.now()));
                        ins.setString(9, rs.getString("hwid"));
                        ins.addBatch();
                        imported++;
                    }
                    ins.executeBatch();
                }
                my.commit();
            }
            LOG.info("H2 -> MySQL auto-import complete: {} users imported.", imported);
        } catch (Exception e) {
            LOG.error("H2 -> MySQL auto-import failed (continuing with MySQL as-is): {}", e.getMessage(), e);
        }
    }

    private long countUsers() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            return -1;
        }
    }

    public synchronized void shutdown() {
        if (dataSource != null) dataSource.close();
        io.shutdown();
    }

    public CompletableFuture<Optional<UserRecord>> findByName(String name) {
        return CompletableFuture.supplyAsync(() -> findByNameSync(name), io);
    }

    public Optional<UserRecord> findByNameSync(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, username, password_hash, premium, last_ip, last_seen, registered_at, " +
                        "rules_accepted FROM users WHERE username_lower = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            // Deliberately propagated: "the database is unreachable" is not "this account does not
            // exist". Swallowing it into an empty Optional made a DB outage look like a brand-new
            // player, which invites /register on an existing account. Callers fail the login instead.
            LOG.error("findByName failed for {}", name, e);
            throw new RuntimeException("auth database unavailable", e);
        }
    }

    public CompletableFuture<Void> register(UUID uuid, String username, String passwordHash,
                                            boolean premium, String ip, boolean rulesAccepted) {
        return CompletableFuture.runAsync(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (uuid, username, username_lower, password_hash, premium, " +
                            "last_ip, last_seen, rules_accepted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, username);
                ps.setString(3, username.toLowerCase(Locale.ROOT));
                ps.setString(4, passwordHash);
                ps.setBoolean(5, premium);
                ps.setString(6, normalizeIp(ip));
                ps.setTimestamp(7, Timestamp.from(Instant.now()));
                ps.setBoolean(8, rulesAccepted);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, io);
    }

    /** Persists the rules acceptance so a returning player is never asked twice. */
    public CompletableFuture<Void> setRulesAccepted(String username) {
        return CompletableFuture.runAsync(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET rules_accepted = TRUE WHERE username_lower = ?")) {
                ps.setString(1, username.toLowerCase(Locale.ROOT));
                ps.executeUpdate();
            } catch (SQLException e) {
                LOG.warn("setRulesAccepted failed for {}", username, e);
            }
        }, io);
    }

    public CompletableFuture<Void> updateLastSeen(String username, String ip) {
        return CompletableFuture.runAsync(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET last_ip = ?, last_seen = ? WHERE username_lower = ?")) {
                ps.setString(1, normalizeIp(ip));
                ps.setTimestamp(2, Timestamp.from(Instant.now()));
                ps.setString(3, username.toLowerCase(Locale.ROOT));
                ps.executeUpdate();
            } catch (SQLException e) {
                LOG.warn("updateLastSeen failed", e);
            }
        }, io);
    }

    public CompletableFuture<List<String>> findNamesByIp(String ip) {
        return CompletableFuture.supplyAsync(() -> findNamesByIpSync(ip), io);
    }

    private List<String> findNamesByIpSync(String ip) {
        String normalized = normalizeIp(ip);
        if (normalized == null || normalized.isBlank()) return List.of();
        String withSlash = "/" + normalized;
        String withPort = normalized + ":%";
        String withSlashPort = withSlash + ":%";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT username FROM users WHERE last_ip = ? OR last_ip = ? OR last_ip LIKE ? OR last_ip LIKE ?")) {
            ps.setString(1, normalized);
            ps.setString(2, withSlash);
            ps.setString(3, withPort);
            ps.setString(4, withSlashPort);
            try (ResultSet rs = ps.executeQuery()) {
                LinkedHashSet<String> names = new LinkedHashSet<>();
                while (rs.next()) {
                    String name = rs.getString("username");
                    if (name != null && !name.isBlank()) names.add(name);
                }
                return new ArrayList<>(names);
            }
        } catch (SQLException e) {
            LOG.warn("findNamesByIp failed", e);
            return List.of();
        }
    }

    public static String normalizeIp(String raw) {
        if (raw == null) return null;
        String ip = raw.trim();
        if (ip.startsWith("/")) ip = ip.substring(1);
        if (ip.startsWith("[")) {
            int end = ip.indexOf(']');
            if (end > 0) return ip.substring(1, end);
        }
        int lastColon = ip.lastIndexOf(':');
        if (lastColon > 0 && ip.indexOf(':') == lastColon) {
            return ip.substring(0, lastColon);
        }
        return ip;
    }

    public CompletableFuture<Void> setPremium(String username, boolean premium, UUID newUuid) {
        return CompletableFuture.runAsync(() -> {
            if (dataSource == null) {
                LOG.error("setPremium called but database not initialized (dataSource is null)! username={}", username);
                return;
            }
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET premium = ?, uuid = ? WHERE username_lower = ?")) {
                ps.setBoolean(1, premium);
                ps.setString(2, newUuid.toString());
                ps.setString(3, username.toLowerCase(Locale.ROOT));
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    LOG.warn("setPremium: no row found for username_lower='{}' — update had no effect", username.toLowerCase(Locale.ROOT));
                } else {
                    LOG.info("setPremium: set premium={} for '{}' (uuid={})", premium, username, newUuid);
                }
            } catch (Exception e) {
                LOG.warn("setPremium failed for '{}'", username, e);
            }
        }, io);
    }

    /**
     * Reads an AuthMe SQLite database and imports all users that are not yet registered.
     * Returns [imported, skipped].
     */
    public CompletableFuture<int[]> importFromAuthMe(Path sqlitePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("SQLite JDBC driver not found", e);
            }

            int imported = 0, skipped = 0;
            String url = "jdbc:sqlite:" + sqlitePath.toAbsolutePath().toString().replace('\\', '/');

            try (Connection sqlite = DriverManager.getConnection(url);
                 PreparedStatement sel = sqlite.prepareStatement(
                         "SELECT realname, username, password, ip, lastlogin, regdate FROM authme")) {

                ResultSet rs = sel.executeQuery();
                while (rs.next()) {
                    String realname      = rs.getString("realname");
                    String usernameLower = rs.getString("username");
                    String passwordHash  = rs.getString("password");
                    String ip            = rs.getString("ip");
                    long   lastloginMs   = rs.getLong("lastlogin");
                    long   regdateMs     = rs.getLong("regdate");

                    if (realname == null || passwordHash == null) { skipped++; continue; }
                    if (findByNameSync(realname).isPresent()) { skipped++; continue; }

                    UUID    uuid         = ru.goidacraft.goidaauth.auth.AuthSession.offlineUuid(realname);
                    Instant lastSeen     = lastloginMs > 0 ? Instant.ofEpochMilli(lastloginMs) : null;
                    Instant registeredAt = regdateMs   > 0 ? Instant.ofEpochMilli(regdateMs)   : Instant.now();
                    String  uLower       = (usernameLower != null ? usernameLower : realname)
                                                .toLowerCase(Locale.ROOT);

                    try (Connection c = dataSource.getConnection();
                         PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO users " +
                            "(uuid, username, username_lower, password_hash, premium, last_ip, last_seen, registered_at) " +
                            "VALUES (?, ?, ?, ?, FALSE, ?, ?, ?)")) {
                        ins.setString(1, uuid.toString());
                        ins.setString(2, realname);
                        ins.setString(3, uLower);
                        ins.setString(4, passwordHash);
                        ins.setString(5, normalizeIp(ip));
                        ins.setTimestamp(6, lastSeen != null ? Timestamp.from(lastSeen) : null);
                        ins.setTimestamp(7, Timestamp.from(registeredAt));
                        ins.executeUpdate();
                        imported++;
                    } catch (SQLException e) {
                        LOG.warn("Skipped {} during import: {}", realname, e.getMessage());
                        skipped++;
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to read AuthMe SQLite: " + e.getMessage(), e);
            }

            return new int[]{imported, skipped};
        }, io);
    }

    public CompletableFuture<Void> restoreUser(UserRecord r) {
        return CompletableFuture.runAsync(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users " +
                    "(uuid, username, username_lower, password_hash, premium, last_ip, last_seen, " +
                    "registered_at, rules_accepted) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE username = VALUES(username), " +
                    "username_lower = VALUES(username_lower), password_hash = VALUES(password_hash), " +
                    "premium = VALUES(premium), last_ip = VALUES(last_ip), " +
                    "last_seen = VALUES(last_seen), registered_at = VALUES(registered_at), " +
                    "rules_accepted = VALUES(rules_accepted)")) {
                ps.setString(1, r.uuid().toString());
                ps.setString(2, r.username());
                ps.setString(3, r.username().toLowerCase(Locale.ROOT));
                ps.setString(4, r.passwordHash());
                ps.setBoolean(5, r.premium());
                ps.setString(6, normalizeIp(r.lastIp()));
                ps.setTimestamp(7, r.lastSeen() == null ? null : Timestamp.from(r.lastSeen()));
                ps.setTimestamp(8, Timestamp.from(r.registeredAt() == null ? Instant.now() : r.registeredAt()));
                ps.setBoolean(9, r.rulesAccepted());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, io);
    }

    public CompletableFuture<Boolean> deleteUser(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM users WHERE username_lower = ?")) {
                ps.setString(1, username.toLowerCase(Locale.ROOT));
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                LOG.warn("deleteUser failed for {}", username, e);
                return false;
            }
        }, io);
    }

    public CompletableFuture<List<Map.Entry<String, List<String>>>> findIpsWithMultipleAccounts(int minCount) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "SELECT last_ip, COUNT(*) as cnt FROM users " +
                    "WHERE last_ip IS NOT NULL AND last_ip <> '' " +
                    "GROUP BY last_ip HAVING COUNT(*) > ? ORDER BY cnt DESC")) {
                ps.setInt(1, minCount);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map.Entry<String, List<String>>> result = new ArrayList<>();
                    while (rs.next()) {
                        String ip = rs.getString("last_ip");
                        List<String> names = findNamesByIpSync(ip);
                        result.add(Map.entry(ip, names));
                    }
                    return result;
                }
            } catch (SQLException e) {
                LOG.warn("findIpsWithMultipleAccounts failed", e);
                return List.of();
            }
        }, io);
    }

    public CompletableFuture<List<String>> findNamesByHwid(String hwid) {
        return CompletableFuture.supplyAsync(() -> findNamesByHwidSync(hwid), io);
    }

    private List<String> findNamesByHwidSync(String hwid) {
        if (hwid == null || hwid.isBlank()) return List.of();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT username FROM users WHERE hwid = ?")) {
            ps.setString(1, hwid);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (rs.next()) {
                    String name = rs.getString("username");
                    if (name != null && !name.isBlank()) names.add(name);
                }
                return names;
            }
        } catch (SQLException e) {
            LOG.warn("findNamesByHwid failed", e);
            return List.of();
        }
    }

    public CompletableFuture<Void> updateHwid(String username, String hwid) {
        return CompletableFuture.runAsync(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET hwid = ? WHERE username_lower = ?")) {
                ps.setString(1, hwid);
                ps.setString(2, username.toLowerCase(Locale.ROOT));
                ps.executeUpdate();
            } catch (SQLException e) {
                LOG.warn("updateHwid failed", e);
            }
        }, io);
    }

    /**
     * Drops the account-linkage data (last IP and hardware fingerprint) for one account, so it stops
     * showing up in the shared-IP report and in the anti-twink check.
     *
     * <p>Note this is not permanent by itself: both fields are written again the next time the
     * account logs in successfully. It clears history, it does not suppress future linkage.
     *
     * @return true if a row was actually updated
     */
    public CompletableFuture<Boolean> clearLinkage(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET last_ip = NULL, hwid = NULL WHERE username_lower = ?")) {
                ps.setString(1, username.toLowerCase(Locale.ROOT));
                boolean changed = ps.executeUpdate() > 0;
                if (changed) LOG.info("clearLinkage: wiped last_ip and hwid for '{}'", username);
                return changed;
            } catch (SQLException e) {
                LOG.warn("clearLinkage failed for {}", username, e);
                return false;
            }
        }, io);
    }

    // ------------------------------------------------------------------
    // Failed login attempts
    // ------------------------------------------------------------------

    /**
     * Records a failed attempt. Fire-and-forget: an audit write must never delay or fail a login,
     * so errors are logged and swallowed.
     */
    public void recordFailedAttempt(String username, String ip, UUID uuid, String reason) {
        CompletableFuture.runAsync(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO login_attempts (username, username_lower, ip, uuid, reason, attempted_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, username);
                ps.setString(2, username.toLowerCase(Locale.ROOT));
                ps.setString(3, normalizeIp(ip));
                ps.setString(4, uuid == null ? null : uuid.toString());
                ps.setString(5, reason);
                ps.setTimestamp(6, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            } catch (SQLException e) {
                LOG.warn("recordFailedAttempt failed for {}", username, e);
            }
        }, io);
    }

    /** Attempts aimed at one account — who tried to get in here. */
    public CompletableFuture<List<LoginAttempt>> findAttemptsByAccount(String username, int limit) {
        return queryAttempts("WHERE username_lower = ?", username.toLowerCase(Locale.ROOT), limit);
    }

    /** Attempts made from one address — which accounts this address went after. */
    public CompletableFuture<List<LoginAttempt>> findAttemptsByIp(String ip, int limit) {
        return queryAttempts("WHERE ip = ?", normalizeIp(ip), limit);
    }

    /** The latest attempts server-wide. */
    public CompletableFuture<List<LoginAttempt>> findRecentAttempts(int limit) {
        return queryAttempts("", null, limit);
    }

    private CompletableFuture<List<LoginAttempt>> queryAttempts(String where, String arg, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "SELECT username, ip, uuid, reason, attempted_at FROM login_attempts "
                            + where + " ORDER BY attempted_at DESC LIMIT ?")) {
                int i = 1;
                if (arg != null) ps.setString(i++, arg);
                ps.setInt(i, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    List<LoginAttempt> out = new ArrayList<>();
                    while (rs.next()) {
                        Timestamp at = rs.getTimestamp("attempted_at");
                        out.add(new LoginAttempt(
                                rs.getString("username"),
                                rs.getString("ip"),
                                rs.getString("uuid"),
                                rs.getString("reason"),
                                at == null ? null : at.toInstant()));
                    }
                    return out;
                }
            } catch (SQLException e) {
                LOG.warn("queryAttempts failed", e);
                return List.<LoginAttempt>of();
            }
        }, io);
    }

    /** Deletes recorded attempts for one account or one address. Console-only by policy. */
    public CompletableFuture<Integer> clearAttempts(boolean byIp, String value) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = byIp
                    ? "DELETE FROM login_attempts WHERE ip = ?"
                    : "DELETE FROM login_attempts WHERE username_lower = ?";
            String arg = byIp ? normalizeIp(value) : value.toLowerCase(Locale.ROOT);
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, arg);
                int rows = ps.executeUpdate();
                LOG.info("clearAttempts: removed {} rows for {}={}", rows, byIp ? "ip" : "account", arg);
                return rows;
            } catch (SQLException e) {
                LOG.warn("clearAttempts failed for {}", value, e);
                return 0;
            }
        }, io);
    }

    /** Drops attempts older than the retention window. Runs once at startup. */
    private void purgeOldAttempts(int days) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM login_attempts WHERE attempted_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(days))));
            int rows = ps.executeUpdate();
            if (rows > 0) LOG.info("Purged {} login attempts older than {} days", rows, days);
        } catch (SQLException e) {
            LOG.warn("purgeOldAttempts failed", e);
        }
    }

    public CompletableFuture<Void> updatePassword(String username, String newHash) {
        return CompletableFuture.runAsync(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET password_hash = ? WHERE username_lower = ?")) {
                ps.setString(1, newHash);
                ps.setString(2, username.toLowerCase(Locale.ROOT));
                ps.executeUpdate();
            } catch (SQLException e) {
                LOG.warn("updatePassword failed", e);
            }
        }, io);
    }

    private static UserRecord map(ResultSet rs) throws SQLException {
        Timestamp ls = rs.getTimestamp("last_seen");
        Timestamp ra = rs.getTimestamp("registered_at");
        return new UserRecord(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getBoolean("premium"),
                rs.getString("last_ip"),
                ls == null ? null : ls.toInstant(),
                ra == null ? Instant.now() : ra.toInstant(),
                rs.getBoolean("rules_accepted")
        );
    }
}
