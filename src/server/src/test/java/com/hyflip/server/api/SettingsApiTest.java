package com.hyflip.server.api;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyflip.server.support.DatabaseTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Settings saved in the app over config.yaml, and the write-only API key. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"hyflip.player.hotm-level=4", "hyflip.trading.bazaar-tax-rate=0.0125"})
class SettingsApiTest extends DatabaseTest {

    static final String KEY = "0b7a1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d";

    @Autowired MockMvc mvc;

    @AfterEach
    void reset() throws Exception {
        mvc.perform(delete("/api/v1/settings"));
        mvc.perform(delete("/api/v1/settings/api-key"));
    }

    @Test
    void startsFromTheConfigAndMergesPatches() throws Exception {
        mvc.perform(get("/api/v1/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("CONFIG"))
                .andExpect(jsonPath("$.settings.player.hotmLevel").value(4))
                .andExpect(jsonPath("$.settings.alerts.manipulationRatio").value(2.0));

        mvc.perform(patch("/api/v1/settings").contentType(MediaType.APPLICATION_JSON).content("""
                        {"player": {"hotmLevel": 7}, "trading": {"bazaarTaxRate": 0.01125,
                         "auctionFees": {"smallRate": 0.015}}, "alerts": {"minAuctionSales": 5}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("APP"))
                .andExpect(jsonPath("$.settings.player.hotmLevel").value(7))
                .andExpect(jsonPath("$.settings.trading.auctionFees.smallRate").value(0.015))
                .andExpect(jsonPath("$.settings.trading.auctionFees.mediumRate").value(0.02));

        // The flippers use the new values.
        mvc.perform(get("/api/v1/profile"))
                .andExpect(jsonPath("$.context.hotmLevel").value(7))
                .andExpect(jsonPath("$.context.source").value("APP"))
                .andExpect(jsonPath("$.context.fees.bazaarTaxRate").value(0.01125));

        // The collection map is replaced, not merged.
        mvc.perform(patch("/api/v1/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"player\": {\"collections\": {\"MITHRIL_ORE\": 9, \"GEMSTONE_COLLECTION\": 3}}}"));
        mvc.perform(patch("/api/v1/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"player\": {\"collections\": {\"MITHRIL_ORE\": 7}}}"))
                .andExpect(jsonPath("$.settings.player.collections.MITHRIL_ORE").value(7))
                .andExpect(jsonPath("$.settings.player.collections.GEMSTONE_COLLECTION").doesNotExist());

        // null clears an optional value.
        mvc.perform(patch("/api/v1/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"player\": {\"hotmLevel\": null}}"))
                .andExpect(jsonPath("$.settings.player.hotmLevel").doesNotExist());

        mvc.perform(delete("/api/v1/settings"))
                .andExpect(jsonPath("$.source").value("CONFIG"))
                .andExpect(jsonPath("$.settings.player.hotmLevel").value(4));
    }

    @Test
    void rejectsInvalidSettingsWithEveryProblem() throws Exception {
        mvc.perform(patch("/api/v1/settings").contentType(MediaType.APPLICATION_JSON).content("""
                        {"player": {"hotmLevel": 11, "username": "not a name"}, "trading": {"bazaarTaxRate": 0.5}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.problems.length()").value(3))
                .andExpect(jsonPath("$.problems").value(hasItem("player.hotmLevel must be between 0 and 10")));
        mvc.perform(patch("/api/v1/settings").contentType(MediaType.APPLICATION_JSON).content("[1]"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/settings")).andExpect(jsonPath("$.source").value("CONFIG"));
    }

    @Test
    void storesTheApiKeyWithoutEverReturningIt() throws Exception {
        mvc.perform(put("/api/v1/settings/api-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\": \"nope\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/settings/api-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\": \"" + KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("VALID"))
                .andExpect(jsonPath("$.source").value("APP"));
        mvc.perform(get("/api/v1/settings"))
                .andExpect(jsonPath("$.apiKey.state").value("VALID"))
                .andExpect(content().string(not(containsString(KEY))));
        mvc.perform(get("/api/v1/status")).andExpect(content().string(not(containsString(KEY))));
        mvc.perform(delete("/api/v1/settings/api-key"))
                .andExpect(jsonPath("$.state").value("MISSING"));
    }

    @Test
    void desktopWebviewMayPatchSettings() throws Exception {
        // The Tauri webview is another origin, so the browser asks first; PATCH must be allowed.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/settings")
                        .header("Origin", "tauri://localhost")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Methods", containsString("PATCH")));
    }
}
