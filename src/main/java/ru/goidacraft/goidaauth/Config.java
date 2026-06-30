package ru.goidacraft.goidaauth;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class Config {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue PREMIUM_AUTOLOGIN;
    public static final ModConfigSpec.IntValue MOJANG_TIMEOUT_MS;
    public static final ModConfigSpec.IntValue MOJANG_CACHE_TTL_MIN;
    public static final ModConfigSpec.IntValue LUCKPERMS_DEFER_TICKS;

    public static final ModConfigSpec.ConfigValue<String> DB_MODE;
    public static final ModConfigSpec.ConfigValue<String> DB_HOST;
    public static final ModConfigSpec.IntValue            DB_PORT;
    public static final ModConfigSpec.ConfigValue<String> DB_NAME;
    public static final ModConfigSpec.ConfigValue<String> DB_USER;
    public static final ModConfigSpec.ConfigValue<String> DB_PASSWORD;
    public static final ModConfigSpec.IntValue            DB_POOL_SIZE;
    public static final ModConfigSpec.BooleanValue        DB_USE_SSL;

    public static final ModConfigSpec.IntValue LOGIN_TIMEOUT_SEC;
    public static final ModConfigSpec.IntValue MAX_LOGIN_ATTEMPTS;
    public static final ModConfigSpec.IntValue MIN_PASSWORD_LEN;
    public static final ModConfigSpec.IntValue MAX_PASSWORD_LEN;
    public static final ModConfigSpec.BooleanValue REGISTER_CONFIRM_REQUIRED;
    public static final ModConfigSpec.BooleanValue SESSION_ENABLED;
    public static final ModConfigSpec.IntValue SESSION_TIMEOUT_MIN;
    public static final ModConfigSpec.BooleanValue SESSION_REQUIRE_SAME_IP;

    public static final ModConfigSpec.IntValue ARGON2_ITERATIONS;
    public static final ModConfigSpec.IntValue ARGON2_MEMORY_KB;
    public static final ModConfigSpec.IntValue ARGON2_PARALLELISM;

    public static final ModConfigSpec.BooleanValue APPLY_BLINDNESS;
    public static final ModConfigSpec.BooleanValue APPLY_SLOWNESS;
    public static final ModConfigSpec.BooleanValue FREEZE_PLAYER;
    public static final ModConfigSpec.BooleanValue GOD_MODE;
    public static final ModConfigSpec.BooleanValue HIDE_FROM_OTHER_PLAYERS;
    public static final ModConfigSpec.BooleanValue TELEPORT_TO_SAFE_ROOM;

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_COMMANDS;

    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRANSFER_EXTRA_FILES;

    public static final ModConfigSpec.ConfigValue<String> TWINK_MODE;
    public static final ModConfigSpec.ConfigValue<String> MSG_TWINK_KICK;

    public static final ModConfigSpec.ConfigValue<String> MSG_LOGIN_PROMPT;
    public static final ModConfigSpec.ConfigValue<String> MSG_REGISTER_PROMPT;
    public static final ModConfigSpec.ConfigValue<String> MSG_LOGIN_SUCCESS;
    public static final ModConfigSpec.ConfigValue<String> MSG_LOGIN_FAIL;
    public static final ModConfigSpec.ConfigValue<String> MSG_REGISTER_SUCCESS;
    public static final ModConfigSpec.ConfigValue<String> MSG_REGISTER_MISMATCH;
    public static final ModConfigSpec.ConfigValue<String> MSG_REGISTER_TAKEN;
    public static final ModConfigSpec.ConfigValue<String> MSG_PASSWORD_TOO_SHORT;
    public static final ModConfigSpec.ConfigValue<String> MSG_PASSWORD_TOO_LONG;
    public static final ModConfigSpec.ConfigValue<String> MSG_NOT_REGISTERED;
    public static final ModConfigSpec.ConfigValue<String> MSG_ALREADY_AUTH;
    public static final ModConfigSpec.ConfigValue<String> MSG_PREMIUM_AUTOLOGIN;
    public static final ModConfigSpec.ConfigValue<String> MSG_PREMIUM_KICK;
    public static final ModConfigSpec.ConfigValue<String> MSG_SESSION_AUTOLOGIN;
    public static final ModConfigSpec.ConfigValue<String> MSG_TIMEOUT_KICK;
    public static final ModConfigSpec.ConfigValue<String> MSG_BLOCKED_ACTION;

    static {
        var b = new ModConfigSpec.Builder();

        b.comment("Database backend. Use 'mysql' to share the users table with the Velocity",
                  "GoidaAuthVelocity plugin (required for premium auto-login behind a proxy).",
                  "Use 'h2' for the legacy single-process embedded database.").push("database");
        DB_MODE = b.comment("h2 | mysql | mariadb")
                .define("mode", "h2");
        DB_HOST = b.define("host", "127.0.0.1");
        DB_PORT = b.defineInRange("port", 3306, 1, 65535);
        DB_NAME = b.define("database", "goidaauth");
        DB_USER = b.define("user", "goidaauth_rw");
        DB_PASSWORD = b.define("password", "");
        DB_POOL_SIZE = b.comment("Connection pool size (mysql only).")
                .defineInRange("pool_size", 10, 1, 64);
        DB_USE_SSL = b.comment("Set true only if your MySQL enforces TLS.")
                .define("use_ssl", false);
        b.pop();

        b.comment("Premium / autologin settings").push("premium");
        PREMIUM_AUTOLOGIN = b.comment("Enable Mojang session check for premium players (FastLogin behaviour).")
                .define("autologin", true);
        MOJANG_TIMEOUT_MS = b.comment("Timeout for Mojang API calls in milliseconds.")
                .defineInRange("mojang_timeout_ms", 5000, 1000, 30000);
        MOJANG_CACHE_TTL_MIN = b.comment("How many minutes to cache 'is name premium' lookups.")
                .defineInRange("mojang_cache_ttl_min", 360, 1, 10080);
        b.pop();

        b.comment("Compatibility workarounds").push("compat");
        LUCKPERMS_DEFER_TICKS = b.comment(
                "When LuckPerms is installed as a NeoForge mod, the player-login event is delayed",
                "this many server ticks before being delivered. This avoids the LuckPerms/NeoForge",
                "race condition ('Capability has not been initialised') that disconnects players on",
                "join with 'Invalid player data'. Increase if players still get disconnected.",
                "20 ticks = 1 second. Has no effect when LuckPerms is a Bukkit plugin.")
                .defineInRange("luckperms_login_defer_ticks", 5, 1, 200);
        b.pop();

        b.comment("Login flow").push("login");
        LOGIN_TIMEOUT_SEC = b.comment("Seconds before an unauthenticated player is kicked.")
                .defineInRange("timeout_seconds", 60, 10, 600);
        MAX_LOGIN_ATTEMPTS = b.comment("Max wrong /login attempts before kick.")
                .defineInRange("max_attempts", 5, 1, 20);
        MIN_PASSWORD_LEN = b.comment("Minimum password length.")
                .defineInRange("min_password_length", 4, 1, 64);
        MAX_PASSWORD_LEN = b.comment("Maximum password length.")
                .defineInRange("max_password_length", 64, 8, 256);
        REGISTER_CONFIRM_REQUIRED = b.comment("Require /register <pass> <pass>. If false, /register <pass> is allowed.")
                .define("register_confirm_required", true);
        ALLOWED_COMMANDS = b.comment("Commands a non-authenticated player can still execute (without leading slash).",
                        "Note: /premium and /unpremium are intentionally NOT here — they require login first.")
                .defineList("allowed_commands",
                        List.of("login", "l", "register", "reg", "help"),
                        () -> "",
                        o -> o instanceof String);
        b.pop();

        b.comment("Session auto-login for cracked players").push("sessions");
        SESSION_ENABLED = b.comment("Enable session-based auto-login for cracked players.")
                .define("enabled", false);
        SESSION_TIMEOUT_MIN = b.comment("Minutes before session expires.")
                .defineInRange("timeout_minutes", 10, 1, 43200);
        SESSION_REQUIRE_SAME_IP = b.comment("Require same IP for session auto-login.")
                .define("require_same_ip", true);
        b.pop();

        b.comment("Password hashing parameters (legacy section name for compatibility)").push("argon2");
        ARGON2_ITERATIONS = b.defineInRange("iterations", 3, 1, 20);
        ARGON2_MEMORY_KB = b.defineInRange("memory_kb", 65536, 1024, 1048576);
        ARGON2_PARALLELISM = b.defineInRange("parallelism", 1, 1, 16);
        b.pop();

        b.comment("Restrictions applied to unauthenticated players").push("restrictions");
        APPLY_BLINDNESS = b.define("blindness", true);
        APPLY_SLOWNESS = b.define("slowness", true);
        FREEZE_PLAYER = b.comment("Force the player back to their join position every tick.")
                .define("freeze", true);
        GOD_MODE = b.comment("Cancel all incoming damage to unauthenticated players.")
                .define("god_mode", true);
        HIDE_FROM_OTHER_PLAYERS = b.comment("Make unauthenticated players invisible to other players (does not hide nameplate fully).")
                .define("invisible", false);
        TELEPORT_TO_SAFE_ROOM = b.comment("If true, teleport unauthenticated players to the spawn point until they log in.")
                .define("teleport_to_spawn", false);
        b.pop();

        b.comment("Account transfer (/transferaccount)").push("transfer");
        TRANSFER_EXTRA_FILES = b.comment(
                "Extra per-player files to move during /transferaccount, IN ADDITION to the",
                "playerdata file and all its sidecars (e.g. .cosarmor), stats, advancements and",
                "scoreboard scores, which are always handled. Use this for mods that keep their own",
                "per-player file keyed by UUID. Paths are relative to the world folder; '{uuid}' is",
                "replaced with the player UUID. These files are also backed up and restorable.",
                "Example: \"data/mymod/{uuid}.dat\"")
                .defineList("extra_player_files", List.of(), () -> "", o -> o instanceof String);
        b.pop();

        b.comment("Twink (multi-account) protection").push("twink_protection");
        TWINK_MODE = b.comment(
                "Anti-twink mode.",
                "  disabled — feature is off (default).",
                "  ip       — block a connecting player if another registered account",
                "             already has the same last_ip in the database.",
                "  hardware — block by hardware fingerprint sent by the client mod;",
                "             falls back to IP when no fingerprint is received",
                "             (i.e. on vanilla clients without the companion mod).")
                .define("mode", "disabled");
        MSG_TWINK_KICK = b.comment("Message shown when a player is kicked for twink protection.")
                .define("kick_message", "§cСоздание нескольких аккаунтов запрещено на этом сервере.");
        b.pop();

        b.comment("Player-facing messages (Russian by default)").push("messages");
        MSG_LOGIN_PROMPT = b.define("login_prompt", "§eВведите §a/login <пароль> §eдля входа.");
        MSG_REGISTER_PROMPT = b.define("register_prompt", "§eВы не зарегистрированы. Введите §a/register <пароль> <пароль> §eдля регистрации.");
        MSG_LOGIN_SUCCESS = b.define("login_success", "§aВы успешно авторизованы.");
        MSG_LOGIN_FAIL = b.define("login_fail", "§cНеверный пароль.");
        MSG_REGISTER_SUCCESS = b.define("register_success", "§aВы успешно зарегистрированы и авторизованы.");
        MSG_REGISTER_MISMATCH = b.define("register_mismatch", "§cПароли не совпадают.");
        MSG_REGISTER_TAKEN = b.define("register_taken", "§cЭтот никнейм уже зарегистрирован. Используйте §a/login§c.");
        MSG_PASSWORD_TOO_SHORT = b.define("password_too_short", "§cПароль слишком короткий.");
        MSG_PASSWORD_TOO_LONG = b.define("password_too_long", "§cПароль слишком длинный.");
        MSG_NOT_REGISTERED = b.define("not_registered", "§cВы не зарегистрированы.");
        MSG_ALREADY_AUTH = b.define("already_auth", "§eВы уже авторизованы.");
        MSG_PREMIUM_AUTOLOGIN = b.define("premium_autologin", "§a✔ §fАвтоматический вход через лицензию Mojang.");
        MSG_PREMIUM_KICK = b.define("premium_kick", "Этот никнейм принадлежит лицензионному аккаунту. Войдите с официального клиента Minecraft.");
        MSG_SESSION_AUTOLOGIN = b.define("session_autologin", "§a✔ §fАвтоматический вход по сессии.");
        MSG_TIMEOUT_KICK = b.define("timeout_kick", "Время на авторизацию истекло.");
        MSG_BLOCKED_ACTION = b.define("blocked_action", "§cСначала войдите в аккаунт.");
        b.pop();

        SPEC = b.build();
    }

    private Config() {}
}
