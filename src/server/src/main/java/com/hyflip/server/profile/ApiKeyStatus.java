package com.hyflip.server.profile;

import com.hyflip.server.settings.SettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Expiry state of the personal Hypixel API key, so the UI can warn before profile features stop.
 * Personal keys expire every 3 days ({@code hypixel.api-key-lifetime}).
 */
@Component
public class ApiKeyStatus {

    /** Warn this long before the key expires. */
    static final Duration WARN_BEFORE = Duration.ofHours(12);

    private final SettingsService settings;
    private final Clock clock;

    @Autowired
    public ApiKeyStatus(SettingsService settings) {
        this(settings, Clock.systemUTC());
    }

    ApiKeyStatus(SettingsService settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public enum State { MISSING, UNKNOWN_AGE, VALID, EXPIRING_SOON, EXPIRED }

    /** @param source where the key comes from: NONE, CONFIG or APP */
    public record Status(State state, Instant expiresAt, SettingsService.KeySource source) {
    }

    public Status status() {
        SettingsService.ApiKey key = settings.apiKey();
        return evaluate(key.value(), key.issuedAt(), settings.apiKeyLifetime(), clock.instant(), key.source());
    }

    static Status evaluate(String key, Instant issuedAt, Duration lifetime, Instant now,
                           SettingsService.KeySource source) {
        if (key.isBlank()) {
            return new Status(State.MISSING, null, source);
        }
        if (issuedAt == null) {
            return new Status(State.UNKNOWN_AGE, null, source);
        }
        Instant expires = issuedAt.plus(lifetime);
        State state = !now.isBefore(expires) ? State.EXPIRED
                : now.isAfter(expires.minus(WARN_BEFORE)) ? State.EXPIRING_SOON : State.VALID;
        return new Status(state, expires, source);
    }
}
