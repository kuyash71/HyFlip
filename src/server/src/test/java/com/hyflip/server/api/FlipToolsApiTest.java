package com.hyflip.server.api;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.source.neu.NeuRepository;
import com.hyflip.server.storage.BazaarRepository;
import com.hyflip.server.storage.ItemRepository;
import com.hyflip.server.support.DatabaseTest;
import com.hyflip.server.support.Fixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Trade journal, backtest, forge planner, item history and the time zone, over HTTP. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "hyflip.player.hotm-level=5",
        "hyflip.player.forge-slots=2",
        "hyflip.forge.quick-forge-reduction=0.2",
        "hyflip.forge.molten-forge=OFF",
        "hyflip.display.time-zone=Europe/Istanbul"})
class FlipToolsApiTest extends DatabaseTest {

    static final long HOUR = 3_600_000;
    /** Refined Mithril: 6 h base, 20% Quick Forge. */
    static final long REFINED_MS = Math.round(21_600 * 0.8) * 1000;
    static final double COST = 160 * 1260;

    @Autowired MockMvc mvc;
    @Autowired NeuRepository neu;
    @Autowired HyFlipProperties properties;
    @Autowired ItemRepository items;
    @Autowired BazaarRepository bazaar;
    @Autowired JdbcClient jdbc;

    long now;

    @BeforeEach
    void setUp() throws Exception {
        Path root = properties.dataDir().resolve("neu");
        if (Files.notExists(root.resolve("COMMIT"))) {
            ForgeApiTest.copy(Fixtures.neuRepo(), root.resolve("repo"));
            Files.writeString(root.resolve("COMMIT"), "fixture");
        }
        neu.loadLocal();
        jdbc.sql("DELETE FROM forge_job").update();
        jdbc.sql("DELETE FROM trade").update();
        jdbc.sql("DELETE FROM bazaar_snapshot").update();
        now = System.currentTimeMillis();
        price("ENCHANTED_MITHRIL", now, 1330, 1260);
        price("REFINED_MITHRIL", now, 760_000, 745_000);
    }

    @Test
    void journalComparesResultsWithTheEstimate() throws Exception {
        double expectedProfit = 760_000 * 0.9875 - COST;
        String body = mvc.perform(post("/api/v1/trades").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"forge\", \"id\": \"REFINED_MITHRIL\", \"runs\": 2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("FORGE"))
                .andExpect(jsonPath("$.expectedCost").value(closeTo(2 * COST, 0.01)))
                .andExpect(jsonPath("$.expectedProfit").value(closeTo(2 * expectedProfit, 0.01)))
                .andExpect(jsonPath("$.actualProfit").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(put("/api/v1/trades/" + id + "/result").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualCost\": 400000, \"actualRevenue\": 1400000, \"finished\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualProfit").value(1_000_000.0))
                .andExpect(jsonPath("$.difference").value(closeTo(1_000_000 - 2 * expectedProfit, 0.01)))
                .andExpect(jsonPath("$.finishedAt").isNumber());

        mvc.perform(get("/api/v1/trades/summary"))
                .andExpect(jsonPath("$.all.withResult").value(1))
                .andExpect(jsonPath("$.byType.FORGE.accuracy").value(closeTo(1_000_000 / (2 * expectedProfit), 1e-6)))
                .andExpect(jsonPath("$.all.profitable").value(1.0));
        mvc.perform(get("/api/v1/trades").param("type", "forge")).andExpect(jsonPath("$.length()").value(1));

        mvc.perform(post("/api/v1/trades").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"forge\", \"id\": \"NOPE\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/trades").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"kat\", \"id\": \"X\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/trades/" + id + "/result").contentType(MediaType.APPLICATION_JSON)
                .content("{\"actualRevenue\": -1}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/trades/" + id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/trades/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void backtestSellsAtThePricesAfterTheJob() throws Exception {
        long t = now - 10 * HOUR;
        price("ENCHANTED_MITHRIL", t, 1330, 1260);
        price("REFINED_MITHRIL", t, 760_000, 745_000);
        // By the time the job would have finished, Refined Mithril fell.
        price("REFINED_MITHRIL", t + REFINED_MS - 60_000, 700_000, 690_000);
        double predicted = 760_000 * 0.9875 - COST;
        double realised = 700_000 * 0.9875 - COST;
        mvc.perform(get("/api/v1/backtest/forge").param("from", String.valueOf(t)).param("to", String.valueOf(t))
                        .param("top", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moments").value(1))
                .andExpect(jsonPath("$.items[?(@.id == 'REFINED_MITHRIL')].predictedProfit").value(
                        org.hamcrest.Matchers.contains(closeTo(predicted, 0.01))))
                .andExpect(jsonPath("$.items[?(@.id == 'REFINED_MITHRIL')].realisedProfit").value(
                        org.hamcrest.Matchers.contains(closeTo(realised, 0.01))))
                .andExpect(jsonPath("$.overall.flips").value(greaterThan(0)));
        mvc.perform(get("/api/v1/backtest/forge").param("step", "1m")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/backtest/kat")).andExpect(status().isNotFound());
    }

    @Test
    void backtestSkipsMomentsBeforeTheFirstSnapshot() throws Exception {
        // An NPC-to-NPC flip needs no stored prices, so without the check its list is never empty and the
        // moments before any snapshot would be ranked on NPC prices alone.
        items.ensureItem("PET_ITEM_ALL_SKILLS_BOOST_COMMON");
        jdbc.sql("UPDATE item SET npc_sell_price = 60000 WHERE canonical_id = 'PET_ITEM_ALL_SKILLS_BOOST_COMMON'")
                .update();
        long t = now - 10 * HOUR;
        jdbc.sql("DELETE FROM bazaar_snapshot").update();
        price("ENCHANTED_MITHRIL", t, 1330, 1260);
        price("ENCHANTED_MITHRIL", now, 1330, 1260);
        mvc.perform(get("/api/v1/backtest/npc").param("from", String.valueOf(t - 3 * HOUR))
                        .param("to", String.valueOf(t)).param("step", "1h").param("top", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moments").value(1))
                .andExpect(jsonPath("$.skipped").value(3))
                .andExpect(jsonPath("$.items[*].at").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is(t))));
    }

    @Test
    void plannerFillsFreeSlotsWithinTheBudget() throws Exception {
        mvc.perform(get("/api/v1/forge/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots.length()").value(2))
                .andExpect(jsonPath("$.picks.length()").value(2))
                .andExpect(jsonPath("$.picks[0].opportunity.id").value("REFINED_MITHRIL"))
                .andExpect(jsonPath("$.limitedBy").value("SLOTS"));
        // Enough for one Refined Mithril only.
        mvc.perform(get("/api/v1/forge/plan").param("budget", "300000"))
                .andExpect(jsonPath("$.picks[?(@.opportunity.id == 'REFINED_MITHRIL')]").value(org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.limitedBy").value("BUDGET"))
                .andExpect(jsonPath("$.totalCost").value(org.hamcrest.Matchers.lessThanOrEqualTo(300000.0)));
        mvc.perform(post("/api/v1/forge/jobs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"slot\": 1, \"recipeId\": \"REFINED_MITHRIL\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/forge/plan"))
                .andExpect(jsonPath("$.slots[0]").value(2))
                .andExpect(jsonPath("$.picks[0].slot").value(2));
        mvc.perform(get("/api/v1/forge/plan").param("slots", "9")).andExpect(status().isBadRequest());
    }

    @Test
    void ordersReportHowLongTheyTakeToFill() throws Exception {
        // 160 Enchanted Mithril at 700k insta-sold a week (~1.16 a second): about 2.3 min; selling one
        // Refined Mithril at 700k insta-bought a week: under a second.
        mvc.perform(get("/api/v1/flips/forge/REFINED_MITHRIL"))
                .andExpect(jsonPath("$.summary.fillSeconds").value(org.hamcrest.Matchers.allOf(
                        greaterThan(130), org.hamcrest.Matchers.lessThan(150))))
                .andExpect(jsonPath("$.guide.steps[1]").value(org.hamcrest.Matchers.startsWith(
                        "At current volume the orders fill in about")));
    }

    @Test
    void itemHistoryBucketsPrices() throws Exception {
        price("REFINED_MITHRIL", now - 3 * HOUR, 700_000, 690_000);
        price("REFINED_MITHRIL", now - 3 * HOUR + 60_000, 710_000, 700_000);
        mvc.perform(get("/api/v1/items/REFINED_MITHRIL"))
                .andExpect(jsonPath("$.name").value("Refined Mithril"))
                .andExpect(jsonPath("$.bazaar.buyPrice").value(760_000.0));
        mvc.perform(get("/api/v1/items/REFINED_MITHRIL/history").param("from", String.valueOf(now - 4 * HOUR))
                        .param("bucket", "1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucketSeconds").value(3600))
                .andExpect(jsonPath("$.bazaar.length()").value(2))
                .andExpect(jsonPath("$.bazaar[0].buyPrice").value(705_000.0));
        mvc.perform(get("/api/v1/items/REFINED_MITHRIL/history").param("from", String.valueOf(now))
                .param("to", String.valueOf(now - 1))).andExpect(status().isBadRequest());
    }

    @Test
    void forgeSlotsCarryTheTimeZone() throws Exception {
        mvc.perform(get("/api/v1/forge/slots")).andExpect(jsonPath("$.timeZone").value("Europe/Istanbul"));
        mvc.perform(patch("/api/v1/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display\": {\"timeZone\": \"Mars/Olympus\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display\": {\"timeZone\": \"America/New_York\"}}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/forge/slots")).andExpect(jsonPath("$.timeZone").value("America/New_York"));
        mvc.perform(delete("/api/v1/settings"));
    }

    private void price(String id, long at, double buy, double sell) {
        long itemId = items.ensureItem(id);
        bazaar.insert(List.of(new BazaarRepository.Snapshot(at, itemId, buy, sell, 100_000, 100_000, 700_000, 700_000,
                100, 100, "HYPIXEL")));
    }
}
