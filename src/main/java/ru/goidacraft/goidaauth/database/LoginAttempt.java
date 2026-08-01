package ru.goidacraft.goidaauth.database;

import java.time.Instant;

/**
 * One recorded attempt to get into an account that failed.
 *
 * <p>Kept in its own table rather than on the {@code users} row: a failed attempt says something
 * about the person who tried, not about the account they aimed at, and the account's own columns
 * ({@code last_ip}, {@code hwid}) must keep describing the real owner only.
 *
 * @param username  the account that was aimed at
 * @param ip        where the attempt came from
 * @param uuid      the connecting player's UUID, useful when the same address changes nickname
 * @param reason    {@link #WRONG_PASSWORD} or {@link #PREMIUM_IMPOSTOR}
 * @param at        when it happened
 */
public record LoginAttempt(String username, String ip, String uuid, String reason, Instant at) {

    /** A wrong password was given to {@code /login}. */
    public static final String WRONG_PASSWORD = "wrong_password";

    /** An offline connection arrived on a licensed account and was rejected before any password. */
    public static final String PREMIUM_IMPOSTOR = "premium_impostor";

    /** Human-readable Russian label for the moderation report. */
    public String reasonLabel() {
        return switch (reason) {
            case WRONG_PASSWORD -> "неверный пароль";
            case PREMIUM_IMPOSTOR -> "вход на лицензионный аккаунт без лицензии";
            default -> reason;
        };
    }
}
