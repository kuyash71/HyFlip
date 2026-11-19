package com.hyflip.server.source.hypixel;

import com.hyflip.server.mapping.ItemCondition;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The item inside an auction, decoded from {@code item_bytes}.
 *
 * @param id         SkyBlock item ID ({@code ExtraAttributes.id}), for example {@code PET}
 * @param count      stack size
 * @param attributes the full {@code ExtraAttributes} compound (enchantments, petInfo, runes, ...)
 */
public record AuctionItem(String id, int count, Map<String, Object> attributes) {

    @SuppressWarnings("unchecked")
    public static AuctionItem decode(String itemBytes) throws IOException {
        Map<String, Object> root = Nbt.readBase64Gzip(itemBytes);
        if (!(root.get("i") instanceof List<?> items) || items.isEmpty()
                || !(items.get(0) instanceof Map<?, ?> first)) {
            throw new IOException("item_bytes has no item");
        }
        Map<String, Object> item = (Map<String, Object>) first;
        int count = item.get("Count") instanceof Number n ? n.intValue() : 1;
        Map<String, Object> tag = item.get("tag") instanceof Map<?, ?> t ? (Map<String, Object>) t : Map.of();
        Map<String, Object> extra = tag.get("ExtraAttributes") instanceof Map<?, ?> e
                ? (Map<String, Object>) e : Map.of();
        if (!(extra.get("id") instanceof String id)) {
            throw new IOException("item has no SkyBlock ID");
        }
        return new AuctionItem(id, Math.max(count, 1), extra);
    }

    /** True if the item carries no value-adding upgrades; see {@link ItemCondition}. */
    public boolean clean() {
        return ItemCondition.isClean(id, attributes);
    }
}
