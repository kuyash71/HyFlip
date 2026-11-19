package com.hyflip.server.api;

import com.hyflip.server.profile.ApiKeyStatus;
import com.hyflip.server.settings.Settings;
import com.hyflip.server.settings.SettingsService;
import java.time.Instant;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Settings edited in the app, and the Hypixel API key (write-only). */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final SettingsService settings;
    private final ApiKeyStatus keys;

    public SettingsController(SettingsService settings, ApiKeyStatus keys) {
        this.settings = settings;
        this.keys = keys;
    }

    /** @param source CONFIG until settings are saved in the app, then APP */
    public record View(Settings settings, SettingsService.Source source, ApiKeyStatus.Status apiKey) {
    }

    @GetMapping
    public View get() {
        return view();
    }

    /** JSON merge patch: send only the fields that change; null clears an optional field. */
    @PatchMapping(consumes = {"application/json", "application/merge-patch+json"})
    public View update(@RequestBody JsonNode patch) {
        settings.update(patch);
        return view();
    }

    /** Back to config.yaml. */
    @DeleteMapping
    public View reset() {
        settings.reset();
        return view();
    }

    /** @param issuedAt when the key was created; null means now */
    public record ApiKeyRequest(String key, Instant issuedAt) {
    }

    @PutMapping("/api-key")
    public ApiKeyStatus.Status setApiKey(@RequestBody ApiKeyRequest request) {
        settings.setApiKey(request.key(), request.issuedAt());
        return keys.status();
    }

    @DeleteMapping("/api-key")
    public ApiKeyStatus.Status clearApiKey() {
        settings.clearApiKey();
        return keys.status();
    }

    private View view() {
        return new View(settings.current(), settings.source(), keys.status());
    }
}
