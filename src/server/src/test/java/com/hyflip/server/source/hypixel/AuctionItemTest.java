package com.hyflip.server.source.hypixel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyflip.server.mapping.ItemIds;
import com.hyflip.server.support.Fixtures;
import com.hyflip.server.support.NbtWriter;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuctionItemTest {

    @Test
    void decodesRealRuneItemBytes() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, String> samples = Fixtures.json("hypixel/item_bytes.json", Map.class);
        AuctionItem rune = AuctionItem.decode(samples.get("rune"));
        assertThat(rune.id()).isEqualTo("RUNE");
        assertThat(rune.count()).isEqualTo(1);
        assertThat(ItemIds.fromAuction(rune)).isEqualTo("MUSIC_RUNE;1");
    }

    @Test
    void decodesRealEndedAuctions() throws Exception {
        AuctionsEndedResponse ended = Fixtures.json("hypixel/auctions_ended.json", AuctionsEndedResponse.class);
        assertThat(ended.auctions()).hasSize(2);
        for (AuctionsEndedResponse.EndedAuction a : ended.auctions()) {
            AuctionItem item = AuctionItem.decode(a.itemBytes());
            assertThat(item.id()).isNotBlank();
        }
        AuctionItem pet = AuctionItem.decode(ended.auctions().stream()
                .map(AuctionsEndedResponse.EndedAuction::itemBytes)
                .filter(b -> {
                    try {
                        return AuctionItem.decode(b).id().equals("PET");
                    } catch (IOException e) {
                        return false;
                    }
                }).findFirst().orElseThrow());
        assertThat(ItemIds.fromAuction(pet)).matches("[A-Z_]+;[0-5]");
    }

    @Test
    void readsStackCount() throws Exception {
        AuctionItem item = AuctionItem.decode(NbtWriter.itemBytes("ENCHANTED_DIAMOND", 64, Map.of()));
        assertThat(item.count()).isEqualTo(64);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> AuctionItem.decode("bm90IG5idA==")).isInstanceOf(IOException.class);
    }
}
