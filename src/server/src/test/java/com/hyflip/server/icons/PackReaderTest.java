package com.hyflip.server.icons;

import static com.hyflip.server.icons.TestPacks.BLUE;
import static com.hyflip.server.icons.TestPacks.GREEN;
import static com.hyflip.server.icons.TestPacks.GREY;
import static com.hyflip.server.icons.TestPacks.RED;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PackReaderTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path tmp;

    @Test
    void readsAFlatItemThroughDefinitionModelAndTexture() throws Exception {
        Path pack = TestPacks.skyblock().flatItem("uncategorized/aspect_of_the_end", RED).write(tmp.resolve("p.zip"));
        try (PackReader reader = new PackReader(pack, JSON)) {
            assertThat(reader.description()).isEqualTo("Hypixel SkyBlock");
            assertThat(reader.itemModels()).containsExactly("hypixel_skyblock:item/uncategorized/aspect_of_the_end");
            BufferedImage icon = reader.icon("hypixel_skyblock:item/uncategorized/aspect_of_the_end").orElseThrow();
            assertThat(icon.getWidth()).isEqualTo(16);
            assertThat(icon.getRGB(8, 8)).isEqualTo(RED);
            assertThat(reader.icon("hypixel_skyblock:item/nope")).isEmpty();
        }
    }

    @Test
    void takesTheDefaultBranchFirstFrameAndStacksLayers() throws Exception {
        Path pack = TestPacks.skyblock()
                // condition: the rod when not cast
                .text("assets/hypixel_skyblock/items/item/rod.json", """
                        {"model":{"type":"condition","property":"fishing_rod/cast",
                          "on_true":{"type":"model","model":"hypixel_skyblock:item/rod_cast"},
                          "on_false":{"type":"model","model":"hypixel_skyblock:item/rod"}}}""")
                // the model inherits layer0 from a parent in the pack and adds layer1 on top
                .text("assets/hypixel_skyblock/models/item/rod.json",
                        "{\"parent\":\"hypixel_skyblock:item/base\",\"textures\":{\"layer1\":\"hypixel_skyblock:item/top\"}}")
                .text("assets/hypixel_skyblock/models/item/base.json",
                        "{\"parent\":\"item/handheld\",\"textures\":{\"layer0\":\"hypixel_skyblock:item/animated\"}}")
                .png("assets/hypixel_skyblock/textures/item/animated.png", 16, 4, GREEN)
                .text("assets/hypixel_skyblock/textures/item/top.png", "not a png")
                .write(tmp.resolve("p.zip"));
        try (PackReader reader = new PackReader(pack, JSON)) {
            BufferedImage icon = reader.icon("hypixel_skyblock:item/rod").orElseThrow();
            // first frame only (later frames are blue), square, broken layer skipped
            assertThat(icon.getHeight()).isEqualTo(16);
            assertThat(icon.getRGB(0, 15)).isEqualTo(GREEN);
        }
    }

    @Test
    void picksTheDefaultModelOfEachDefinitionType() throws Exception {
        var node = JSON.readTree("""
                {"type":"select","cases":[{"when":"x","model":{"type":"model","model":"a"}}],
                 "fallback":{"type":"composite","models":[{"type":"model","model":"b"},{"type":"model","model":"c"}]}}""");
        assertThat(PackReader.defaultModel(node, 0)).isEqualTo("b");
        assertThat(PackReader.defaultModel(JSON.readTree("""
                {"type":"minecraft:range_dispatch","entries":[{"threshold":0.5,"model":{"type":"model","model":"d"}}]}"""),
                0)).isEqualTo("d");
        assertThat(PackReader.defaultModel(JSON.readTree("{\"type\":\"special\"}"), 0)).isNull();
    }

    @Test
    void fallsBackToTheClientJarForVanillaItemsAndDrawsBlocksAsCubes() throws Exception {
        Path pack = TestPacks.skyblock().flatItem("x", RED).write(tmp.resolve("p.zip"));
        Path jar = TestPacks.client().write(tmp.resolve("client.jar"));
        assertThat(PackReader.isModernClient(jar)).isTrue();
        assertThat(PackReader.isModernClient(pack)).isFalse();
        try (PackReader reader = new PackReader(List.of(pack, jar), JSON)) {
            assertThat(reader.icon("minecraft:lapis_lazuli").orElseThrow().getRGB(3, 3)).isEqualTo(BLUE);
            BufferedImage cube = reader.icon("minecraft:stone").orElseThrow();
            assertThat(cube.getWidth()).isEqualTo(32);
            // corners are empty, top face full light, left 80%, right 60%
            assertThat(cube.getRGB(0, 0) >>> 24).isZero();
            assertThat(cube.getRGB(16, 8)).isEqualTo(GREY);
            assertThat(cube.getRGB(6, 22) & 0xff).isEqualTo((int) (0x80 * 0.8));
            assertThat(cube.getRGB(26, 22) & 0xff).isEqualTo((int) (0x80 * 0.6));
        }
    }

    @Test
    void recognisesOnlyTheSkyBlockPack() throws Exception {
        assertThat(PackReader.isSkyBlockPack(TestPacks.skyblock().flatItem("a", RED).write(tmp.resolve("a.zip"))))
                .isTrue();
        assertThat(PackReader.isSkyBlockPack(new TestPacks().text("pack.mcmeta", "{}").write(tmp.resolve("b.zip"))))
                .isFalse();
        Path notZip = java.nio.file.Files.writeString(tmp.resolve("c.zip"), "hello");
        assertThat(PackReader.isSkyBlockPack(notZip)).isFalse();
    }
}
