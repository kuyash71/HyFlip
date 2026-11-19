package com.hyflip.server.source.coflnet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * One sold auction from Coflnet. Seller and buyer are deliberately not mapped; HyFlip does not
 * store player IDs.
 *
 * @param uuid             auction ID, the same form as Hypixel's {@code auction_id}
 * @param tag              SkyBlock item ID
 * @param highestBidAmount sale price of the whole stack
 * @param end              end time in UTC without zone, for example {@code 2026-11-18T05:30:08}
 * @param count            stack size
 * @param enchantments     enchantments on the item (empty for a clean item)
 * @param flattenedNbt     the rest of {@code ExtraAttributes} as strings
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SoldAuction(String uuid, String tag, long highestBidAmount, String end, boolean bin, int count,
                          List<Object> enchantments, Map<String, String> flattenedNbt) {
}
