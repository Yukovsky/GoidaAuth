package ru.goidacraft.goidaauth.auth;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-address limit on wrong {@code /login} attempts.
 *
 * <p>{@code AuthSession} already counts failures, but that counter lives inside one connection: a
 * new session starts at zero, so an attacker only had to reconnect to get another batch of guesses.
 * This counter is keyed by IP and outlives reconnects, which is what actually bounds brute force.
 *
 * <p>The block duration doubles as the window — failures older than one block period are forgotten,
 * so a legitimate player who mistypes a few times over an evening never accumulates a block.
 *
 * <p>Keyed by IP only, deliberately not by account name: blocking per account would let anyone lock
 * a chosen player out of their own account by failing logins on purpose.
 *
 * <p>Run the self-check with:
 * {@code java src/main/java/ru/goidacraft/goidaauth/auth/LoginThrottle.java}
 */
public final class LoginThrottle {

    /** @param maxFailures failures from one address before it is blocked
     *  @param blockMs     how long the block lasts, and how long a failure is remembered */
    public record Policy(int maxFailures, long blockMs) {}

    /** Above this many tracked addresses, expired entries are swept on the next failure. */
    private static final int SWEEP_THRESHOLD = 1024;

    private static final class Entry {
        int failures;
        long windowStart;
        long blockedUntil;
    }

    private final ConcurrentHashMap<String, Entry> byIp = new ConcurrentHashMap<>();

    /** Seconds left on this address's block, or 0 when it may try again. */
    public long blockedSecondsLeft(String ip, long now) {
        if (ip == null || ip.isBlank()) return 0;
        Entry e = byIp.get(key(ip));
        if (e == null) return 0;
        long left = e.blockedUntil - now;
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    /**
     * Records one wrong password from this address.
     *
     * @return seconds of block now in force, 0 if the address may still try
     */
    public long recordFailure(String ip, long now, Policy policy) {
        if (ip == null || ip.isBlank()) return 0;
        byIp.compute(key(ip), (k, old) -> {
            Entry e = old;
            if (e == null || (e.blockedUntil <= now && now - e.windowStart > policy.blockMs())) {
                e = new Entry();          // first failure, or the previous window has expired
                e.windowStart = now;
            }
            e.failures++;
            if (e.failures >= policy.maxFailures()) {
                e.blockedUntil = now + policy.blockMs();
            }
            return e;
        });
        if (byIp.size() > SWEEP_THRESHOLD) sweep(now, policy);
        return blockedSecondsLeft(ip, now);
    }

    /** Forgets an address — called on a successful login so a bad streak is not held against it. */
    public void clear(String ip) {
        if (ip == null || ip.isBlank()) return;
        byIp.remove(key(ip));
    }

    private void sweep(long now, Policy policy) {
        byIp.values().removeIf(e -> e.blockedUntil <= now && now - e.windowStart > policy.blockMs());
    }

    private static String key(String ip) {
        return ip.toLowerCase(Locale.ROOT);
    }

    public static void main(String[] args) {
        Policy p = new Policy(3, 1000);
        LoginThrottle t = new LoginThrottle();
        long t0 = 10_000;

        check(t.recordFailure("1.2.3.4", t0, p) == 0, "1st failure must not block");
        check(t.recordFailure("1.2.3.4", t0 + 10, p) == 0, "2nd failure must not block");
        check(t.recordFailure("1.2.3.4", t0 + 20, p) > 0, "3rd failure must block");
        check(t.blockedSecondsLeft("1.2.3.4", t0 + 20) == 1, "block must report ~1s left");

        // Другой адрес не должен страдать от чужой блокировки.
        check(t.blockedSecondsLeft("9.9.9.9", t0 + 20) == 0, "other address must stay free");

        // По истечении блокировки адрес снова свободен, и счётчик начинается заново.
        long after = t0 + 20 + 1001;
        check(t.blockedSecondsLeft("1.2.3.4", after) == 0, "block must expire");
        check(t.recordFailure("1.2.3.4", after, p) == 0, "counter must reset after the window");

        // Успешный вход снимает накопленные неудачи.
        LoginThrottle t2 = new LoginThrottle();
        t2.recordFailure("5.5.5.5", t0, p);
        t2.recordFailure("5.5.5.5", t0, p);
        t2.clear("5.5.5.5");
        check(t2.recordFailure("5.5.5.5", t0, p) == 0, "clear() must reset the counter");

        // Медленный игрок, ошибающийся реже одного окна, не должен копить блокировку.
        LoginThrottle t3 = new LoginThrottle();
        long slow = t0;
        for (int i = 0; i < 10; i++) {
            check(t3.recordFailure("7.7.7.7", slow, p) == 0, "slow typist must never be blocked");
            slow += p.blockMs() + 1;
        }

        // Отсутствующий адрес не должен ронять вызов.
        check(t.recordFailure(null, t0, p) == 0, "null ip must be a no-op");
        check(t.blockedSecondsLeft("", t0) == 0, "blank ip must be a no-op");

        System.out.println("LoginThrottle self-check passed");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
