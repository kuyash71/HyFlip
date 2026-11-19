package com.hyflip.server.settings;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.storage.AppStateRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Effective settings: {@code config.yaml} until the player saves settings in the app, then the
 * stored copy ({@code app_state} key {@code settings}). Saving merges a JSON merge patch
 * (RFC 7386) into the current settings, so the UI can send only what changed. The collection
 * tier map is the exception: a patch that contains it replaces it.
 *
 * <p>The Hypixel API key entered in the app is stored separately and never returned by the API.
 */
@Service
public class SettingsService {

    static final String KEY_SETTINGS = "settings";
    static final String KEY_API_KEY = "hypixel.api-key";
    static final String KEY_API_KEY_ISSUED = "hypixel.api-key-issued-at";
    private static final Pattern API_KEY = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final HyFlipProperties properties;
    private final AppStateRepository state;
    private final ObjectMapper json;
    private final Clock clock;
    private final AtomicReference<Settings> cached = new AtomicReference<>();

    @Autowired
    public SettingsService(HyFlipProperties properties, AppStateRepository state, ObjectMapper json) {
        this(properties, state, json, Clock.systemUTC());
    }

    SettingsService(HyFlipProperties properties, AppStateRepository state, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.state = state;
        this.json = json;
        this.clock = clock;
    }

    /** CONFIG while the settings come from config.yaml, APP once saved in the app. */
    public enum Source { CONFIG, APP }

    /** Where the API key comes from. */
    public enum KeySource { NONE, CONFIG, APP }

    /** The personal Hypixel API key and when it was issued (null if unknown). */
    public record ApiKey(String value, Instant issuedAt, KeySource source) {
        public boolean present() {
            return !value.isBlank();
        }
    }

    public Settings current() {
        Settings s = cached.get();
        if (s == null) {
            s = state.get(KEY_SETTINGS).map(this::read).orElseGet(() -> Settings.of(properties));
            cached.set(s);
        }
        return s;
    }

    public Source source() {
        return state.get(KEY_SETTINGS).isPresent() ? Source.APP : Source.CONFIG;
    }

    /**
     * Applies a JSON merge patch to the current settings and stores the result.
     *
     * @throws SettingsException if the result is invalid; nothing is stored then
     */
    public Settings update(JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            throw new SettingsException("The body must be a JSON object");
        }
        JsonNode merged = merge(json.valueToTree(current()), patch);
        // Collection tiers are replaced as a whole: merging would keep collections the player removed.
        JsonNode tiers = patch.path("player").path("collections");
        if (tiers.isObject() && merged.path("player").isObject()) {
            ObjectNode clean = ((ObjectNode) tiers).objectNode();
            tiers.properties().forEach(e -> {
                if (!e.getValue().isNull()) {
                    clean.set(e.getKey(), e.getValue());
                }
            });
            ((ObjectNode) merged.path("player")).set("collections", clean);
        }
        Settings next;
        try {
            next = json.treeToValue(merged, Settings.class);
        } catch (JacksonException e) {
            throw new SettingsException("Unreadable settings: " + e.getOriginalMessage());
        }
        List<String> problems = validate(next);
        if (!problems.isEmpty()) {
            throw new SettingsException(problems);
        }
        state.put(KEY_SETTINGS, json.writeValueAsString(next));
        cached.set(next);
        return next;
    }

    /** Drops the settings saved in the app, going back to config.yaml. */
    public Settings reset() {
        state.delete(KEY_SETTINGS);
        cached.set(null);
        return current();
    }

    public ApiKey apiKey() {
        Optional<String> stored = state.get(KEY_API_KEY);
        if (stored.isPresent()) {
            Instant issued = state.get(KEY_API_KEY_ISSUED).map(Instant::parse).orElse(null);
            return new ApiKey(stored.get(), issued, KeySource.APP);
        }
        String configured = properties.hypixel().apiKey();
        return configured.isBlank() ? new ApiKey("", null, KeySource.NONE)
                : new ApiKey(configured, properties.hypixel().apiKeyIssuedAt(), KeySource.CONFIG);
    }

    /**
     * Stores an API key entered in the app. Personal keys are usually entered right after they
     * were created, so a missing {@code issuedAt} means now.
     */
    public ApiKey setApiKey(String key, Instant issuedAt) {
        String trimmed = key == null ? "" : key.trim();
        if (!API_KEY.matcher(trimmed).matches()) {
            throw new SettingsException("The API key must look like xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx");
        }
        Instant issued = issuedAt == null ? clock.instant() : issuedAt;
        if (issued.isAfter(clock.instant().plus(Duration.ofMinutes(5)))) {
            throw new SettingsException("issuedAt is in the future");
        }
        state.put(KEY_API_KEY, trimmed);
        state.put(KEY_API_KEY_ISSUED, issued.toString());
        return apiKey();
    }

    /** Forgets the key entered in the app; a key in config.yaml applies again. */
    public ApiKey clearApiKey() {
        state.delete(KEY_API_KEY);
        state.delete(KEY_API_KEY_ISSUED);
        return apiKey();
    }

    public Duration apiKeyLifetime() {
        return properties.hypixel().apiKeyLifetime();
    }

    private Settings read(String body) {
        Settings defaults = Settings.of(properties);
        try {
            // Merge onto the defaults so settings stored by an older version gain new fields.
            return json.treeToValue(merge(json.valueToTree(defaults), json.readTree(body)), Settings.class);
        } catch (JacksonException e) {
            return defaults;
        }
    }

    /** RFC 7386: objects merge recursively, null removes, anything else replaces. */
    static JsonNode merge(JsonNode target, JsonNode patch) {
        if (!patch.isObject()) {
            return patch;
        }
        ObjectNode result = target != null && target.isObject() ? ((ObjectNode) target).deepCopy()
                : ((ObjectNode) patch).objectNode();
        for (Map.Entry<String, JsonNode> e : patch.properties()) {
            if (e.getValue().isNull()) {
                result.putNull(e.getKey());
            } else {
                result.set(e.getKey(), merge(result.get(e.getKey()), e.getValue()));
            }
        }
        return result;
    }

    static List<String> validate(Settings s) {
        List<String> p = new ArrayList<>();
        if (s.player() == null || s.trading() == null || s.forge() == null || s.npc() == null || s.bazaar() == null
                || s.alerts() == null || s.display() == null) {
            p.add("player, trading, forge, npc, bazaar, alerts and display are required");
            return p;
        }
        HyFlipProperties.Player player = s.player();
        if (player.username() == null || !player.username().matches("[A-Za-z0-9_]{0,16}")) {
            p.add("player.username must be a Minecraft name (letters, digits, _; at most 16)");
        }
        if (player.profile() == null || player.profile().length() > 32) {
            p.add("player.profile must be at most 32 characters");
        }
        if (player.coopMember() == null || !player.coopMember().matches("[A-Za-z0-9_]{0,16}")) {
            p.add("player.coopMember must be a Minecraft name or empty");
        }
        range(p, "player.hotmLevel", player.hotmLevel(), 0, 10);
        range(p, "player.forgeSlots", player.forgeSlots(), 1, 7);
        if (player.collections() == null) {
            p.add("player.collections is required (may be empty)");
        } else {
            player.collections().forEach((k, v) -> {
                if (k == null || k.isBlank() || v == null || v < 0 || v > 30) {
                    p.add("player.collections: tier of " + k + " must be between 0 and 30");
                }
            });
        }

        HyFlipProperties.Trading t = s.trading();
        if (t.budget() != null && t.budget().compareTo(BigDecimal.ZERO) < 0) {
            p.add("trading.budget must be zero or more");
        }
        if (t.bazaarTaxRate() == null || t.bazaarTaxRate().doubleValue() < 0 || t.bazaarTaxRate().doubleValue() > 0.1) {
            p.add("trading.bazaarTaxRate must be between 0 and 0.1");
        }
        if (t.priceSource() == null) {
            p.add("trading.priceSource must be ORDER or INSTANT");
        }
        HyFlipProperties.AuctionFees f = t.auctionFees();
        if (f == null) {
            p.add("trading.auctionFees is required");
        } else {
            rate(p, "trading.auctionFees.smallRate", f.smallRate(), 0.2);
            rate(p, "trading.auctionFees.mediumRate", f.mediumRate(), 0.2);
            rate(p, "trading.auctionFees.largeRate", f.largeRate(), 0.2);
            rate(p, "trading.auctionFees.claimTaxRate", f.claimTaxRate(), 0.2);
            if (f.mediumFrom() < 0 || f.largeFrom() < f.mediumFrom() || f.claimTaxFrom() < 0) {
                p.add("trading.auctionFees: thresholds must be zero or more, with largeFrom ≥ mediumFrom");
            }
        }

        HyFlipProperties.Forge forge = s.forge();
        if (forge.quickForgeReduction() != null && (forge.quickForgeReduction() < 0 || forge.quickForgeReduction() > 0.9)) {
            p.add("forge.quickForgeReduction must be between 0 and 0.9");
        }
        if (forge.moltenForge() == null) {
            p.add("forge.moltenForge must be AUTO, ON or OFF");
        }
        if (forge.nextSessionIn() == null || forge.nextSessionIn().compareTo(Duration.ofMinutes(1)) < 0
                || forge.nextSessionIn().compareTo(Duration.ofDays(7)) > 0) {
            p.add("forge.nextSessionIn must be between 1 minute and 7 days");
        }

        if (s.npc().dailyLimit() < 0 || s.npc().dailyLimit() > 100_000) {
            p.add("npc.dailyLimit must be between 0 and 100000");
        }
        share(p, "npc.marketShare", s.npc().marketShare());
        share(p, "bazaar.marketShare", s.bazaar().marketShare());
        if (s.bazaar().thinBook() < 0 || s.bazaar().thinBook() > 1000) {
            p.add("bazaar.thinBook must be between 0 and 1000");
        }

        HyFlipProperties.Alerts a = s.alerts();
        if (a.minAuctionSales() < 1 || a.minAuctionSales() > 100) {
            p.add("alerts.minAuctionSales must be between 1 and 100");
        }
        if (!(a.wideSpread() > 0) || a.wideSpread() > 10) {
            p.add("alerts.wideSpread must be above 0 and at most 10");
        }
        if (!(a.manipulationRatio() > 1) || a.manipulationRatio() > 100) {
            p.add("alerts.manipulationRatio must be above 1 and at most 100");
        }
        String zone = s.display().timeZone();
        if (zone == null) {
            p.add("display.timeZone is required (may be empty)");
        } else if (!zone.isBlank()) {
            try {
                java.time.ZoneId.of(zone.trim());
            } catch (java.time.DateTimeException e) {
                p.add("display.timeZone: unknown time zone " + zone + " (use a name like Europe/Istanbul)");
            }
        }
        return p;
    }

    /** Clock times are shown in this zone. */
    public java.time.ZoneId zone() {
        try {
            return current().display().zone();
        } catch (java.time.DateTimeException e) {
            return java.time.ZoneId.systemDefault();
        }
    }

    private static void range(List<String> p, String name, Integer v, int min, int max) {
        if (v != null && (v < min || v > max)) {
            p.add(name + " must be between " + min + " and " + max);
        }
    }

    private static void rate(List<String> p, String name, double v, double max) {
        if (!(v >= 0) || v > max) {
            p.add(name + " must be between 0 and " + max);
        }
    }

    private static void share(List<String> p, String name, double v) {
        if (!(v > 0) || v > 1) {
            p.add(name + " must be above 0 and at most 1");
        }
    }
}
