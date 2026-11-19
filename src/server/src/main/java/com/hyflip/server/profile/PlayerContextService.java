package com.hyflip.server.profile;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.engine.Fees;
import com.hyflip.server.settings.Settings;
import com.hyflip.server.settings.SettingsService;
import com.hyflip.server.storage.ResourceRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds the {@link PlayerContext} from the settings, the linked profile and the stored election
 * and collections resources. Linked profile values (HOTM, Quick Forge, collection tiers) replace
 * the manual ones while the linked profile matches the configured username and profile name.
 */
@Service
public class PlayerContextService {

    private static final Logger log = LoggerFactory.getLogger(PlayerContextService.class);

    private final SettingsService settings;
    private final LinkedProfileStore linked;
    private final ResourceRepository resources;
    private final ObjectMapper json;
    private final AtomicReference<Parsed<CollectionCatalog>> catalog = new AtomicReference<>();
    private final AtomicReference<Parsed<Set<String>>> perks = new AtomicReference<>();

    public PlayerContextService(SettingsService settings, LinkedProfileStore linked, ResourceRepository resources,
                                ObjectMapper json) {
        this.settings = settings;
        this.linked = linked;
        this.resources = resources;
        this.json = json;
    }

    private record Parsed<T>(String body, T value) {
    }

    public PlayerContext current() {
        Settings s = settings.current();
        HyFlipProperties.Player player = s.player();
        HyFlipProperties.Trading trading = s.trading();
        HyFlipProperties.Forge forge = s.forge();
        CollectionCatalog collections = collections();
        Optional<LinkedProfile> profile = linkedProfile();

        Map<String, Integer> tiers = new HashMap<>();
        player.collections().forEach((key, tier) -> {
            if (tier != null) {
                tiers.put(collections.resolve(key).orElse(key.toUpperCase(Locale.ROOT)), tier);
            }
        });
        profile.ifPresent(p -> tiers.putAll(p.collections()));
        Integer hotm = profile.map(LinkedProfile::hotmLevel).orElse(player.hotmLevel());
        Double quickForge = profile.map(LinkedProfile::quickForgeReduction).orElse(forge.quickForgeReduction());
        // The profile does not say how many slots are unlocked, only which hold a job.
        int slots = player.forgeSlots() != null ? player.forgeSlots()
                : Math.max(PlayerContext.DEFAULT_FORGE_SLOTS, profile.map(LinkedProfile::highestForgeSlot).orElse(0));
        String source = profile.isPresent() ? "PROFILE" : settings.source().name();

        boolean molten;
        String moltenSource;
        switch (forge.moltenForge()) {
            case ON -> {
                molten = true;
                moltenSource = "CONFIG";
            }
            case OFF -> {
                molten = false;
                moltenSource = "CONFIG";
            }
            default -> {
                Optional<Set<String>> active = activePerks();
                molten = active.map(p -> p.contains(ElectionPerks.MOLTEN_FORGE)).orElse(false);
                moltenSource = active.isPresent() ? "ELECTION" : "UNKNOWN";
            }
        }

        HyFlipProperties.AuctionFees ah = trading.auctionFees();
        Fees fees = new Fees(trading.bazaarTaxRate().doubleValue(), new Fees.AuctionFees(ah.smallRate(),
                ah.mediumRate(), ah.largeRate(), ah.mediumFrom(), ah.largeFrom(), ah.claimTaxRate(),
                ah.claimTaxFrom()));

        return new PlayerContext(hotm, slots, player.forgeSlots() != null, Map.copyOf(tiers), quickForge, molten,
                moltenSource, Optional.ofNullable(trading.budget()).map(BigDecimal::doubleValue).orElse(null),
                forge.nextSessionIn(), trading.priceSource(), fees, source, profile.isPresent(),
                profile.map(LinkedProfile::slayers).orElse(null));
    }

    /** The linked profile, if it was read for the configured username and profile name. */
    public Optional<LinkedProfile> linkedProfile() {
        HyFlipProperties.Player player = settings.current().player();
        return linked.profile()
                .filter(p -> p.username().equalsIgnoreCase(player.username()))
                .filter(p -> player.profile().isBlank() || p.cuteName().equalsIgnoreCase(player.profile().trim()));
    }

    /** Data-quality alert thresholds from the settings. */
    public com.hyflip.server.flipper.AlertRules rules() {
        HyFlipProperties.Alerts a = settings.current().alerts();
        return new com.hyflip.server.flipper.AlertRules(a.minAuctionSales(), a.wideSpread(), a.manipulationRatio());
    }

    /** Time zone for clock times (settings, else the computer's). */
    public java.time.ZoneId zone() {
        return settings.zone();
    }

    /** NPC Flipper limits from the settings. */
    public HyFlipProperties.Npc npcLimits() {
        return settings.current().npc();
    }

    /** Bazaar Flipper settings. */
    public HyFlipProperties.Bazaar bazaarSettings() {
        return settings.current().bazaar();
    }

    /** Collections from the stored resource; empty until the RESOURCES collector has run. */
    public CollectionCatalog collections() {
        return parsed("collections", catalog, body -> CollectionCatalog.parse(json.readTree(body)))
                .orElse(CollectionCatalog.EMPTY);
    }

    private Optional<Set<String>> activePerks() {
        return parsed("election", perks, body -> ElectionPerks.active(json.readTree(body)));
    }

    private <T> Optional<T> parsed(String resource, AtomicReference<Parsed<T>> cache,
                                   java.util.function.Function<String, T> parse) {
        Optional<String> body = resources.body(resource);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        Parsed<T> cached = cache.get();
        if (cached != null && cached.body().equals(body.get())) {
            return Optional.of(cached.value());
        }
        try {
            T value = parse.apply(body.get());
            cache.set(new Parsed<>(body.get(), value));
            return Optional.of(value);
        } catch (RuntimeException e) {
            log.warn("Could not parse stored {} resource: {}", resource, e.getMessage());
            return Optional.empty();
        }
    }
}
