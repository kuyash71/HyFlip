package com.hyflip.server.source.hypixel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** {@code GET /v2/resources/skyblock/items}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemsResponse(boolean success, long lastUpdated, List<Item> items) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    /**
     * @param itemModel  the item's model in the SkyBlock resource pack, such as {@code hypixel_skyblock:item/x}
     * @param material   the 1.8 item it is drawn as without the pack ({@code INK_SACK})
     * @param durability its 1.8 damage value ({@code 4} for lapis), null for 0
     * @param glowing    true if it shows the enchantment glint
     */
    public record Item(String id, String name, String category, String tier,
                       @JsonProperty("npc_sell_price") Double npcSellPrice,
                       @JsonProperty("item_model") String itemModel, String material, Integer durability,
                       Boolean glowing) {

        public Item(String id, String name, String category, String tier, Double npcSellPrice) {
            this(id, name, category, tier, npcSellPrice, null, null, null, null);
        }
    }
}
