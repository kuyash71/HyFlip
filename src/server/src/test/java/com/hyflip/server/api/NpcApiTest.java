package com.hyflip.server.api;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.source.neu.NeuRepository;
import com.hyflip.server.storage.BazaarRepository;
import com.hyflip.server.storage.ItemRepository;
import com.hyflip.server.support.DatabaseTest;
import com.hyflip.server.support.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** NPC Flipper over HTTP: shop → Bazaar capped by the daily limit, Bazaar → NPC capped by market share. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"hyflip.npc.daily-limit=640", "hyflip.npc.market-share=0.05"})
class NpcApiTest extends DatabaseTest {

    static final double TAX = 0.0125;

    @Autowired MockMvc mvc;
    @Autowired NeuRepository neu;
    @Autowired HyFlipProperties properties;
    @Autowired ItemRepository items;
    @Autowired BazaarRepository bazaar;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() throws IOException {
        Path root = properties.dataDir().resolve("neu");
        if (Files.notExists(root.resolve("COMMIT"))) {
            ForgeApiTest.copy(Fixtures.neuRepo(), root.resolve("repo"));
            Files.writeString(root.resolve("COMMIT"), "fixture");
        }
        neu.loadLocal();
        jdbc.sql("DELETE FROM bazaar_snapshot").update();
        jdbc.sql("UPDATE item SET npc_sell_price = NULL").update();
        long now = System.currentTimeMillis();
        // Zog sells this for 50k; it sells on the Bazaar for 80k, 7,000 traded a day.
        price("PET_ITEM_ALL_SKILLS_BOOST_COMMON", now, 82_000, 80_000, 24_500, 24_500);
        // Bought with buy orders at 10, NPCs pay 12; 140,000 traded a day. Twenty minutes ago the top buy
        // order was 7, so the price jumped 43%.
        price("ENCHANTED_MITHRIL", now - 20 * 60_000, 11, 7, 490_000, 490_000);
        price("ENCHANTED_MITHRIL", now, 11, 10, 490_000, 490_000);
        npc("ENCHANTED_MITHRIL", 12);
        // NPCs pay less than the Bazaar: not a flip.
        price("REFINED_MITHRIL", now, 760_000, 745_000, 1000, 1000);
        npc("REFINED_MITHRIL", 1000);
    }

    @Test
    void ranksBothRoutesByProfitPerDay() throws Exception {
        String shop = "$.items[?(@.id == 'shop:ZOG_NPC:PET_ITEM_ALL_SKILLS_BOOST_COMMON')]";
        String sell = "$.items[?(@.id == 'sell:ENCHANTED_MITHRIL')]";
        double shopNet = 82_000 * (1 - TAX) - 50_000;
        mvc.perform(get("/api/v1/flips/npc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.sort").value("profitPerDay"))
                .andExpect(jsonPath(shop + ".name").value(hasItem("PET_ITEM_ALL_SKILLS_BOOST_COMMON · from Zog")))
                .andExpect(jsonPath(shop + ".netProfit").value(hasItem(closeTo(shopNet, 0.01))))
                // Sell offers fill as others insta-buy: 3,500 a day; 5% of that = 175, below the 640 shop limit.
                .andExpect(jsonPath(shop + ".runsPerDay").value(hasItem(closeTo(175, 1e-6))))
                .andExpect(jsonPath(shop + ".profitPerDay").value(hasItem(closeTo(shopNet * 175, 0.1))))
                .andExpect(jsonPath(sell + ".netProfit").value(hasItem(closeTo(2, 1e-9))))
                .andExpect(jsonPath(sell + ".sellVenue").value(hasItem("NPC")))
                // Buy orders fill as others insta-sell: only that side counts, half of both sides together.
                .andExpect(jsonPath(sell + ".runsPerDay").value(hasItem(closeTo(3_500, 1e-6))))
                .andExpect(jsonPath(sell + ".profitPerHour").value(hasItem((Object) null)))
                .andExpect(jsonPath(sell + ".alerts[*].type").value(hasItem("RECENT_CHANGE")))
                .andExpect(jsonPath(shop + ".alerts[*]").isEmpty())
                .andExpect(jsonPath("$.items[0].id").value("shop:ZOG_NPC:PET_ITEM_ALL_SKILLS_BOOST_COMMON"))
                // Losing routes are listed (ranked last), not hidden.
                .andExpect(jsonPath("$.items[?(@.id == 'sell:REFINED_MITHRIL')].netProfit")
                        .value(hasItem(closeTo(1000 - 745_000, 0.01))))
                // Zog's other items have no market price: hidden as incomplete.
                .andExpect(jsonPath("$.items[?(@.id == 'shop:ZOG_NPC:PET_ITEM_FARMING_SKILL_BOOST_COMMON')]").isEmpty());
    }

    @Test
    void detailNamesTheNpcAndWhereToFindIt() throws Exception {
        mvc.perform(get("/api/v1/flips/npc/shop:ZOG_NPC:PET_ITEM_ALL_SKILLS_BOOST_COMMON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guide.steps[0]").value("Buy PET_ITEM_ALL_SKILLS_BOOST_COMMON from Zog (Hub) for 50k coins"))
                .andExpect(jsonPath("$.guide.steps[2]").value(startsWith("Repeat about 175 times a day")));
        mvc.perform(get("/api/v1/flips/npc/sell:ENCHANTED_MITHRIL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guide.steps[0]").value(startsWith("Place a buy order for ")))
                .andExpect(jsonPath("$.guide.steps[1]").value(startsWith("Sell them to any NPC for ")));
        mvc.perform(get("/api/v1/flips/npc/sell:NOT_AN_ITEM")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/flips/npc/ENCHANTED_MITHRIL")).andExpect(status().isNotFound());
    }

    private void price(String id, long at, double buy, double sell, long buyWeek, long sellWeek) {
        long itemId = items.ensureItem(id);
        bazaar.insert(List.of(new BazaarRepository.Snapshot(at, itemId, buy, sell, 100_000, 100_000, buyWeek, sellWeek,
                100, 100, "HYPIXEL")));
    }

    private void npc(String id, double price) {
        items.ensureItem(id);
        jdbc.sql("UPDATE item SET npc_sell_price = :p WHERE canonical_id = :id").param("p", price).param("id", id).update();
    }
}
