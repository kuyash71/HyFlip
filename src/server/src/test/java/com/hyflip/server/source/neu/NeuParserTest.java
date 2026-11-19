package com.hyflip.server.source.neu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.hyflip.server.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NeuParserTest {

    static NeuData data;

    @BeforeAll
    static void parse() throws Exception {
        data = new NeuParser(Fixtures.JSON).parse(Fixtures.neuRepo(), "abc");
    }

    @Test
    void readsItemsWithCleanNamesAndRequirements() {
        NeuItem mithril = data.items().get("REFINED_MITHRIL");
        assertThat(mithril.displayName()).isEqualTo("Refined Mithril");
        assertThat(mithril.requirements()).containsExactly(
                new NeuItem.Requirement(NeuItem.Requirement.Kind.HOTM, "HotM", 2));
        assertThat(data.items().get("ENCHANTED_DIAMOND").requirements()).containsExactly(
                new NeuItem.Requirement(NeuItem.Requirement.Kind.OTHER, "Diamond", 4));
        assertThat(data.items().get("SHARPNESS;6").requirements()).isEmpty();
        assertThat(data.items()).containsKeys("SHARPNESS;6", "INK_SACK-3");
    }

    @Test
    void readsRaritiesFromTheLastLoreLine() {
        assertThat(data.items().get("REFINED_MITHRIL").rarity()).isEqualTo("EPIC");
        assertThat(data.items().get("ENCHANTED_DIAMOND").rarity()).isEqualTo("UNCOMMON");
        assertThat(data.items().get("SHARPNESS;6").rarity()).isEqualTo("RARE");
        assertThat(data.items().get("ZOG_NPC").rarity()).isNull();
    }

    @Test
    void keepsTheCraftingGridRowByRow() {
        NeuRecipe mithril = only(NeuRecipe.Type.CRAFTING, "ENCHANTED_MITHRIL");
        assertThat(mithril.grid()).containsExactly(null, "MITHRIL_ORE:32", null, "MITHRIL_ORE:32", "MITHRIL_ORE:32",
                "MITHRIL_ORE:32", null, "MITHRIL_ORE:32", null);
        assertThat(only(NeuRecipe.Type.FORGE, "REFINED_MITHRIL").grid()).isNull();
    }

    @Test
    void takesPetRarityFromTheTierOnlyForPets() throws Exception {
        assertThat(NeuParser.rarity("MOLE;4", null, true)).isEqualTo("LEGENDARY");
        assertThat(NeuParser.rarity("PROTECTION;4", null, false)).isNull();
        assertThat(NeuParser.rarity("X", Fixtures.JSON.readTree("[\"§6§lLEGENDARY BOOTS\", \"\"]"), false))
                .isEqualTo("LEGENDARY");
        assertThat(NeuParser.rarity("X", Fixtures.JSON.readTree("[\"§d§l§ka§r §d§lMYTHIC ACCESSORY §d§l§ka\"]"), false))
                .isEqualTo("MYTHIC");
        assertThat(NeuParser.rarity("X", Fixtures.JSON.readTree("[\"§7Some text about RARE drops\"]"), false))
                .isNull();
        assertThat(NeuParser.rarity("X", Fixtures.JSON.readTree("[\"§c§lVERY SPECIAL\"]"), false))
                .isEqualTo("VERY_SPECIAL");
    }

    @Test
    void readsForgeRecipes() {
        NeuRecipe forge = only(NeuRecipe.Type.FORGE, "REFINED_MITHRIL");
        assertThat(forge.inputs()).containsEntry("ENCHANTED_MITHRIL", 160.0);
        assertThat(forge.durationSeconds()).isEqualTo(21600L);
        assertThat(forge.outputCount()).isEqualTo(1.0);
    }

    @Test
    void sumsDuplicateGridIngredients() {
        List<NeuRecipe> recipes = recipes(NeuRecipe.Type.CRAFTING, "ENCHANTED_DIAMOND");
        assertThat(recipes).isNotEmpty();
        assertThat(recipes.get(0).inputs().get("DIAMOND")).isCloseTo(160.0, within(0.001));
    }

    @Test
    void readsLegacyRecipesWithCount() {
        NeuRecipe worm = only(NeuRecipe.Type.CRAFTING, "WORM_BAIT");
        assertThat(worm.outputCount()).isEqualTo(16.0);
        assertThat(worm.inputs()).containsKeys("WORM_MEMBRANE", "CLAY_BALL");
    }

    @Test
    void readsNpcShopsWithCoins() {
        NeuRecipe shop = only(NeuRecipe.Type.NPC_SHOP, "PET_ITEM_ALL_SKILLS_BOOST_COMMON");
        assertThat(shop.coins()).isEqualTo(50000.0);
        assertThat(shop.inputs()).doesNotContainKey("SKYBLOCK_COIN");
    }

    @Test
    void readsBazaarStocks() {
        assertThat(data.bazaarStocks()).containsEntry("SHARD_CRETAN_BULL", "ATTRIBUTE_SHARD_MYTHOLOGICAL_RESISTANCE;1");
    }

    @Test
    void splitsCompoundRequirements() {
        assertThat(NeuParser.requirements("§cRequires: Gemstone X & HotM 5")).containsExactly(
                new NeuItem.Requirement(NeuItem.Requirement.Kind.OTHER, "Gemstone", 10),
                new NeuItem.Requirement(NeuItem.Requirement.Kind.HOTM, "HotM", 5));
        assertThat(NeuParser.requirements("Requires: Zombie Slayer 3")).containsExactly(
                new NeuItem.Requirement(NeuItem.Requirement.Kind.OTHER, "Zombie Slayer", 3));
        assertThat(NeuParser.requirements("something else")).isEmpty();
    }

    @Test
    void separatesCoinsFromForgeInputs() {
        NeuRecipe chamber = only(NeuRecipe.Type.FORGE, "GEMSTONE_CHAMBER");
        assertThat(chamber.coins()).isEqualTo(25000.0);
        assertThat(chamber.inputs()).containsOnlyKeys("WORM_MEMBRANE", "GEMSTONE_MIXTURE");
    }

    @Test
    void namesPetsByRarity() {
        assertThat(NeuParser.displayName("MOLE;4", "§7[Lvl {LVL}] §6Mole")).isEqualTo("Mole (Legendary)");
        assertThat(NeuParser.displayName("REFINED_MITHRIL", "§5Refined Mithril")).isEqualTo("Refined Mithril");
    }

    @Test
    void parsesRomanNumerals() {
        assertThat(NeuParser.roman("IV")).isEqualTo(4);
        assertThat(NeuParser.roman("IX")).isEqualTo(9);
        assertThat(NeuParser.roman("XIV")).isEqualTo(14);
    }

    private static List<NeuRecipe> recipes(NeuRecipe.Type type, String output) {
        return data.recipes().stream().filter(r -> r.type() == type && r.output().equals(output)).toList();
    }

    private static NeuRecipe only(NeuRecipe.Type type, String output) {
        List<NeuRecipe> list = recipes(type, output);
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    @Test
    void dropsIconGlyphsFromNames() {
        assertThat(NeuParser.displayName("FLAWLESS_PERIDOT_GEM", "\u00a75\ue051 Flawless Peridot Gemstone"))
                .isEqualTo("Flawless Peridot Gemstone");
    }

    @Test
    void namesEnchantmentBooksAfterTheirEnchantment() throws Exception {
        NeuItem book = new NeuParser(Fixtures.JSON).item(Fixtures.JSON.readTree("""
                {"internalname": "LOOTING_NEW;7", "displayname": "\u00a75Enchanted Book",
                 "lore": ["\u00a78Combinable in Anvil", "", "\u00a79Looting VII", "\u00a77Grants ..."]}
                """));
        assertThat(book.displayName()).isEqualTo("Looting VII");
    }
}
