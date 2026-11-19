package com.hyflip.server.api;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.source.neu.NeuRepository;
import com.hyflip.server.storage.BazaarRepository;
import com.hyflip.server.storage.ItemRepository;
import com.hyflip.server.storage.ResourceRepository;
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

/** Craft Flipper over HTTP: collection gating and buy-vs-craft intermediates. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "hyflip.player.hotm-level=5",
        "hyflip.player.collections.Mithril=3",
        "hyflip.player.collections.DIAMOND=3"})
class CraftApiTest extends DatabaseTest {

    @Autowired MockMvc mvc;
    @Autowired NeuRepository neu;
    @Autowired HyFlipProperties properties;
    @Autowired ItemRepository items;
    @Autowired BazaarRepository bazaar;
    @Autowired ResourceRepository resources;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() throws IOException {
        Path root = properties.dataDir().resolve("neu");
        if (Files.notExists(root.resolve("COMMIT"))) {
            ForgeApiTest.copy(Fixtures.neuRepo(), root.resolve("repo"));
            Files.writeString(root.resolve("COMMIT"), "fixture");
        }
        neu.loadLocal();
        resources.save("collections", """
                {"collections": {"MINING": {"name": "Mining", "items": {
                    "MITHRIL_ORE": {"name": "Mithril"}, "DIAMOND": {"name": "Diamond"}}}}}
                """, 1);
        jdbc.sql("DELETE FROM bazaar_snapshot").update();
        long now = System.currentTimeMillis();
        price("MITHRIL_ORE", now, 6, 5);
        price("ENCHANTED_MITHRIL", now, 1330, 1260);
        price("REFINED_MITHRIL", now, 760_000, 745_000);
        price("DIAMOND", now, 9, 8);
        price("DIAMOND_BLOCK", now, 75, 70);
        price("ENCHANTED_DIAMOND", now, 1400, 1300);
    }

    @Test
    void ranksCraftRecipesAndHidesLockedOnes() throws Exception {
        mvc.perform(get("/api/v1/flips/craft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.forgeSpeed").doesNotExist())
                .andExpect(jsonPath("$.items[?(@.id == 'ENCHANTED_MITHRIL')].netProfit")
                        .value(hasItem(closeTo(1330 * 0.9875 - 160 * 5, 0.01))))
                .andExpect(jsonPath("$.items[?(@.id == 'ENCHANTED_MITHRIL')].profitPerHour").value(hasItem((Object) null)))
                .andExpect(jsonPath("$.items[?(@.id == 'ENCHANTED_DIAMOND')]").isEmpty());

        // Diamond IV is needed and the player has III; the 9-per-craft recipe from blocks earns more per run.
        mvc.perform(get("/api/v1/flips/craft").param("includeLocked", "true"))
                .andExpect(jsonPath("$.items[?(@.id == 'ENCHANTED_DIAMOND')].unlock").value(hasItem("LOCKED")))
                .andExpect(jsonPath("$.items[?(@.id == 'ENCHANTED_DIAMOND')].outputCount").value(hasItem(9.0)));
    }

    @Test
    void forgeInputsAreCraftedWhenCheaper() throws Exception {
        mvc.perform(get("/api/v1/flips/forge/REFINED_MITHRIL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheet.inputs[0].plan.method").value("CRAFT"))
                .andExpect(jsonPath("$.sheet.inputs[0].plan.inputs[0].itemId").value("MITHRIL_ORE"))
                .andExpect(jsonPath("$.sheet.cost").value(closeTo(160 * 160 * 5, 0.01)))
                .andExpect(jsonPath("$.names.MITHRIL_ORE").value("MITHRIL_ORE"))
                .andExpect(jsonPath("$.guide.steps[0]").value(org.hamcrest.Matchers.startsWith("Buy 25,600 × MITHRIL_ORE")))
                .andExpect(jsonPath("$.guide.steps[1]").value(org.hamcrest.Matchers.startsWith("Craft 160 × Enchanted Mithril")));
        mvc.perform(get("/api/v1/flips/craft/NOT_CRAFTABLE")).andExpect(status().isNotFound());
    }

    @Test
    void detailCarriesGridsRaritiesAndRequirements() throws Exception {
        mvc.perform(get("/api/v1/flips/forge/REFINED_MITHRIL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipes.REFINED_MITHRIL.type").value("FORGE"))
                .andExpect(jsonPath("$.recipes.REFINED_MITHRIL.durationSeconds").value(21600))
                .andExpect(jsonPath("$.recipes.ENCHANTED_MITHRIL.type").value("CRAFTING"))
                .andExpect(jsonPath("$.recipes.ENCHANTED_MITHRIL.grid.length()").value(9))
                .andExpect(jsonPath("$.recipes.ENCHANTED_MITHRIL.grid[0]").value((Object) null))
                .andExpect(jsonPath("$.recipes.ENCHANTED_MITHRIL.grid[1].item").value("MITHRIL_ORE"))
                .andExpect(jsonPath("$.recipes.ENCHANTED_MITHRIL.grid[1].count").value(32.0))
                .andExpect(jsonPath("$.rarities.REFINED_MITHRIL").value("EPIC"))
                .andExpect(jsonPath("$.extras.requirements[0]").value("HOTM 2"))
                .andExpect(jsonPath("$.extras.rarity").value("EPIC"));
        mvc.perform(get("/api/v1/flips/craft"))
                .andExpect(jsonPath("$.extras.ENCHANTED_MITHRIL.rarity").value("RARE"))
                .andExpect(jsonPath("$.extras.ENCHANTED_MITHRIL.requirements[0]").value("Mithril III"))
                // Prices from one moment give no trend yet.
                .andExpect(jsonPath("$.extras.ENCHANTED_MITHRIL.trend").value((Object) null));
    }

    @Test
    void catalogListsNamesAndRarities() throws Exception {
        mvc.perform(get("/api/v1/items/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commit").value("fixture"))
                .andExpect(jsonPath("$.items.REFINED_MITHRIL[0]").value("Refined Mithril"))
                .andExpect(jsonPath("$.items.REFINED_MITHRIL[1]").value("EPIC"));
    }

    private void price(String id, long at, double buy, double sell) {
        long itemId = items.ensureItem(id);
        bazaar.insert(List.of(new BazaarRepository.Snapshot(at, itemId, buy, sell, 100_000, 100_000, 700_000, 700_000,
                100, 100, "HYPIXEL")));
    }
}
