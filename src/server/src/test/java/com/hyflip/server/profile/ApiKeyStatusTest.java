package com.hyflip.server.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyflip.server.settings.SettingsService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ApiKeyStatusTest {

    static final Instant ISSUED = Instant.parse("2026-11-17T00:00:00Z");

    @Test
    void reportsMissingUnknownValidExpiringAndExpired() {
        assertThat(status("", null, ISSUED).state()).isEqualTo(ApiKeyStatus.State.MISSING);
        assertThat(status("k", null, ISSUED).state()).isEqualTo(ApiKeyStatus.State.UNKNOWN_AGE);
        assertThat(status("k", ISSUED, ISSUED.plus(Duration.ofDays(1))).state()).isEqualTo(ApiKeyStatus.State.VALID);
        assertThat(status("k", ISSUED, ISSUED.plus(Duration.ofHours(65))).state())
                .isEqualTo(ApiKeyStatus.State.EXPIRING_SOON);
        ApiKeyStatus.Status expired = status("k", ISSUED, ISSUED.plus(Duration.ofDays(3)));
        assertThat(expired.state()).isEqualTo(ApiKeyStatus.State.EXPIRED);
        assertThat(expired.expiresAt()).isEqualTo(ISSUED.plus(Duration.ofDays(3)));
    }

    private static ApiKeyStatus.Status status(String key, Instant issued, Instant now) {
        return ApiKeyStatus.evaluate(key, issued, Duration.ofDays(3), now, SettingsService.KeySource.CONFIG);
    }
}
