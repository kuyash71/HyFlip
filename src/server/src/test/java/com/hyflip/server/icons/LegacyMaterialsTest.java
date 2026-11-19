package com.hyflip.server.icons;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LegacyMaterialsTest {

    @Test
    void translatesOneEightNamesToTodaysItems() {
        assertThat(LegacyMaterials.modern("BONE", null)).isEqualTo("bone");
        assertThat(LegacyMaterials.modern("INK_SACK", 4)).isEqualTo("lapis_lazuli");
        assertThat(LegacyMaterials.modern("INK_SACK", null)).isEqualTo("ink_sac");
        assertThat(LegacyMaterials.modern("LOG", 3)).isEqualTo("jungle_log");
        assertThat(LegacyMaterials.modern("LOG_2", 1)).isEqualTo("dark_oak_log");
        assertThat(LegacyMaterials.modern("RAW_FISH", 1)).isEqualTo("salmon");
        assertThat(LegacyMaterials.modern("WOOL", 14)).isEqualTo("red_wool");
        assertThat(LegacyMaterials.modern("STAINED_CLAY", 99)).isEqualTo("white_terracotta");
        assertThat(LegacyMaterials.modern("SULPHUR", null)).isEqualTo("gunpowder");
        assertThat(LegacyMaterials.modern("MELON", null)).isEqualTo("melon_slice");
        assertThat(LegacyMaterials.modern("GOLD_CHESTPLATE", null)).isEqualTo("golden_chestplate");
        assertThat(LegacyMaterials.modern("minecraft:enchanted_book", null)).isEqualTo("enchanted_book");
        assertThat(LegacyMaterials.modern("minecraft:dye", 4)).isEqualTo("lapis_lazuli");
    }

    @Test
    void playerHeadsAndUnknownMaterialsHaveNoTexture() {
        assertThat(LegacyMaterials.modern("SKULL_ITEM", 3)).isEmpty();
        assertThat(LegacyMaterials.modern("minecraft:skull", 3)).isEmpty();
        assertThat(LegacyMaterials.modern(null, null)).isEmpty();
        assertThat(LegacyMaterials.modern(" ", null)).isEmpty();
    }
}
