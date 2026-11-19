package com.hyflip.server.icons;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyflip.server.support.DatabaseTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** Loading, serving and removing pack icons over HTTP. */
@AutoConfigureMockMvc
class IconApiTest extends DatabaseTest {

    @Autowired MockMvc mvc;

    @Test
    void loadsServesAndRemovesIcons() throws Exception {
        mvc.perform(get("/api/v1/icons")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/icons/atlas.png")).andExpect(status().isNotFound());

        Path other = new TestPacks().text("pack.mcmeta", "{}").write(dataDir.resolve("other.zip"));
        mvc.perform(post("/api/v1/icons/import").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"" + other.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not the Hypixel SkyBlock")));

        Path pack = TestPacks.skyblock().flatItem("uncategorized/refined_mithril", TestPacks.RED)
                .write(dataDir.resolve("pack.zip"));
        mvc.perform(multipart("/api/v1/icons/import")
                        .file(new MockMultipartFile("file", "Hypixel SkyBlock.zip", "application/zip", Files.readAllBytes(pack))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.REFINED_MITHRIL").isNumber())
                .andExpect(jsonPath("$.source").value("Hypixel SkyBlock.zip"));
        mvc.perform(get("/api/v1/icons")).andExpect(status().isOk()).andExpect(jsonPath("$.cell").value(32));
        mvc.perform(get("/api/v1/icons/atlas.png").param("v", "1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
        mvc.perform(get("/api/v1/icons/candidates")).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/icons")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/icons")).andExpect(status().isNoContent());
    }
}
