package com.hyflip.server.api;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Forge Flipper, Forge Tracker and calibration over HTTP, with NEU fixtures and stored prices. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "hyflip.player.hotm-level=5",
        "hyflip.player.forge-slots=2",
        "hyflip.forge.quick-forge-reduction=0.2",
        "hyflip.forge.molten-forge=OFF"})
class ForgeApiTest extends DatabaseTest {

    static final long HOUR_MS = 3_600_000;
    /** Refined Mithril: 6 h base, 20% Quick Forge. */
    static final long REFINED_SECONDS = Math.round(21_600 * 0.8);

    @Autowired MockMvc mvc;
    @Autowired NeuRepository neu;
    @Autowired HyFlipProperties properties;
    @Autowired ItemRepository items;
    @Autowired BazaarRepository bazaar;
    @Autowired JdbcClient jdbc;

    long now;

    @BeforeEach
    void setUp() throws IOException {
        Path root = properties.dataDir().resolve("neu");
        if (Files.notExists(root.resolve("COMMIT"))) {
            copy(Fixtures.neuRepo(), root.resolve("repo"));
            Files.writeString(root.resolve("COMMIT"), "fixture");
        }
        neu.loadLocal();
        jdbc.sql("DELETE FROM forge_job").update();
        jdbc.sql("DELETE FROM forge_calibration").update();
        jdbc.sql("DELETE FROM bazaar_snapshot").update();
        now = System.currentTimeMillis();
        price("ENCHANTED_MITHRIL", now, 1330, 1260);
        price("REFINED_MITHRIL", now, 760_000, 745_000);
    }

    @Test
    void ranksForgeRecipesWithCostSheet() throws Exception {
        double net = 760_000 * 0.9875 - 160 * 1260;
        mvc.perform(get("/api/v1/flips/forge").param("includeIncomplete", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.priceSource").value("ORDER"))
                .andExpect(jsonPath("$.settings.forgeSpeed.knownReduction").value(closeTo(0.2, 1e-9)))
                .andExpect(jsonPath("$.items[0].id").value("REFINED_MITHRIL"))
                .andExpect(jsonPath("$.items[0].netProfit").value(closeTo(net, 0.01)))
                .andExpect(jsonPath("$.items[0].durationSeconds").value(REFINED_SECONDS))
                .andExpect(jsonPath("$.items[0].unlock").value("MET"))
                .andExpect(jsonPath("$.items[?(@.id == 'GEMSTONE_CHAMBER')].complete").value(hasItem(false)));

        mvc.perform(get("/api/v1/flips/forge/REFINED_MITHRIL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheet.inputs[0].quote.venue").value("BAZAAR"))
                .andExpect(jsonPath("$.sheet.inputs[0].quantity").value(160.0))
                .andExpect(jsonPath("$.names.ENCHANTED_MITHRIL").value("Enchanted Mithril"))
                .andExpect(jsonPath("$.guide.steps.length()").value(5))
                .andExpect(jsonPath("$.guide.steps[1]").value(org.hamcrest.Matchers.startsWith(
                        "At current volume the orders fill in about")))
                .andExpect(jsonPath("$.summary.fillSeconds").isNumber());
    }

    @Test
    void validatesQueries() throws Exception {
        mvc.perform(get("/api/v1/flips/kat")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/flips/forge").param("sort", "luck")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/flips/forge").param("nextSessionIn", "soon")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/flips/forge").param("budget", "1000"))
                .andExpect(jsonPath("$.items[?(@.id == 'REFINED_MITHRIL')]").isEmpty());
        mvc.perform(get("/api/v1/flips/forge").param("nextSessionIn", "PT2H"))
                .andExpect(jsonPath("$.settings.nextSessionInSeconds").value(7200))
                .andExpect(jsonPath("$.items[0].sessions").value(3));
        mvc.perform(get("/api/v1/flips/forge/NOT_A_RECIPE")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/flips/forge").param("hideAlerts", "FEW_SALES,NOPE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/flips/forge").param("hotm", "11")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/flips/forge").param("search", "refined").param("hideAlerts", "FEW_SALES,STALE_PRICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(hasItem("REFINED_MITHRIL")));
        mvc.perform(get("/api/v1/flips/forge").param("search", "no such thing"))
                .andExpect(jsonPath("$.total").value(0));
        // HotM 1 locks Refined Mithril (HotM 2) and hides it by default.
        mvc.perform(get("/api/v1/flips/forge").param("hotm", "1"))
                .andExpect(jsonPath("$.items[?(@.id == 'REFINED_MITHRIL')]").isEmpty());
    }

    @Test
    void tracksJobsPerSlot() throws Exception {
        mvc.perform(startJob(1, "REFINED_MITHRIL", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expectedCost").value(closeTo(160 * 1260, 0.01)))
                .andExpect(jsonPath("$.remainingSeconds").value((int) REFINED_SECONDS));
        mvc.perform(startJob(1, "REFINED_MITHRIL", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").isString());
        mvc.perform(startJob(3, "REFINED_MITHRIL", null)).andExpect(status().isBadRequest());
        mvc.perform(startJob(2, "NOT_A_RECIPE", null)).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/forge/slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotCount").value(2))
                .andExpect(jsonPath("$.slots[0].job.recipeId").value("REFINED_MITHRIL"))
                .andExpect(jsonPath("$.slots[0].job.finished").value(false))
                .andExpect(jsonPath("$.slots[1].job").doesNotExist());
    }

    @Test
    void settlesFinishedJobsAtPricesFromTheirEnd() throws Exception {
        // Ten hours ago Refined Mithril sold for less; the job (4.8 h) ended before today's price.
        price("REFINED_MITHRIL", now - 11 * HOUR_MS, 700_000, 690_000);
        String body = mvc.perform(startJob(2, "REFINED_MITHRIL", now - 10 * HOUR_MS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.finished").value(true))
                .andReturn().getResponse().getContentAsString();
        long id = Fixtures.JSON.readTree(body).get("id").asLong();

        mvc.perform(get("/api/v1/forge/slots"))
                .andExpect(jsonPath("$.alerts[0].type").value("FORGE_FINISHED"));
        mvc.perform(get("/api/v1/forge/jobs"))
                .andExpect(jsonPath("$[0].realisedValue").value(closeTo(700_000 * 0.9875, 0.01)))
                .andExpect(jsonPath("$[0].valueChange").value(closeTo((700_000 - 760_000) * 0.9875, 0.01)));

        mvc.perform(post("/api/v1/forge/jobs/" + id + "/collect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectedAt").isNumber());
        mvc.perform(get("/api/v1/forge/slots"))
                .andExpect(jsonPath("$.slots[1].job").doesNotExist())
                .andExpect(jsonPath("$.alerts").isEmpty());
        mvc.perform(put("/api/v1/forge/jobs/" + id + "/sale").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 720000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soldValue").value(720_000.0))
                .andExpect(jsonPath("$.soldProfit").value(closeTo(720_000 - 201_600, 0.01)));
        mvc.perform(put("/api/v1/forge/jobs/" + id + "/sale").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\": -1}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/forge/jobs/" + id + "/sale").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": null}"))
                .andExpect(jsonPath("$.soldValue").doesNotExist());
        mvc.perform(delete("/api/v1/forge/jobs/" + id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/forge/jobs/" + id)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/forge/jobs/" + id + "/sale").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\": 1}")).andExpect(status().isNotFound());
    }

    @Test
    void calibrationChangesRealDurations() throws Exception {
        mvc.perform(post("/api/v1/forge/calibration").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipeId\": \"REFINED_MITHRIL\", \"observedSeconds\": " + REFINED_SECONDS / 2 + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ratio").value(closeTo(0.5, 1e-9)));
        mvc.perform(get("/api/v1/forge/calibration"))
                .andExpect(jsonPath("$.fit.kind").value("PERCENTAGE"))
                .andExpect(jsonPath("$.fit.provisional").value(true))
                .andExpect(jsonPath("$.samples.length()").value(1))
                .andExpect(jsonPath("$.suggestions.length()").value(2));
        mvc.perform(get("/api/v1/flips/forge/REFINED_MITHRIL"))
                .andExpect(jsonPath("$.summary.durationSeconds").value(REFINED_SECONDS / 2));
        mvc.perform(post("/api/v1/forge/calibration").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipeId\": \"REFINED_MITHRIL\", \"observedSeconds\": 0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportsTheEffectiveProfile() throws Exception {
        mvc.perform(get("/api/v1/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.hotmLevel").value(5))
                .andExpect(jsonPath("$.context.forgeSlotsKnown").value(true))
                .andExpect(jsonPath("$.context.moltenForgeSource").value("CONFIG"))
                .andExpect(jsonPath("$.forgeSpeed.calibration").value("NONE"));
    }

    private org.springframework.test.web.servlet.RequestBuilder startJob(int slot, String recipe, Long startedAt) {
        String start = startedAt == null ? "" : ", \"startedAt\": " + startedAt;
        return post("/api/v1/forge/jobs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"slot\": " + slot + ", \"recipeId\": \"" + recipe + "\"" + start + "}");
    }

    private void price(String id, long at, double buy, double sell) {
        long itemId = items.ensureItem(id);
        bazaar.insert(List.of(new BazaarRepository.Snapshot(at, itemId, buy, sell, 100_000, 100_000, 700_000, 700_000,
                100, 100, "HYPIXEL")));
    }

    static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }
}
