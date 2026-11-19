package com.hyflip.server.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyflip.server.source.hypixel.AuctionItem;
import com.hyflip.server.support.NbtWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItemIdsTest {

    @Test
    void bazaarIdsMapToNeuNames() {
        Map<String, String> stocks = Map.of("SHARD_CRETAN_BULL", "ATTRIBUTE_SHARD_MYTHOLOGICAL_RESISTANCE;1");
        assertThat(ItemIds.fromBazaar("REFINED_MITHRIL", stocks)).isEqualTo("REFINED_MITHRIL");
        assertThat(ItemIds.fromBazaar("ENCHANTMENT_SHARPNESS_6", stocks)).isEqualTo("SHARPNESS;6");
        assertThat(ItemIds.fromBazaar("ENCHANTMENT_ULTIMATE_WISE_1", stocks)).isEqualTo("ULTIMATE_WISE;1");
        assertThat(ItemIds.fromBazaar("INK_SACK:3", stocks)).isEqualTo("INK_SACK-3");
        assertThat(ItemIds.fromBazaar("SHARD_CRETAN_BULL", stocks))
                .isEqualTo("ATTRIBUTE_SHARD_MYTHOLOGICAL_RESISTANCE;1");
        assertThat(ItemIds.fromBazaar("SHARD_CRETAN_BULL", Map.of())).isEqualTo("SHARD_CRETAN_BULL");
    }

    @Test
    void singleEnchantmentBooksUseEnchantmentAndLevel() throws Exception {
        AuctionItem book = AuctionItem.decode(NbtWriter.itemBytes("ENCHANTED_BOOK", 1,
                Map.of("enchantments", Map.of("sharpness", 6))));
        assertThat(ItemIds.fromAuction(book)).isEqualTo("SHARPNESS;6");
    }

    @Test
    void multiEnchantmentBooksStayGeneric() throws Exception {
        AuctionItem book = AuctionItem.decode(NbtWriter.itemBytes("ENCHANTED_BOOK", 1,
                Map.of("enchantments", Map.of("sharpness", 6, "smite", 7))));
        assertThat(ItemIds.fromAuction(book)).isEqualTo("ENCHANTED_BOOK");
    }

    @Test
    void petsUseTypeAndTierIndex() throws Exception {
        AuctionItem pet = AuctionItem.decode(NbtWriter.itemBytes("PET", 1,
                Map.of("petInfo", "{\"type\":\"GOLDEN_DRAGON\",\"active\":false,\"tier\":\"LEGENDARY\"}")));
        assertThat(ItemIds.fromAuction(pet)).isEqualTo("GOLDEN_DRAGON;4");
    }

    @Test
    void plainItemsKeepTheirId() throws Exception {
        AuctionItem item = AuctionItem.decode(NbtWriter.itemBytes("HYPERION", 1, Map.of()));
        assertThat(ItemIds.fromAuction(item)).isEqualTo("HYPERION");
    }
}
