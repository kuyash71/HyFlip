package com.hyflip.server.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyflip.server.support.DatabaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class StatusControllerTest extends DatabaseTest {

    @Autowired MockMvc mvc;
    @Autowired com.hyflip.server.storage.CollectorRunRepository runs;

    @Test
    void reportsCollectorsDatabaseAndKey() throws Exception {
        mvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectors[?(@.name == 'BAZAAR')].intervalSeconds").value(60))
                .andExpect(jsonPath("$.collectors.length()").value(8))
                .andExpect(jsonPath("$.database.sizeBytes").isNumber())
                .andExpect(jsonPath("$.neu.loaded").value(false))
                .andExpect(jsonPath("$.apiKey.state").value("MISSING"));
    }

    @Test
    void listsRunsPerCollectorForTheUptimeStrip() throws Exception {
        long now = System.currentTimeMillis();
        long id = runs.start("BAZAAR", now - 60_000);
        runs.finish(id, now - 59_000, "OK", 10, null);
        runs.start("BAZAAR", now - 30 * 3_600_000L);
        mvc.perform(get("/api/v1/status/runs").param("hours", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.BAZAAR.length()").value(1))
                .andExpect(jsonPath("$.BAZAAR[0].status").value("OK"))
                .andExpect(jsonPath("$.PROFILE").isArray());
        mvc.perform(get("/api/v1/status/runs").param("hours", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void runNowNeedsAKnownCollectorAndCollectionOn() throws Exception {
        mvc.perform(post("/api/v1/status/collectors/NOPE/run")).andExpect(status().isNotFound());
        // Tests run with collection disabled.
        mvc.perform(post("/api/v1/status/collectors/BAZAAR/run")).andExpect(status().isConflict());
    }
}
