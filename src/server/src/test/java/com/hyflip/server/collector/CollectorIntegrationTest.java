package com.hyflip.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyflip.server.mapping.ItemCatalogSync;
import com.hyflip.server.market.BazaarBook;
import com.hyflip.server.source.hypixel.AuctionsEndedResponse;
import com.hyflip.server.source.hypixel.BazaarResponse;
import com.hyflip.server.source.hypixel.ItemsResponse;
import com.hyflip.server.source.neu.NeuData;
import com.hyflip.server.source.neu.NeuParser;
import com.hyflip.server.support.DatabaseTest;
import com.hyflip.server.support.FakeHypixelApi;
import com.hyflip.server.support.Fixtures;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class CollectorIntegrationTest extends DatabaseTest {

    @Autowired FakeHypixelApi hypixel;
    @Autowired BazaarCollector bazaar;
    @Autowired AuctionsEndedCollector auctionsEnded;
    @Autowired ResourcesCollector resources;
    @Autowired CollectorScheduler scheduler;
    @Autowired ItemCatalogSync catalog;
    @Autowired JdbcClient jdbc;
    @Autowired BazaarBook book;

    @BeforeEach
    void reset() {
        hypixel.failure = null;
        hypixel.bazaar = Fixtures.json("hypixel/bazaar.json", BazaarResponse.class);
        hypixel.auctionsEnded = Fixtures.json("hypixel/auctions_ended.json", AuctionsEndedResponse.class);
        jdbc.sql("DELETE FROM bazaar_snapshot").update();
        jdbc.sql("DELETE FROM auction_sale").update();
        bazaar.reset();
    }

    @Test
    void storesBazaarSnapshotsOnlyWhenSomethingChanged() throws Exception {
        assertThat(bazaar.collect()).isEqualTo(5);
        assertThat(bazaar.collect()).as("same lastUpdated is skipped").isZero();

        hypixel.bazaar = withPrice(hypixel.bazaar, "REFINED_MITHRIL", 1.0, hypixel.bazaar.lastUpdated() + 60_000);
        assertThat(bazaar.collect()).as("only the changed product").isEqualTo(1);
        assertThat(count("bazaar_snapshot")).isEqualTo(6);
    }

    @Test
    void keepsTheBestOrdersOfEachBookInMemory() throws Exception {
        bazaar.collect();
        BazaarBook.Top top = book.top("INK_SACK-3").orElseThrow();
        assertThat(top.buyOrder()).isEqualTo(1.0);
        assertThat(top.buyOrderAmount()).isEqualTo(128_589);
        assertThat(top.sellOffer()).isEqualTo(11.7);
        assertThat(top.at()).isEqualTo(hypixel.bazaar.lastUpdated());
    }

    @Test
    void mapsBazaarIdsToCanonicalItems() throws Exception {
        bazaar.collect();
        assertThat(canonicalOf("BAZAAR", "ENCHANTMENT_SHARPNESS_6")).isEqualTo("SHARPNESS;6");
        assertThat(canonicalOf("BAZAAR", "INK_SACK:3")).isEqualTo("INK_SACK-3");
    }

    @Test
    void remapsShardsWhenNeuArrivesLate() throws Exception {
        bazaar.collect();
        assertThat(canonicalOf("BAZAAR", "SHARD_CRETAN_BULL")).isIn("SHARD_CRETAN_BULL",
                "ATTRIBUTE_SHARD_MYTHOLOGICAL_RESISTANCE;1");

        NeuData neu = new NeuParser(Fixtures.JSON).parse(Fixtures.neuRepo(), "test");
        catalog.registerNeu(neu);

        assertThat(canonicalOf("BAZAAR", "SHARD_CRETAN_BULL")).isEqualTo("ATTRIBUTE_SHARD_MYTHOLOGICAL_RESISTANCE;1");
        long rows = jdbc.sql("""
                SELECT count(*) FROM bazaar_snapshot s JOIN item i ON i.id = s.item_id
                WHERE i.canonical_id = 'ATTRIBUTE_SHARD_MYTHOLOGICAL_RESISTANCE;1'
                """).query(Long.class).single();
        assertThat(rows).as("history moved with the alias").isEqualTo(1);
    }

    @Test
    void storesEndedAuctionsOnce() throws Exception {
        assertThat(auctionsEnded.collect()).isEqualTo(2);
        assertThat(auctionsEnded.collect()).isZero();
        long mapped = jdbc.sql("SELECT count(*) FROM auction_sale WHERE item_id IS NOT NULL").query(Long.class).single();
        assertThat(mapped).isEqualTo(2);
    }

    @Test
    void storesResources() throws Exception {
        assertThat(resources.collect()).isEqualTo(3);
        Double npc = jdbc.sql("SELECT npc_sell_price FROM item WHERE canonical_id = 'REFINED_MITHRIL'")
                .query(Double.class).single();
        assertThat(npc).isEqualTo(1000.0);
        assertThat(count("resource_snapshot")).isEqualTo(2);
    }

    @Test
    void hypixelCatalogueItemsMissingFromNeuAreNotUnmapped() throws Exception {
        catalog.registerNeu(new NeuParser(Fixtures.JSON).parse(Fixtures.neuRepo(), "test"));
        hypixel.items = new ItemsResponse(true, 2, List.of(
                new ItemsResponse.Item("ADMIN_ONLY_THING", "Admin Thing", "MISC", "SPECIAL", null)));
        resources.collect();
        long unmapped = jdbc.sql("SELECT count(*) FROM unmapped_id WHERE source = 'HYPIXEL_ITEM'")
                .query(Long.class).single();
        assertThat(unmapped).isZero();
    }

    @Test
    void recordsFailedRunsAndBacksOff() {
        hypixel.failure = FakeHypixelApi.serverError();
        assertThat(scheduler.runOnce(bazaar)).isTrue();
        assertThat(scheduler.states().get("BAZAAR").consecutiveFailures()).isEqualTo(1);
        assertThat(scheduler.runOnce(bazaar)).as("backing off").isFalse();
        String status = jdbc.sql("SELECT status FROM collector_run WHERE collector = 'BAZAAR' ORDER BY id DESC LIMIT 1")
                .query(String.class).single();
        assertThat(status).isEqualTo("FAILED");
    }

    @Test
    void restartsDoNotRecordTheSameGapTwice() {
        long t0 = 1_900_000_000_000L;
        long hour = 3_600_000;
        jdbc.sql("DELETE FROM collection_gap WHERE collector = 'BAZAAR'").update();
        jdbc.sql("DELETE FROM collector_run WHERE collector = 'BAZAAR'").update();
        jdbc.sql("""
                INSERT INTO collector_run (collector, started_at, finished_at, status, rows_written)
                VALUES ('BAZAAR', :t, :t, 'OK', 1)
                """).param("t", t0).update();

        scheduler.detectGaps(t0 + hour);          // closed for an hour
        scheduler.detectGaps(t0 + hour + 60_000); // restarted a minute later, before any new success
        scheduler.detectGaps(t0 + 3 * hour);      // closed again
        List<Long> starts = jdbc.sql("SELECT gap_start FROM collection_gap WHERE collector = 'BAZAAR' ORDER BY gap_start")
                .query(Long.class).list();
        assertThat(starts).containsExactly(t0, t0 + hour);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private String canonicalOf(String source, String sourceId) {
        return jdbc.sql("""
                SELECT i.canonical_id FROM item_alias a JOIN item i ON i.id = a.item_id
                WHERE a.source = :s AND a.source_id = :id
                """).param("s", source).param("id", sourceId).query(String.class).single();
    }

    private static BazaarResponse withPrice(BazaarResponse r, String productId, double buyPrice, long lastUpdated) {
        Map<String, BazaarResponse.Product> products = new HashMap<>(r.products());
        BazaarResponse.Product p = products.get(productId);
        BazaarResponse.QuickStatus q = p.quickStatus();
        products.put(productId, new BazaarResponse.Product(productId, new BazaarResponse.QuickStatus(q.productId(),
                q.sellPrice(), q.sellVolume(), q.sellMovingWeek(), q.sellOrders(), buyPrice, q.buyVolume(),
                q.buyMovingWeek(), q.buyOrders())));
        return new BazaarResponse(true, lastUpdated, products);
    }
}
