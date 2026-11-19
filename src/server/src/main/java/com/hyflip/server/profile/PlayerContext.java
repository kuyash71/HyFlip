package com.hyflip.server.profile;

import com.hyflip.server.engine.Fees;
import com.hyflip.server.engine.PriceSource;
import java.time.Duration;
import java.util.Map;

/**
 * Effective personal context and trading settings used by the flippers. Comes from the config
 * today; the profile API fills it later (M6).
 *
 * @param hotmLevel           HOTM level, null if unknown
 * @param forgeSlots          forge slots
 * @param forgeSlotsKnown     false if {@code forgeSlots} is the default, not the player's value
 * @param collections         collection tiers by collection ID
 * @param quickForgeReduction Quick Forge reduction as a fraction, null if unknown
 * @param moltenForge         whether Cole's Molten Forge perk is active
 * @param moltenForgeSource   ELECTION (detected), CONFIG (forced) or UNKNOWN (no election data yet)
 * @param budget              coins available, null for no limit
 * @param nextSessionIn       usual time until the player can refill a slot
 * @param priceSource         how Bazaar prices are taken
 * @param fees                sell fees
 * @param source              where the personal values came from: PROFILE (linked profile), APP
 *                            (settings saved in the app) or CONFIG (config.yaml)
 * @param collectionsComplete true when {@code collections} lists every unlocked tier (a linked profile), so a
 *                            collection missing from it is at tier 0; false when it holds only what was typed
 * @param slayers             slayer level by boss (zombie, spider, wolf, enderman, blaze, vampire), null if unknown
 */
public record PlayerContext(Integer hotmLevel, int forgeSlots, boolean forgeSlotsKnown,
                            Map<String, Integer> collections, Double quickForgeReduction, boolean moltenForge,
                            String moltenForgeSource, Double budget, Duration nextSessionIn,
                            PriceSource priceSource, Fees fees, String source, boolean collectionsComplete,
                            Map<String, Integer> slayers) {

    /** Without profile-only facts: collections as typed, slayers unknown. */
    public PlayerContext(Integer hotmLevel, int forgeSlots, boolean forgeSlotsKnown, Map<String, Integer> collections,
                         Double quickForgeReduction, boolean moltenForge, String moltenForgeSource, Double budget,
                         Duration nextSessionIn, PriceSource priceSource, Fees fees, String source) {
        this(hotmLevel, forgeSlots, forgeSlotsKnown, collections, quickForgeReduction, moltenForge, moltenForgeSource,
                budget, nextSessionIn, priceSource, fees, source, false, null);
    }

    /** Forge slots before HOTM upgrades. */
    public static final int DEFAULT_FORGE_SLOTS = 2;

    public PlayerContext withHotmLevel(Integer level) {
        return new PlayerContext(level, forgeSlots, forgeSlotsKnown, collections, quickForgeReduction, moltenForge,
                moltenForgeSource, budget, nextSessionIn, priceSource, fees, source, collectionsComplete, slayers);
    }

    public PlayerContext withPriceSource(PriceSource source) {
        return new PlayerContext(hotmLevel, forgeSlots, forgeSlotsKnown, collections, quickForgeReduction,
                moltenForge, moltenForgeSource, budget, nextSessionIn, source, fees, this.source, collectionsComplete, slayers);
    }
}
