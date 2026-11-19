package com.hyflip.server.api;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.market.BazaarBook;
import com.hyflip.server.source.neu.NeuRepository;
import com.hyflip.server.storage.BazaarRepository;
import com.hyflip.server.storage.ItemRepository;
import com.hyflip.server.support.DatabaseTest;
import com.hyflip.server.support.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bazaar Flipper over HTTP: order-to-order spreads from quick_status or the live book, the slower
 * side's volume, thin books.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"hyflip.bazaar.market-share=0.05", "hyflip.bazaar.thin-book=5",
        "hyflip.trading.price-source=INSTANT"})
class BazaarApiTest extends DatabaseTest {

    static final double TAX = 0.0125;

    @Autowired MockMvc mvc;
    @Autowired NeuRepository neu;
    @Autowired HyFlipProperties properties;
    @Autowired ItemRepository items;
    @Autowired BazaarRepository bazaar;
    @Autowired JdbcClient jdbc;
    @Autowired BazaarBook book;
    long now;

    @BeforeEach
    void setUp() throws IOException {
        Path root = properties.dataDir().resolve("neu");
        if (Files.notExists(root.resolve("COMMIT"))) {
            ForgeApiTest.copy(Fixtures.neuRepo(), root.resolve("repo"));
            Files.writeString(root.resolve("COMMIT"), "fixture");
        }
        neu.loadLocal();
        jdbc.sql("DELETE FROM bazaar_snapshot").update();
        book.replace(Map.of());
        now = System.currentTimeMillis();
        // Offers at 1,400, orders at 1,300; 70,000 insta-bought and 140,000 insta-sold a week.
        price("ENCHANTED_MITHRIL", now, 1_400, 1_300, 70_000, 140_000, 40, 50);
        // Wide spread but only two buy orders.
        price("REFINED_MITHRIL", now, 900_000, 700_000, 7_000, 7_000, 30, 2);
        // No sell offers: no spread to trade.
        price("MITHRIL_ORE", now, 0, 5, 700_000, 700_000, 0, 30);
    }

    @Test
    void ranksSpreadsByProfitPerDay() throws Exception {
        String mithril = "$.items[?(@.id == 'ENCHANTED_MITHRIL')]";
        String refined = "$.items[?(@.id == 'REFINED_MITHRIL')]";
        double net = 1_400 * (1 - TAX) - 1_300;
        mvc.perform(get("/api/v1/flips/bazaar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.sort").value("profitPerDay"))
                // Always order to order, even though the player prices with insta-buy / insta-sell.
                .andExpect(jsonPath("$.settings.priceSource").value("ORDER"))
                .andExpect(jsonPath(mithril + ".cost").value(hasItem(closeTo(1_300, 1e-9))))
                .andExpect(jsonPath(mithril + ".netProfit").value(hasItem(closeTo(net, 1e-9))))
                // Slower side: 70,000 a week = 10,000 a day; 5% of that.
                .andExpect(jsonPath(mithril + ".liquidity.dailyVolume").value(hasItem(closeTo(10_000, 1e-9))))
                .andExpect(jsonPath(mithril + ".runsPerDay").value(hasItem(closeTo(500, 1e-9))))
                .andExpect(jsonPath(mithril + ".profitPerDay").value(hasItem(closeTo(net * 500, 1e-6))))
                .andExpect(jsonPath(mithril + ".alerts[*]").isEmpty())
                .andExpect(jsonPath(refined + ".alerts[*].type").value(hasItem("THIN_BOOK")))
                .andExpect(jsonPath(refined + ".alerts[*].message").value(hasItem(startsWith("Only 2 buy orders and 30"))))
                .andExpect(jsonPath("$.items[*].id").value(not(hasItem("MITHRIL_ORE"))));
    }

    @Test
    void prefersTheLiveBookAndFlagsLoneOrders() throws Exception {
        // quick_status averages say 1,300 / 1,400; the best orders are 1,250 and 1,500. The best buy order
        // of refined mithril is a lone 700k order far above the next level at 500k.
        price("REFINED_MITHRIL", now + 1, 900_000, 500_000, 7_000, 7_000, 30, 30);
        book.replace(Map.of(
                "ENCHANTED_MITHRIL", new BazaarBook.Top(1_250, 64, 1_500, 640, now),
                "REFINED_MITHRIL", new BazaarBook.Top(700_000, 1, 900_000, 5, 500_000, 900_100, now),
                "MITHRIL_ORE", new BazaarBook.Top(5, 100, 0, 0, now)));
        double net = (1_500 - 0.1) * (1 - TAX) - (1_250 + 0.1);
        String mithril = "$.items[?(@.id == 'ENCHANTED_MITHRIL')]";
        mvc.perform(get("/api/v1/flips/bazaar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(mithril + ".cost").value(hasItem(closeTo(1_250.1, 1e-9))))
                .andExpect(jsonPath(mithril + ".netProfit").value(hasItem(closeTo(net, 1e-9))))
                // Same quick_status as before: no recent change, nothing stale.
                .andExpect(jsonPath(mithril + ".alerts[*]").isEmpty())
                .andExpect(jsonPath("$.items[?(@.id == 'REFINED_MITHRIL')].alerts[*].message")
                        .value(hasItem("The best buy order (700k for 1) stands far from the next one (500k)")))
                .andExpect(jsonPath("$.items[*].id").value(not(hasItem("MITHRIL_ORE"))));
        mvc.perform(get("/api/v1/flips/bazaar/ENCHANTED_MITHRIL"))
                .andExpect(jsonPath("$.guide.steps[0]").value(startsWith("Place a buy order for 500 × ")))
                .andExpect(jsonPath("$.guide.steps[0]").value(org.hamcrest.Matchers.containsString("at 1.25k each")))
                .andExpect(jsonPath("$.guide.logic").value(org.hamcrest.Matchers.containsString("best orders from the latest poll")));
    }

    @Test
    void detailGuidesBothOrders() throws Exception {
        mvc.perform(get("/api/v1/flips/bazaar/ENCHANTED_MITHRIL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guide.steps[0]").value(startsWith("Place a buy order for 500 × ")))
                .andExpect(jsonPath("$.guide.steps[1]").value(startsWith("When it fills, place a sell offer at 1.4k each")))
                .andExpect(jsonPath("$.guide.logic").value(org.hamcrest.Matchers.containsString(" is a spread of ")));
        mvc.perform(get("/api/v1/flips/bazaar/MITHRIL_ORE")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/flips/bazaar/NOT_AN_ITEM")).andExpect(status().isNotFound());
    }

    private void price(String id, long at, double buy, double sell, long buyWeek, long sellWeek, int buyOrders,
                       int sellOrders) {
        long itemId = items.ensureItem(id);
        bazaar.insert(List.of(new BazaarRepository.Snapshot(at, itemId, buy, sell, 100_000, 100_000, buyWeek, sellWeek,
                buyOrders, sellOrders, "HYPIXEL")));
    }
}
