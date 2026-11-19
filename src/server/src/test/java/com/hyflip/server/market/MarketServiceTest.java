package com.hyflip.server.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.hyflip.server.engine.Market;
import com.hyflip.server.storage.AuctionRepository;
import com.hyflip.server.storage.BazaarRepository;
import com.hyflip.server.storage.ItemRepository;
import com.hyflip.server.storage.PriceRepository;
import com.hyflip.server.support.DatabaseTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MarketServiceTest extends DatabaseTest {

    static final long NOW = 1_800_000_000_000L;
    static final long HOUR = 3_600_000;

    @Autowired PriceRepository prices;
    @Autowired ItemRepository items;
    @Autowired BazaarRepository bazaar;
    @Autowired AuctionRepository auctions;
    @Autowired JdbcClient jdbc;

    MarketService markets;
    BazaarBook book;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM auction_sale").update();
        jdbc.sql("DELETE FROM auction_lowest_bin").update();
        book = new BazaarBook();
        markets = new MarketService(prices, book, java.time.Clock.fixed(java.time.Instant.ofEpochMilli(NOW),
                java.time.ZoneOffset.UTC));
    }

    @Test
    void readsBazaarAsOfAPastMoment() {
        long id = items.ensureItem("MARKET_TEST_BAZAAR");
        bazaar.insert(List.of(snapshot(id, NOW - 2 * HOUR, 10), snapshot(id, NOW - HOUR, 20), snapshot(id, NOW, 30)));
        assertThat(markets.now().bazaar("MARKET_TEST_BAZAAR").orElseThrow().buyPrice()).isEqualTo(30);
        assertThat(markets.at(NOW - HOUR - 1).bazaar("MARKET_TEST_BAZAAR").orElseThrow().buyPrice()).isEqualTo(10);
        assertThat(markets.at(NOW - 3 * HOUR).bazaar("MARKET_TEST_BAZAAR")).isEmpty();
        assertThat(markets.now().bazaar("NOT_STORED")).isEmpty();
    }

    @Test
    void liveViewUsesTheBestOrdersOfAFreshBook() {
        long id = items.ensureItem("MARKET_TEST_LIVE");
        bazaar.insert(List.of(snapshot(id, NOW - 60_000, 30)));
        book.replace(java.util.Map.of("MARKET_TEST_LIVE", new BazaarBook.Top(25, 10, 40, 5, NOW)));
        Market.BazaarQuote live = markets.now().bazaar("MARKET_TEST_LIVE").orElseThrow();
        assertThat(live.buyPrice()).as("insta-buy = best sell offer").isEqualTo(40);
        assertThat(live.sellPrice()).as("insta-sell = best buy order").isEqualTo(25);
        assertThat(live.live()).isTrue();
        assertThat(live.buyMovingWeek()).as("volumes stay from quick_status").isEqualTo(7);

        Market.BazaarQuote stored = markets.at(NOW).bazaar("MARKET_TEST_LIVE").orElseThrow();
        assertThat(stored.buyPrice()).isEqualTo(30);
        assertThat(stored.live()).isFalse();

        book.replace(java.util.Map.of("MARKET_TEST_LIVE", new BazaarBook.Top(25, 10, 40, 5, NOW - 2 * HOUR)));
        assertThat(markets.now().bazaar("MARKET_TEST_LIVE").orElseThrow().live()).as("stale book ignored").isFalse();
    }

    @Test
    void weeklyAveragesWeighEachHourOnce() {
        long id = items.ensureItem("MARKET_TEST_AVERAGE");
        // Hour 1: many changes around 10; hour 2: one row at 40. The hour average is 25, not ~13.
        bazaar.insert(List.of(snapshot(id, NOW - 2 * HOUR, 10), snapshot(id, NOW - 2 * HOUR + 1_000, 10),
                snapshot(id, NOW - 2 * HOUR + 2_000, 10), snapshot(id, NOW - HOUR + 5_000, 40)));
        assertThat(markets.now().bazaarAverage("MARKET_TEST_AVERAGE")).isEmpty();
        markets.refreshAverages();
        var avg = markets.now().bazaarAverage("MARKET_TEST_AVERAGE").orElseThrow();
        assertThat(avg.buyPrice()).isEqualTo(25);
        assertThat(avg.days()).isBetween(2.0 / 24, 3.0 / 24);
        assertThat(markets.at(NOW).bazaarAverage("MARKET_TEST_AVERAGE")).isEmpty();
    }

    @Test
    void weeklyAveragesComputeThemselvesInTheBackground() throws InterruptedException {
        long id = items.ensureItem("MARKET_TEST_BACKGROUND");
        bazaar.insert(List.of(snapshot(id, NOW - 2 * HOUR, 10), snapshot(id, NOW - HOUR + 5_000, 40)));
        // Nobody calls refreshAverages: the first read starts it.
        assertThat(markets.now().bazaarAverage("MARKET_TEST_BACKGROUND")).isEmpty();
        long deadline = System.currentTimeMillis() + 10_000;
        while (markets.now().bazaarAverage("MARKET_TEST_BACKGROUND").isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(markets.now().bazaarAverage("MARKET_TEST_BACKGROUND")).isPresent();
    }

    @Test
    void weeklyAveragesReadOnlyNewHours() {
        long id = items.ensureItem("MARKET_TEST_INCREMENTAL");
        bazaar.insert(List.of(snapshot(id, NOW - 2 * HOUR, 10), snapshot(id, NOW - HOUR + 5_000, 40)));
        markets.refreshAverages();
        assertThat(markets.now().bazaarAverage("MARKET_TEST_INCREMENTAL").orElseThrow().buyPrice()).isEqualTo(25);

        // The current hour is read on every update.
        bazaar.insert(List.of(snapshot(id, NOW, 70)));
        markets.updateAverages();
        assertThat(markets.now().bazaarAverage("MARKET_TEST_INCREMENTAL").orElseThrow().buyPrice()).isEqualTo(40);

        // A finished hour is kept: a late row there shows only after a rebuild.
        bazaar.insert(List.of(snapshot(id, NOW - 2 * HOUR + 3_000, 100)));
        markets.updateAverages();
        assertThat(markets.now().bazaarAverage("MARKET_TEST_INCREMENTAL").orElseThrow().buyPrice()).isEqualTo(40);
        markets.invalidateAverages();
        markets.updateAverages();
        // Hours: (10 + 100) / 2, 40, 70.
        assertThat(markets.now().bazaarAverage("MARKET_TEST_INCREMENTAL").orElseThrow().buyPrice()).isEqualTo(55);
    }

    @Test
    void lowestBinCountsOnlyWhenSeenInTheLatestScan() {
        long sword = items.ensureItem("MARKET_TEST_OLD_BIN");
        long other = items.ensureItem("MARKET_TEST_NEW_BIN");
        auctions.insertLowestBins(List.of(new AuctionRepository.LowestBin(NOW - 2 * HOUR, sword, 41_000_000, 15),
                new AuctionRepository.LowestBin(NOW - HOUR, other, 5, 1)));
        // The latest scan (NOW - HOUR) had no clean listing of the sword: its older BIN is outdated.
        assertThat(markets.now().auction("MARKET_TEST_OLD_BIN")).isEmpty();
        assertThat(markets.now().auction("MARKET_TEST_NEW_BIN").orElseThrow().lowestBin()).isEqualTo(5);
        assertThat(markets.at(NOW - 90 * 60_000).auction("MARKET_TEST_OLD_BIN").orElseThrow().lowestBin())
                .isEqualTo(41_000_000);
    }

    @Test
    void auctionPricesComeFromCleanCopiesOnly() {
        long id = items.ensureItem("MARKET_TEST_SWORD");
        auctions.insertSales(List.of(
                new AuctionRepository.Sale("c1", NOW - HOUR, id, 15_000_000, true, 1, "HYPIXEL", true),
                new AuctionRepository.Sale("c2", NOW - HOUR, id, 14_000_000, true, 1, "COFLNET", true),
                new AuctionRepository.Sale("d1", NOW - HOUR, id, 44_000_000, true, 1, "HYPIXEL", false),
                new AuctionRepository.Sale("u1", NOW - HOUR, id, 90_000_000, true, 1, "HYPIXEL", null)));
        Market.AuctionQuote q = markets.now().auction("MARKET_TEST_SWORD").orElseThrow();
        assertThat(q.sales()).isEqualTo(2);
        assertThat(q.medianSale()).isEqualTo(14_500_000);
        assertThat(q.listings()).isZero();
    }

    @Test
    void auctionQuoteUsesPerUnitMedianAndHistoryCoverage() {
        long id = items.ensureItem("MARKET_TEST_PET");
        auctions.insertLowestBins(List.of(new AuctionRepository.LowestBin(NOW - HOUR, id, 1_000, 3)));
        // Six hours of history: three sales, one of them a stack of 2 (per-unit 400).
        auctions.insertSales(List.of(
                new AuctionRepository.Sale("m1", NOW - 6 * HOUR, id, 900, true, 1, "HYPIXEL"),
                new AuctionRepository.Sale("m2", NOW - 2 * HOUR, id, 800, true, 2, "HYPIXEL"),
                new AuctionRepository.Sale("m3", NOW - HOUR, id, 1_100, true, 1, "HYPIXEL")));
        Market.AuctionQuote q = markets.now().auction("MARKET_TEST_PET").orElseThrow();
        assertThat(q.lowestBin()).isEqualTo(1_000);
        assertThat(q.lowestBinAt()).isEqualTo(NOW - HOUR);
        assertThat(q.medianSale()).isEqualTo(900);
        assertThat(q.salesPerDay()).isCloseTo(4 / 0.25, within(1e-9));
        assertThat(q.sales()).isEqualTo(3);
        assertThat(q.listings()).isEqualTo(3);
    }

    @Test
    void median() {
        assertThat(MarketService.median(new double[0])).isNull();
        assertThat(MarketService.median(new double[] {1, 2, 10})).isEqualTo(2);
        assertThat(MarketService.median(new double[] {1, 2, 4, 10})).isEqualTo(3);
    }

    private static BazaarRepository.Snapshot snapshot(long itemId, long at, double price) {
        return new BazaarRepository.Snapshot(at, itemId, price, price - 1, 1, 1, 7, 7, 1, 1, "HYPIXEL");
    }

    @Test
    void dailyVolatilityScalesHourlySwings() {
        assertThat(MarketService.dailyVolatility(null)).isNull();
        assertThat(MarketService.dailyVolatility(new double[] {1, 4, 0, 0})).isNull();
        // Alternating +r, -r: mean 0, sample sd = r·sqrt(n/(n-1)).
        double r = Math.log(1.01);
        double[] w = {1, 6, 0, 6 * r * r};
        assertThat(MarketService.dailyVolatility(w)).isCloseTo(r * Math.sqrt(6.0 / 5) * Math.sqrt(24),
                org.assertj.core.api.Assertions.within(1e-9));
    }
}
