package com.hyflip.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyflip.server.security.AuthTokenFilter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hyflip.data-dir", dataDir::toString);
        registry.add("hyflip.auth-token", () -> "test-token");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsUpWithToken() throws Exception {
        mvc.perform(get("/api/v1/health").header(AuthTokenFilter.HEADER, "test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isUnauthorized());
    }

    @Test
    void requestsWithWrongTokenAreRejected() throws Exception {
        mvc.perform(get("/api/v1/health").header(AuthTokenFilter.HEADER, "nope"))
                .andExpect(status().isUnauthorized());
    }
}
