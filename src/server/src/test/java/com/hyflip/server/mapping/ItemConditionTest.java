package com.hyflip.server.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItemConditionTest {

    @Test
    void upgradesMakeAnItemUnclean() {
        assertThat(ItemCondition.isClean("YETI_SWORD", Map.of("modifier", "heroic", "uuid", "x"))).isTrue();
        assertThat(ItemCondition.isClean("YETI_SWORD", Map.of("enchantments", Map.of("sharpness", 6)))).isFalse();
        assertThat(ItemCondition.isClean("YETI_SWORD", Map.of("rarity_upgrades", 1))).isFalse();
        assertThat(ItemCondition.isClean("YETI_SWORD", Map.of("hot_potato_count", 10))).isFalse();
        assertThat(ItemCondition.isClean("YETI_SWORD", Map.of("upgrade_level", 0))).as("zero stars").isTrue();
        assertThat(ItemCondition.isClean("YETI_SWORD", Map.of("enchantments", Map.of()))).as("empty").isTrue();
    }

    @Test
    void petsAndBooksAreValuedByTheirId() {
        assertThat(ItemCondition.isClean("ENCHANTED_BOOK", Map.of("enchantments", Map.of("looting", 5)))).isTrue();
        assertThat(ItemCondition.isClean("PET", Map.of("petInfo", "{}"))).isTrue();
    }

    @Test
    void readsCoflnetsFlattenedForm() {
        assertThat(ItemCondition.isCleanFlattened("STARRED_YETI_SWORD", List.of(), Map.of("uid", "a", "uuid", "b")))
                .isTrue();
        assertThat(ItemCondition.isCleanFlattened("STARRED_YETI_SWORD", List.of(Map.of("type", "luck")), Map.of()))
                .isFalse();
        assertThat(ItemCondition.isCleanFlattened("STARRED_YETI_SWORD", List.of(), Map.of("hpc", "10"))).isFalse();
        assertThat(ItemCondition.isCleanFlattened("STARRED_YETI_SWORD", List.of(), Map.of("SAPPHIRE_0", "FINE")))
                .isFalse();
        assertThat(ItemCondition.isCleanFlattened("STARRED_YETI_SWORD", List.of(), Map.of("upgrade_level", "1")))
                .isFalse();
        assertThat(ItemCondition.isCleanFlattened("STARRED_YETI_SWORD", List.of(), Map.of("dungeon_item", "1")))
                .as("dungeonised but not starred").isTrue();
    }
}
