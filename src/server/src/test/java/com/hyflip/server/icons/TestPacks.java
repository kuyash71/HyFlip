package com.hyflip.server.icons;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/** Small resource packs and client jars built in memory, with textures of one flat colour each. */
final class TestPacks {

    static final int RED = 0xffff0000;
    static final int GREEN = 0xff00ff00;
    static final int BLUE = 0xff0000ff;
    static final int GREY = 0xff808080;

    private final Map<String, byte[]> files = new LinkedHashMap<>();

    static TestPacks skyblock() {
        return new TestPacks().text("pack.mcmeta", "{\"pack\":{\"pack_format\":97,\"description\":\"Hypixel SkyBlock\"}}");
    }

    TestPacks text(String name, String content) {
        files.put(name, content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** A {@code size}-px texture of one colour; {@code frames} stacks animation frames, the first in {@code rgb}. */
    TestPacks png(String name, int size, int frames, int rgb) {
        BufferedImage image = new BufferedImage(size, size * frames, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size * frames; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, y < size ? rgb : BLUE);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        files.put(name, out.toByteArray());
        return this;
    }

    /** A flat item: definition, model with {@code layer0}, texture. */
    TestPacks flatItem(String path, int rgb) {
        return text("assets/hypixel_skyblock/items/item/" + path + ".json",
                "{\"model\":{\"type\":\"model\",\"model\":\"hypixel_skyblock:item/" + path + "\"}}")
                .text("assets/hypixel_skyblock/models/item/" + path + ".json",
                        "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"hypixel_skyblock:item/" + path + "\"}}")
                .png("assets/hypixel_skyblock/textures/item/" + path + ".png", 16, 1, rgb);
    }

    Path write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    /** A 1.21.4+ client jar with a flat bone, a cube_all block and the enchanted book. */
    static TestPacks client() {
        return new TestPacks()
                .text("assets/minecraft/items/stone.json",
                        "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/stone\"}}")
                .text("assets/minecraft/models/block/stone.json",
                        "{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"minecraft:block/stone\"}}")
                .text("assets/minecraft/models/block/cube_all.json",
                        "{\"parent\":\"block/cube\",\"textures\":{\"particle\":\"#all\",\"up\":\"#all\",\"north\":\"#all\","
                                + "\"east\":\"#all\",\"south\":\"#all\",\"west\":\"#all\",\"down\":\"#all\"}}")
                .text("assets/minecraft/models/block/cube.json", "{\"parent\":\"block/block\"}")
                .png("assets/minecraft/textures/block/stone.png", 16, 1, GREY)
                .vanillaItem("bone", GREY)
                .vanillaItem("lapis_lazuli", BLUE)
                .vanillaItem("enchanted_book", RED);
    }

    TestPacks vanillaItem(String name, int rgb) {
        return text("assets/minecraft/items/" + name + ".json",
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/" + name + "\"}}")
                .text("assets/minecraft/models/item/" + name + ".json",
                        "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/" + name + "\"}}")
                .png("assets/minecraft/textures/item/" + name + ".png", 16, 1, rgb);
    }
}
