package com.hyflip.server.mapping;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether an auctioned item is "clean": in the state a craft or forge produces it, without the
 * upgrades players add later (stars, recombobulator, hot potato books, enchantments, gemstones,
 * scrolls, runes, ...). Upgraded copies sell for far more than a fresh one, so prices of crafted
 * outputs are taken from clean copies only. Reforges are allowed: most add little value and nearly
 * every listing has one.
 *
 * <p>Pets and enchanted books are always clean here: their value-defining attributes are already
 * part of the canonical ID ({@code GOLDEN_DRAGON;4}, {@code LOOTING;5}).
 */
public final class ItemCondition {

    /** {@code ExtraAttributes} keys that add value on top of the base item. */
    static final Set<String> UPGRADES = Set.of(
            "enchantments", "rarity_upgrades", "hot_potato_count", "upgrade_level", "dungeon_item_level", "gems",
            "power_ability_scroll", "ability_scroll", "art_of_war_count", "runes", "ethermerge", "dye_item", "skin",
            "talisman_enrichment", "mana_disintegrator_count", "jalapeno_count", "wood_singularity_count",
            "farming_for_dummies_count", "divan_powder_coating", "polarvoid", "attributes", "drill_part_engine",
            "drill_part_fuel_tank", "drill_part_upgrade_module", "stats_book", "unlocked_slots");

    /** Coflnet flattens gemstone slots to keys like {@code SAPPHIRE_0} or {@code COMBAT_0.uuid}. */
    private static final Pattern GEM_SLOT = Pattern.compile("[A-Z]+_\\d+(\\..*)?");

    private ItemCondition() {
    }

    /**
     * @param hypixelId  {@code ExtraAttributes.id}
     * @param attributes the decoded {@code ExtraAttributes} compound
     */
    public static boolean isClean(String hypixelId, Map<String, ?> attributes) {
        if (exempt(hypixelId)) {
            return true;
        }
        for (Map.Entry<String, ?> e : attributes.entrySet()) {
            if (UPGRADES.contains(e.getKey()) && present(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Coflnet's form: enchantments as a list, the rest of {@code ExtraAttributes} flattened to
     * strings ({@code hpc} is the hot potato count).
     */
    public static boolean isCleanFlattened(String tag, Collection<?> enchantments, Map<String, String> flattened) {
        if (exempt(tag)) {
            return true;
        }
        if (enchantments != null && !enchantments.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> e : flattened.entrySet()) {
            String key = e.getKey();
            if (key.equals("hpc") || key.startsWith("RUNE_") || GEM_SLOT.matcher(key).matches()
                    || (UPGRADES.contains(key) && present(e.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean exempt(String id) {
        return id == null || id.equals("PET") || id.startsWith("PET_") || id.equals("ENCHANTED_BOOK")
                || id.startsWith("ENCHANTMENT_");
    }

    private static boolean present(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        String s = value.toString();
        return !s.isEmpty() && !s.equals("0");
    }
}
