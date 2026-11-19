package com.hyflip.server.icons;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates the 1.8 item names Hypixel's items API uses ({@code material} and {@code durability}, Bukkit names such
 * as {@code INK_SACK:4}) and NEU uses ({@code minecraft:dye}) to today's item IDs ({@code lapis_lazuli}), so vanilla
 * looking SkyBlock items can be drawn with the player's own Minecraft textures.
 *
 * <p>Names that did not change only need lower case; this lists the ones that did.
 */
final class LegacyMaterials {

    private LegacyMaterials() {
    }

    /** Wool, stained glass, clay and carpet colours by durability. */
    private static final List<String> COLOURS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    /** Dyes run the other way round (0 is ink, 15 bone meal). */
    private static final List<String> DYES = List.of("ink_sac", "red_dye", "green_dye", "cocoa_beans", "lapis_lazuli",
            "purple_dye", "cyan_dye", "light_gray_dye", "gray_dye", "pink_dye", "lime_dye", "yellow_dye",
            "light_blue_dye", "magenta_dye", "orange_dye", "bone_meal");

    private static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak");

    /** Items whose kind depends on durability: one name per value, the first for unknown values. */
    private static final Map<String, List<String>> BY_DURABILITY = Map.ofEntries(
            Map.entry("ink_sack", DYES),
            Map.entry("dye", DYES),
            Map.entry("log", List.of("oak_log", "spruce_log", "birch_log", "jungle_log")),
            Map.entry("log_2", List.of("acacia_log", "dark_oak_log")),
            Map.entry("log2", List.of("acacia_log", "dark_oak_log")),
            Map.entry("wood", WOODS.stream().map(w -> w + "_planks").toList()),
            Map.entry("planks", WOODS.stream().map(w -> w + "_planks").toList()),
            Map.entry("sapling", WOODS.stream().map(w -> w + "_sapling").toList()),
            Map.entry("leaves", List.of("oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves")),
            Map.entry("leaves_2", List.of("acacia_leaves", "dark_oak_leaves")),
            Map.entry("raw_fish", List.of("cod", "salmon", "tropical_fish", "pufferfish")),
            Map.entry("fish", List.of("cod", "salmon", "tropical_fish", "pufferfish")),
            Map.entry("cooked_fish", List.of("cooked_cod", "cooked_salmon")),
            Map.entry("coal", List.of("coal", "charcoal")),
            Map.entry("sponge", List.of("sponge", "wet_sponge")),
            Map.entry("stone", List.of("stone", "granite", "polished_granite", "diorite", "polished_diorite", "andesite",
                    "polished_andesite")),
            Map.entry("sand", List.of("sand", "red_sand")),
            Map.entry("dirt", List.of("dirt", "coarse_dirt", "podzol")),
            Map.entry("red_rose", List.of("poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
                    "white_tulip", "pink_tulip", "oxeye_daisy")),
            Map.entry("red_flower", List.of("poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip",
                    "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy")),
            Map.entry("double_plant", List.of("sunflower", "lilac", "tall_grass", "large_fern", "rose_bush", "peony")),
            Map.entry("smooth_brick", List.of("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks",
                    "chiseled_stone_bricks")),
            Map.entry("stonebrick", List.of("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks",
                    "chiseled_stone_bricks")),
            Map.entry("prismarine", List.of("prismarine", "prismarine_bricks", "dark_prismarine")),
            Map.entry("sandstone", List.of("sandstone", "chiseled_sandstone", "cut_sandstone")),
            Map.entry("red_sandstone", List.of("red_sandstone", "chiseled_red_sandstone", "cut_red_sandstone")),
            Map.entry("quartz_block", List.of("quartz_block", "chiseled_quartz_block", "quartz_pillar")),
            Map.entry("golden_apple", List.of("golden_apple", "enchanted_golden_apple")),
            Map.entry("anvil", List.of("anvil", "chipped_anvil", "damaged_anvil")),
            Map.entry("long_grass", List.of("dead_bush", "short_grass", "fern")),
            Map.entry("tallgrass", List.of("dead_bush", "short_grass", "fern")),
            Map.entry("cobble_wall", List.of("cobblestone_wall", "mossy_cobblestone_wall")),
            Map.entry("cobblestone_wall", List.of("cobblestone_wall", "mossy_cobblestone_wall")));

    /** Items that only changed their name. */
    private static final Map<String, String> RENAMED = Map.ofEntries(
            Map.entry("mycel", "mycelium"), Map.entry("ender_stone", "end_stone"),
            Map.entry("brewing_stand_item", "brewing_stand"), Map.entry("exp_bottle", "experience_bottle"),
            Map.entry("experience_bottle", "experience_bottle"),
            Map.entry("raw_chicken", "chicken"), Map.entry("raw_beef", "beef"), Map.entry("pork", "porkchop"),
            Map.entry("grilled_pork", "cooked_porkchop"), Map.entry("melon", "melon_slice"),
            Map.entry("melon_block", "melon"), Map.entry("speckled_melon", "glistering_melon_slice"),
            Map.entry("sulphur", "gunpowder"), Map.entry("nether_stalk", "nether_wart"),
            Map.entry("nether_wart", "nether_wart"), Map.entry("storage_minecart", "chest_minecart"),
            Map.entry("empty_map", "map"), Map.entry("map", "filled_map"), Map.entry("flower_pot_item", "flower_pot"),
            Map.entry("trap_door", "oak_trapdoor"), Map.entry("snow_ball", "snowball"),
            Map.entry("seeds", "wheat_seeds"), Map.entry("carrot_item", "carrot"), Map.entry("potato_item", "potato"),
            Map.entry("water_lily", "lily_pad"), Map.entry("waterlily", "lily_pad"),
            Map.entry("nether_brick_item", "nether_brick"), Map.entry("netherbrick", "nether_brick"),
            Map.entry("eye_of_ender", "ender_eye"), Map.entry("fireball", "fire_charge"),
            Map.entry("iron_barding", "iron_horse_armor"), Map.entry("gold_barding", "golden_horse_armor"),
            Map.entry("diamond_barding", "diamond_horse_armor"), Map.entry("leash", "lead"),
            Map.entry("boat", "oak_boat"), Map.entry("bed", "red_bed"),
            Map.entry("carrot_stick", "carrot_on_a_stick"), Map.entry("web", "cobweb"),
            Map.entry("huge_mushroom_1", "brown_mushroom_block"), Map.entry("huge_mushroom_2", "red_mushroom_block"),
            Map.entry("brown_mushroom_block", "brown_mushroom_block"),
            Map.entry("quartz_ore", "nether_quartz_ore"), Map.entry("enchantment_table", "enchanting_table"),
            Map.entry("workbench", "crafting_table"), Map.entry("crafting_table", "crafting_table"),
            Map.entry("piston_base", "piston"), Map.entry("piston_sticky_base", "sticky_piston"),
            Map.entry("diode", "repeater"), Map.entry("redstone_comparator", "comparator"),
            Map.entry("watch", "clock"), Map.entry("book_and_quill", "writable_book"),
            Map.entry("writable_book", "writable_book"), Map.entry("firework", "firework_rocket"),
            Map.entry("fireworks", "firework_rocket"), Map.entry("firework_charge", "firework_star"),
            Map.entry("mushroom_soup", "mushroom_stew"), Map.entry("thin_glass", "glass_pane"),
            Map.entry("glass_pane", "glass_pane"), Map.entry("hard_clay", "terracotta"),
            Map.entry("hardened_clay", "terracotta"), Map.entry("iron_fence", "iron_bars"),
            Map.entry("fence", "oak_fence"), Map.entry("nether_fence", "nether_brick_fence"),
            Map.entry("skull_item", ""), Map.entry("skull", ""), Map.entry("monster_egg", ""),
            Map.entry("spawn_egg", ""), Map.entry("gold_record", "music_disc_13"),
            Map.entry("green_record", "music_disc_cat"), Map.entry("record_3", "music_disc_blocks"),
            Map.entry("gold_sword", "golden_sword"), Map.entry("gold_axe", "golden_axe"),
            Map.entry("gold_pickaxe", "golden_pickaxe"), Map.entry("gold_spade", "golden_shovel"),
            Map.entry("gold_hoe", "golden_hoe"), Map.entry("gold_helmet", "golden_helmet"),
            Map.entry("gold_chestplate", "golden_chestplate"), Map.entry("gold_leggings", "golden_leggings"),
            Map.entry("gold_boots", "golden_boots"), Map.entry("wood_sword", "wooden_sword"),
            Map.entry("wood_axe", "wooden_axe"), Map.entry("wood_pickaxe", "wooden_pickaxe"),
            Map.entry("wood_spade", "wooden_shovel"), Map.entry("wood_hoe", "wooden_hoe"),
            Map.entry("iron_spade", "iron_shovel"), Map.entry("stone_spade", "stone_shovel"),
            Map.entry("diamond_spade", "diamond_shovel"), Map.entry("wood_door", "oak_door"),
            Map.entry("wooden_door", "oak_door"), Map.entry("yellow_flower", "dandelion"),
            Map.entry("snow", "snow"), Map.entry("reeds", "sugar_cane"), Map.entry("sugar_cane", "sugar_cane"),
            Map.entry("cocoa", "cocoa_beans"), Map.entry("noteblock", "note_block"),
            Map.entry("note_block", "note_block"), Map.entry("jack_o_lantern", "jack_o_lantern"),
            Map.entry("lit_pumpkin", "jack_o_lantern"), Map.entry("pumpkin", "carved_pumpkin"),
            Map.entry("sign", "oak_sign"), Map.entry("cake", "cake"), Map.entry("lapis_ore", "lapis_ore"),
            Map.entry("redstone_lamp_off", "redstone_lamp"), Map.entry("redstone_torch_on", "redstone_torch"),
            Map.entry("stone_plate", "stone_pressure_plate"), Map.entry("wood_plate", "oak_pressure_plate"),
            Map.entry("gold_plate", "light_weighted_pressure_plate"),
            Map.entry("iron_plate", "heavy_weighted_pressure_plate"), Map.entry("wood_button", "oak_button"),
            Map.entry("nether_warts", "nether_wart"), Map.entry("mob_spawner", "spawner"),
            Map.entry("ender_portal_frame", "end_portal_frame"), Map.entry("command", "command_block"),
            Map.entry("dragon_egg", "dragon_egg"), Map.entry("rabbit_foot", "rabbit_foot"));

    /** Colours by durability for these 1.8 names; the result is {@code <colour>_<suffix>}. */
    private static final Map<String, String> COLOURED = Map.of(
            "wool", "wool", "stained_glass", "stained_glass", "stained_glass_pane", "stained_glass_pane",
            "stained_clay", "terracotta", "carpet", "carpet", "banner", "banner", "concrete", "concrete");

    /**
     * Today's item ID for a 1.8 item, or an empty string for items drawn as player heads or spawn eggs, which have
     * no texture of their own.
     *
     * @param material   a Bukkit name ({@code INK_SACK}) or a NEU registry name ({@code minecraft:dye})
     * @param durability the item's damage value, null for 0
     */
    static String modern(String material, Integer durability) {
        if (material == null || material.isBlank()) {
            return "";
        }
        String name = material.toLowerCase(Locale.ROOT);
        name = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
        int d = durability == null ? 0 : durability;
        List<String> variants = BY_DURABILITY.get(name);
        if (variants != null) {
            return d >= 0 && d < variants.size() ? variants.get(d) : variants.get(0);
        }
        String suffix = COLOURED.get(name);
        if (suffix != null) {
            return COLOURS.get(d >= 0 && d < COLOURS.size() ? d : 0) + "_" + suffix;
        }
        return RENAMED.getOrDefault(name, name);
    }
}
