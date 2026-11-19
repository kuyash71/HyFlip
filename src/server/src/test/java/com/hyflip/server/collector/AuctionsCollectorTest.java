package com.hyflip.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyflip.server.source.hypixel.AuctionsPage;
import com.hyflip.server.support.DatabaseTest;
import com.hyflip.server.support.FakeHypixelApi;
import com.hyflip.server.support.NbtWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "hyflip.collection.tracked-auction-items=HYPERION")
class AuctionsCollectorTest extends DatabaseTest {

    @Autowired FakeHypixelApi hypixel;
    @Autowired AuctionsCollector auctions;
    @Autowired JdbcClient jdbc;

    @Test
    void recordsLowestCleanBinPerUnitOfTrackedItemsAcrossPages() throws Exception {
        String hyperion = NbtWriter.itemBytes("HYPERION", 1, Map.of());
        String other = NbtWriter.itemBytes("DIRT", 1, Map.of());
        String upgraded = NbtWriter.itemBytes("HYPERION", 1, Map.of("rarity_upgrades", 1));
        hypixel.auctionPages = List.of(
                new AuctionsPage(true, 0, 2, 4, 1000, List.of(
                        new AuctionsPage.ActiveAuction("a", true, 900_000_000, 0, hyperion),
                        new AuctionsPage.ActiveAuction("b", false, 1, 0, hyperion), // auction, not BIN
                        new AuctionsPage.ActiveAuction("c", true, 5, 0, other))),
                new AuctionsPage(true, 1, 2, 4, 1000, List.of(
                        new AuctionsPage.ActiveAuction("d", true, 850_000_000, 0, hyperion),
                        // Recombobulated: cheaper here, but not what a fresh craft is worth.
                        new AuctionsPage.ActiveAuction("e", true, 800_000_000, 0, upgraded))));

        assertThat(auctions.collect()).isEqualTo(1);
        Map<String, Object> row = jdbc.sql("""
                SELECT b.price, b.listings FROM auction_lowest_bin b JOIN item i ON i.id = b.item_id
                WHERE i.canonical_id = 'HYPERION'
                """).query().singleRow();
        assertThat(((Number) row.get("price")).doubleValue()).isEqualTo(850_000_000.0);
        assertThat(((Number) row.get("listings")).intValue()).isEqualTo(2);
    }
}
