package ru.goidacraft.goidaauth.mojang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.goidacraft.goidaauth.Config;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class MojangApi {
    private static final Logger LOG = LoggerFactory.getLogger(MojangApi.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ConcurrentHashMap<String, CacheEntry> nameCache = new ConcurrentHashMap<>();

    public CompletableFuture<Optional<UUID>> lookupPremiumUuid(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        CacheEntry cached = nameCache.get(key);
        if (cached != null && !cached.expired()) {
            return CompletableFuture.completedFuture(cached.uuid);
        }

        String url = "https://api.mojang.com/users/profiles/minecraft/"
                + URLEncoder.encode(username, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Config.MOJANG_TIMEOUT_MS.get()))
                .GET()
                .build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).handle((res, err) -> {
            if (err != null) {
                LOG.warn("Mojang lookup failed for {}: {}", username, err.getMessage());
                return Optional.<UUID>empty();
            }
            if (res.statusCode() == 200 && res.body() != null && !res.body().isBlank()) {
                try {
                    JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
                    String id = obj.get("id").getAsString();
                    UUID uuid = parseTrimmedUuid(id);
                    Optional<UUID> result = Optional.of(uuid);
                    nameCache.put(key, new CacheEntry(result, ttlInstant()));
                    return result;
                } catch (Exception e) {
                    LOG.warn("Mojang response parse failure for {}", username, e);
                }
            } else if (res.statusCode() == 204 || res.statusCode() == 404) {
                Optional<UUID> result = Optional.empty();
                nameCache.put(key, new CacheEntry(result, ttlInstant()));
                return result;
            }
            return Optional.<UUID>empty();
        });
    }

    public CompletableFuture<Optional<JsonObject>> hasJoined(String username, String serverHash) {
        String url = "https://sessionserver.mojang.com/session/minecraft/hasJoined?username="
                + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&serverId=" + URLEncoder.encode(serverHash, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Config.MOJANG_TIMEOUT_MS.get()))
                .GET()
                .build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).handle((res, err) -> {
            if (err != null) {
                LOG.warn("hasJoined failed for {}: {}", username, err.getMessage());
                return Optional.<JsonObject>empty();
            }
            if (res.statusCode() == 200 && res.body() != null && !res.body().isBlank()) {
                try {
                    return Optional.of(JsonParser.parseString(res.body()).getAsJsonObject());
                } catch (Exception e) {
                    LOG.warn("hasJoined parse failure", e);
                }
            }
            return Optional.<JsonObject>empty();
        });
    }

    public void invalidate(String username) {
        nameCache.remove(username.toLowerCase(Locale.ROOT));
    }

    private Instant ttlInstant() {
        return Instant.now().plus(Duration.ofMinutes(Config.MOJANG_CACHE_TTL_MIN.get()));
    }

    private static UUID parseTrimmedUuid(String id) {
        String s = id.replace("-", "");
        return UUID.fromString(
                s.substring(0, 8) + "-" + s.substring(8, 12) + "-"
                        + s.substring(12, 16) + "-" + s.substring(16, 20) + "-"
                        + s.substring(20, 32));
    }

    private record CacheEntry(Optional<UUID> uuid, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
