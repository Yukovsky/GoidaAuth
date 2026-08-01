package ru.goidacraft.goidaauth.auth;

/**
 * The whole premium/password decision, as a pure function of three booleans. Kept free of Minecraft
 * types on purpose: this is the security core, and it has to stay readable and checkable.
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@code mojangVerified} — the connection's UUID is <b>not</b> the offline UUID derived from
 *       its username, i.e. the proxy ran a real Mojang handshake for it
 *       ({@link AuthSession#offlineUuid}). This is the only premium proof that exists behind an
 *       offline-mode proxy. Profile properties are not: skin plugins attach genuine Mojang-signed
 *       {@code textures} to cracked players, so trusting those handed out password-free logins.</li>
 *   <li>{@code recordExists} / {@code dbPremium} — the account row and its {@code premium} flag.
 *       The database is the authority: it is what {@code /premium} and {@code /unpremium} write,
 *       and what the proxy reads when it decides to force online or offline mode.</li>
 * </ul>
 *
 * <p>Run the self-check with:
 * {@code java src/main/java/ru/goidacraft/goidaauth/auth/LoginDecision.java}
 */
public enum LoginDecision {
    /** Verified Mojang session on a licensed account — let in, no password. */
    PREMIUM_LOGIN,
    /** Verified Mojang session, account unknown — register it as licensed and let in. */
    PREMIUM_FIRST_JOIN,
    /** Licensed account reached over an offline connection — impostor or stale route. Kick. */
    REJECT_IMPOSTOR,
    /** Everything else — the password gate. */
    PASSWORD;

    public static LoginDecision of(boolean mojangVerified, boolean recordExists, boolean dbPremium) {
        if (dbPremium && !mojangVerified) return REJECT_IMPOSTOR;
        if (mojangVerified && dbPremium) return PREMIUM_LOGIN;
        if (mojangVerified && !recordExists) return PREMIUM_FIRST_JOIN;
        // Mojang-verified but the DB says cracked (an admin ran /unpremium and the proxy still had
        // the old route): the DB wins, no free pass.
        return PASSWORD;
    }

    /** True when this outcome lets the player in without ever checking a password. */
    public boolean skipsPassword() {
        return this == PREMIUM_LOGIN || this == PREMIUM_FIRST_JOIN;
    }

    public static void main(String[] args) {
        // mojangVerified, recordExists, dbPremium
        check(of(true,  true,  true),  PREMIUM_LOGIN);
        check(of(true,  false, false), PREMIUM_FIRST_JOIN);
        check(of(false, true,  true),  REJECT_IMPOSTOR);
        check(of(false, true,  false), PASSWORD);
        check(of(false, false, false), PASSWORD);
        // The bug this class exists to prevent: an offline connection must never skip the password,
        // whatever the account looks like.
        for (boolean exists : new boolean[]{true, false}) {
            for (boolean premium : new boolean[]{true, false}) {
                if (of(false, exists, premium).skipsPassword()) {
                    throw new AssertionError("offline connection skipped the password gate: exists="
                            + exists + " premium=" + premium);
                }
            }
        }
        // /unpremium must be effective even against a Mojang-verified connection.
        check(of(true, true, false), PASSWORD);
        System.out.println("LoginDecision self-check passed");
    }

    private static void check(LoginDecision actual, LoginDecision expected) {
        if (actual != expected) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
