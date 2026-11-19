package com.hyflip.server.icons;

import static com.hyflip.server.icons.TestPacks.BLUE;
import static com.hyflip.server.icons.TestPacks.GREEN;
import static com.hyflip.server.icons.TestPacks.RED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackIconsTest {

    @TempDir
    Path tmp;

    /** Hypixel's items API: a pack item, a renamed pack file, a glowing vanilla dye, and a player head. */
    static final Map<String, PackIcons.Look> LOOKS = Map.of(
            "ASPECT_OF_THE_END", new PackIcons.Look("hypixel_skyblock:item/uncategorized/aspect_of_the_end", "DIAMOND_SWORD", null, false),
            "BAT_WAND", new PackIcons.Look("hypixel_skyblock:item/dungeons/spirit_sceptre", "PAPER", null, false),
            "ENCHANTED_LAPIS_LAZULI", new PackIcons.Look(null, "INK_SACK", 4, true),
            "ENCHANTED_STONE", new PackIcons.Look(null, "STONE", null, true),
            "TALISMAN", new PackIcons.Look(null, "SKULL_ITEM", 3, false));
    /** NEU: an enchanted book the items API does not list, and an item only matched by its pack file name. */
    static final Map<String, String> NEU = Map.of("FIRST_STRIKE;5", "minecraft:enchanted_book", "WOB", "minecraft:paper");

    PackIcons icons(Optional<Path> client, List<Path> roots) {
        return new PackIcons(tmp.resolve("data/icons"), PackReaderTest.JSON, () -> LOOKS, () -> NEU, () -> roots,
                () -> client);
    }

    Path pack(String name) throws Exception {
        return TestPacks.skyblock()
                .flatItem("uncategorized/aspect_of_the_end", RED)
                .flatItem("dungeons/spirit_sceptre", GREEN)
                .flatItem("rift/wob", BLUE)
                .flatItem("uncategorized/not_an_item", BLUE)
                .write(tmp.resolve(name));
    }

    @Test
    void buildsAnAtlasFromThePackAndTheClientJar() throws Exception {
        Path jar = TestPacks.client().write(tmp.resolve("client.jar"));
        PackIcons icons = icons(Optional.of(jar), List.of());
        PackIcons.IconIndex index = icons.importFrom(pack("p.zip"));

        assertThat(index.items()).containsOnlyKeys("ASPECT_OF_THE_END", "BAT_WAND", "WOB", "ENCHANTED_LAPIS_LAZULI",
                "ENCHANTED_STONE", "FIRST_STRIKE;5");
        assertThat(index.fromPack()).isEqualTo(3);
        assertThat(index.client()).isEqualTo(jar.toString());
        assertThat(index.glint()).containsExactlyInAnyOrder("ENCHANTED_LAPIS_LAZULI", "ENCHANTED_STONE", "FIRST_STRIKE;5");
        assertThat(index.cell()).isEqualTo(PackIcons.CELL);

        BufferedImage atlas = ImageIO.read(icons.atlas().toFile());
        assertThat(atlas.getWidth()).isEqualTo(index.columns() * PackIcons.CELL);
        assertThat(colourOf(atlas, index, "BAT_WAND")).isEqualTo(GREEN);
        assertThat(colourOf(atlas, index, "ENCHANTED_LAPIS_LAZULI")).isEqualTo(BLUE);

        // a restart reads the index back
        assertThat(icons(Optional.of(jar), List.of()).index()).contains(index);
    }

    @Test
    void withoutAClientJarOnlyPackItemsGetIcons() throws Exception {
        PackIcons.IconIndex index = icons(Optional.empty(), List.of()).importFrom(pack("p.zip"));
        assertThat(index.items()).containsOnlyKeys("ASPECT_OF_THE_END", "BAT_WAND", "WOB");
        assertThat(index.client()).isNull();
    }

    @Test
    void rejectsOtherFilesAndClearsIcons() throws Exception {
        PackIcons icons = icons(Optional.empty(), List.of());
        Path other = new TestPacks().text("pack.mcmeta", "{}").write(tmp.resolve("other.zip"));
        assertThatThrownBy(() -> icons.importFrom(other)).hasMessageContaining("not the Hypixel SkyBlock");
        assertThatThrownBy(() -> icons.importFrom(tmp.resolve("missing.zip"))).hasMessageContaining("No file");

        icons.importFrom(pack("p.zip"));
        icons.clear();
        assertThat(icons.index()).isEmpty();
        assertThat(icons.atlas()).doesNotExist();
    }

    @Test
    void scalesTexturesByWholeStepsOnly() {
        BufferedImage small = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        small.setRGB(0, 0, RED);
        BufferedImage fitted = PackIcons.fit(small, 32);
        assertThat(fitted.getRGB(0, 0)).isEqualTo(RED);
        assertThat(fitted.getRGB(1, 1)).isEqualTo(RED);
        assertThat(fitted.getRGB(2, 2)).isNotEqualTo(RED);

        BufferedImage odd = new BufferedImage(15, 15, BufferedImage.TYPE_INT_ARGB);
        odd.setRGB(0, 0, RED);
        // 15 → 30, centred with a 1 px margin
        assertThat(PackIcons.fit(odd, 32).getRGB(1, 1)).isEqualTo(RED);
        assertThat(PackIcons.fit(odd, 32).getRGB(0, 0) >>> 24).isZero();

        BufferedImage big = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        big.setRGB(2, 2, RED);
        assertThat(PackIcons.fit(big, 32).getRGB(1, 1)).isEqualTo(RED);
    }

    @Test
    void findsPacksInMinecraftFoldersAndRefreshesFromANewerOne() throws Exception {
        Path minecraft = tmp.resolve("home/.minecraft");
        Path prism = tmp.resolve("home/prism/instances");
        Path old = pack("old.zip");
        Path downloaded = minecraft.resolve("downloads/242681e6/526bd87b");
        Files.createDirectories(downloaded.getParent());
        Files.copy(old, downloaded);
        Files.setLastModifiedTime(downloaded, FileTime.fromMillis(1_000_000));
        // a newer pack in a launcher instance
        Path instance = TestPacks.skyblock().flatItem("uncategorized/aspect_of_the_end", GREEN)
                .write(prism.resolve("Skyblock/minecraft/resourcepacks/Hypixel.zip"));
        Files.setLastModifiedTime(instance, FileTime.fromMillis(2_000_000));
        Files.writeString(minecraft.resolve("downloads/242681e6/notes.txt"), "x".repeat(100_000));

        PackIcons icons = icons(Optional.empty(), List.of(minecraft, prism, tmp.resolve("nowhere")));
        List<PackIcons.Candidate> found = icons.detect();
        assertThat(found).extracting(PackIcons.Candidate::path).containsExactly(instance.toString(), downloaded.toString());

        icons.importFrom(downloaded);
        assertThat(icons.index().orElseThrow().source()).isEqualTo(downloaded.toString());
        icons.refresh();
        assertThat(icons.index().orElseThrow().source()).isEqualTo(instance.toString());
    }

    @Test
    void rebuildsWhenNeuArrivesAfterThePack() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> neu = new java.util.concurrent.atomic.AtomicReference<>(Map.of());
        Path jar = TestPacks.client().write(tmp.resolve("client.jar"));
        PackIcons icons = new PackIcons(tmp.resolve("data/icons"), PackReaderTest.JSON, () -> LOOKS, neu::get,
                List::of, () -> Optional.of(jar));
        icons.importFrom(pack("p.zip"));
        assertThat(icons.index().orElseThrow().items()).doesNotContainKey("FIRST_STRIKE;5");

        neu.set(NEU);
        icons.onNeuUpdated();
        assertThat(icons.index().orElseThrow().items()).containsKeys("FIRST_STRIKE;5", "WOB");
        assertThat(icons.index().orElseThrow().source()).isEqualTo(tmp.resolve("p.zip").toString());
    }

    @Test
    void refreshDoesNothingBeforeAPackIsLoaded() {
        PackIcons icons = icons(Optional.empty(), List.of(tmp));
        icons.refresh();
        assertThat(icons.index()).isEmpty();
    }

    @Test
    void ordersClientVersionsByNumber() {
        assertThat(PackIcons.versionKey("26.3")).isGreaterThan(PackIcons.versionKey("1.21.11"));
        assertThat(PackIcons.versionKey("1.21.11")).isGreaterThan(PackIcons.versionKey("1.21.4"));
        assertThat(PackIcons.versionKey("25w45a")).isEmpty();
    }

    private static int colourOf(BufferedImage atlas, PackIcons.IconIndex index, String id) {
        int cell = index.items().get(id);
        int x = (cell % index.columns()) * index.cell() + index.cell() / 2;
        int y = (cell / index.columns()) * index.cell() + index.cell() / 2;
        return atlas.getRGB(x, y);
    }
}
