package com.hyflip.server.mapping;

import com.hyflip.server.source.hypixel.AuctionItem;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure rules that turn source-specific IDs into canonical HyFlip item IDs.
 *
 * <p>Canonical IDs are NEU internal names, because NEU names every variant uniformly and its
 * recipes use them: {@code REFINED_MITHRIL}, {@code SHARPNESS;6} (enchanted book),
 * {@code GOLDEN_DRAGON;4} (legendary pet), {@code MUSIC_RUNE;1}, {@code INK_SACK-3} (Bazaar's
 * {@code INK_SACK:3}). Verified against live Bazaar and NEU data on 2026-11-17: these rules map
 * 97% of Bazaar products to an existing NEU item.
 */
public final class ItemIds {

    /** Pet rarities in NEU's tier-index order. */
    static final List<String> PET_TIERS = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");

    private static final Pattern BAZAAR_ENCHANTMENT = Pattern.compile("ENCHANTMENT_(.+)_(\\d+)");
    private static final Pattern PET_TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([A-Z0-9_]+)\"");
    private static final Pattern PET_TIER = Pattern.compile("\"tier\"\\s*:\\s*\"([A-Z]+)\"");

    private ItemIds() {
    }

    /**
     * @param productId    Bazaar product ID
     * @param bazaarStocks NEU's {@code constants/bazaarstocks.json} (Bazaar stock to NEU ID), used
     *                     for shards; may be empty before NEU is loaded
     */
    public static String fromBazaar(String productId, Map<String, String> bazaarStocks) {
        String stock = bazaarStocks.get(productId);
        if (stock != null) {
            return stock;
        }
        Matcher m = BAZAAR_ENCHANTMENT.matcher(productId);
        if (m.matches()) {
            return m.group(1) + ";" + m.group(2);
        }
        return fromHypixelItemId(productId);
    }

    /** Hypixel item IDs from the items resource; vanilla damage values use {@code -} in NEU. */
    public static String fromHypixelItemId(String id) {
        return id.replace(':', '-');
    }

    /** Canonical ID of an auctioned item, using its attributes for books, pets and runes. */
    public static String fromAuction(AuctionItem item) {
        Map<String, Object> attributes = item.attributes();
        switch (item.id()) {
            case "ENCHANTED_BOOK" -> {
                if (attributes.get("enchantments") instanceof Map<?, ?> enchants && enchants.size() == 1) {
                    Map.Entry<?, ?> e = enchants.entrySet().iterator().next();
                    if (e.getValue() instanceof Number level) {
                        return e.getKey().toString().toUpperCase(Locale.ROOT) + ";" + level.intValue();
                    }
                }
            }
            case "PET" -> {
                if (attributes.get("petInfo") instanceof String info) {
                    Matcher type = PET_TYPE.matcher(info);
                    Matcher tier = PET_TIER.matcher(info);
                    if (type.find() && tier.find() && PET_TIERS.contains(tier.group(1))) {
                        return type.group(1) + ";" + PET_TIERS.indexOf(tier.group(1));
                    }
                }
            }
            case "RUNE", "UNIQUE_RUNE" -> {
                if (attributes.get("runes") instanceof Map<?, ?> runes && runes.size() == 1) {
                    Map.Entry<?, ?> e = runes.entrySet().iterator().next();
                    if (e.getValue() instanceof Number level) {
                        return e.getKey().toString().toUpperCase(Locale.ROOT) + "_RUNE;" + level.intValue();
                    }
                }
            }
            default -> {
            }
        }
        return fromHypixelItemId(item.id());
    }
}
