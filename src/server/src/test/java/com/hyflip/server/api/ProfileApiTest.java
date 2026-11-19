package com.hyflip.server.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.source.neu.NeuRepository;
import com.hyflip.server.support.DatabaseTest;
import com.hyflip.server.support.FakeHypixelApi;
import com.hyflip.server.support.Fixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Profile linking: settings and Forge Tracker filled from the SkyBlock profile. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "hyflip.player.username=kuyashe",
        "hyflip.player.hotm-level=5",
        "hyflip.player.forge-slots=3",
        "hyflip.forge.quick-forge-reduction=0.2",
        "hyflip.forge.molten-forge=OFF"})
class ProfileApiTest extends DatabaseTest {

    static final String UUID = "a41b271f93b040b4a9776e83d4f61918";
    static final String KEY = "0b7a1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d";
    static final long HOUR = 3_600_000;

    @Autowired MockMvc mvc;
    @Autowired NeuRepository neu;
    @Autowired HyFlipProperties properties;
    @Autowired JdbcClient jdbc;
    @Autowired FakeHypixelApi hypixel;
    @Autowired FakeHypixelApi.FakeMojangApi mojang;

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
        mvc.perform(delete("/api/v1/profile/link"));
        mvc.perform(delete("/api/v1/settings/api-key"));
        mojang.uuids.put("kuyashe", UUID);
        now = System.currentTimeMillis();
        hypixel.profiles = """
                {"success": true, "profiles": [{"profile_id": "p1", "cute_name": "Tomato", "selected": true,
                  "members": {"%s": {
                    "skill_tree": {"experience": {"mining": 21600}, "nodes": {"mining": {"quick_forge": 11}}},
                    "player_data": {"unlocked_coll_tiers": ["MITHRIL_ORE_9"]},
                    "forge": {"forge_processes": {"forge_1": {
                      "1": {"id": "REFINED_MITHRIL", "startTime": %d, "slot": 1},
                      "3": {"id": "MYSTERY_ITEM", "startTime": %d, "slot": 3}}}}}}}]}"""
                .formatted(UUID, now - HOUR, now - HOUR);
    }

    @Test
    void needsAUsernameAndAKey() throws Exception {
        mvc.perform(post("/api/v1/profile/link"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.kind").value("NOT_CONFIGURED"));
        mvc.perform(get("/api/v1/profile"))
                .andExpect(jsonPath("$.link.configured").value(false))
                .andExpect(jsonPath("$.link.lastError").isString());
    }

    @Test
    void fillsTheContextAndTheForgeTracker() throws Exception {
        // A job recorded by hand that finished long ago and is no longer in the forge.
        mvc.perform(post("/api/v1/forge/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot\": 2, \"recipeId\": \"REFINED_MITHRIL\", \"startedAt\": " + (now - 10 * HOUR) + "}"))
                .andExpect(status().isCreated());
        key();

        mvc.perform(post("/api/v1/profile/link"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.cuteName").value("Tomato"))
                .andExpect(jsonPath("$.forge.added").value(1))
                .andExpect(jsonPath("$.forge.collected").value(1))
                .andExpect(jsonPath("$.forge.unknownRecipes[0]").value("MYSTERY_ITEM"));
        org.assertj.core.api.Assertions.assertThat(hypixel.lastProfilesKey).isEqualTo(KEY);

        mvc.perform(get("/api/v1/profile"))
                .andExpect(jsonPath("$.context.source").value("PROFILE"))
                .andExpect(jsonPath("$.context.hotmLevel").value(3))
                .andExpect(jsonPath("$.context.quickForgeReduction").value(0.155))
                .andExpect(jsonPath("$.context.collections.MITHRIL_ORE").value(9))
                .andExpect(jsonPath("$.link.linked.uuid").value(UUID))
                .andExpect(jsonPath("$.link.lastError").doesNotExist());
        mvc.perform(get("/api/v1/forge/slots"))
                .andExpect(jsonPath("$.slots[0].job.recipeId").value("REFINED_MITHRIL"))
                .andExpect(jsonPath("$.slots[0].job.source").value("PROFILE_API"))
                .andExpect(jsonPath("$.slots[0].job.startedAt").value(now - HOUR))
                .andExpect(jsonPath("$.slots[1].job").doesNotExist());

        // A second sync changes nothing.
        mvc.perform(post("/api/v1/profile/link"))
                .andExpect(jsonPath("$.forge.added").value(0))
                .andExpect(jsonPath("$.forge.collected").value(0));

        // Another username: the stored profile no longer applies.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"player\": {\"username\": \"someone\"}}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/profile"))
                .andExpect(jsonPath("$.context.hotmLevel").value(5))
                .andExpect(jsonPath("$.link.linked").doesNotExist());
        mvc.perform(post("/api/v1/profile/link"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/settings"));
    }

    @Test
    void movesAJobRecordedInTheWrongSlot() throws Exception {
        // Recorded by hand in slot 2, 10 minutes off; the forge has it in slot 1.
        mvc.perform(post("/api/v1/forge/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot\": 2, \"recipeId\": \"REFINED_MITHRIL\", \"startedAt\": " + (now - 50 * 60_000) + "}"))
                .andExpect(status().isCreated());
        key();
        mvc.perform(post("/api/v1/profile/link"))
                .andExpect(jsonPath("$.forge.added").value(0))
                .andExpect(jsonPath("$.forge.moved").value(1))
                .andExpect(jsonPath("$.forge.collected").value(0));
        mvc.perform(get("/api/v1/forge/slots"))
                .andExpect(jsonPath("$.slots[0].job.recipeId").value("REFINED_MITHRIL"))
                .andExpect(jsonPath("$.slots[0].job.startedAt").value(now - HOUR))
                .andExpect(jsonPath("$.slots[1].job").doesNotExist());
    }

    @Test
    void reportsARejectedKey() throws Exception {
        key();
        hypixel.failure = new com.hyflip.server.source.hypixel.HypixelApiException(403, "forbidden");
        try {
            mvc.perform(post("/api/v1/profile/link"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.kind").value("KEY_REJECTED"));
        } finally {
            hypixel.failure = null;
        }
    }

    private void key() throws Exception {
        mvc.perform(put("/api/v1/settings/api-key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\": \"" + KEY + "\"}")).andExpect(status().isOk());
    }
}
