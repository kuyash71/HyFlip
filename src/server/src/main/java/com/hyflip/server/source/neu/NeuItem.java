package com.hyflip.server.source.neu;

import java.util.List;

/**
 * An item from the NEU repository.
 *
 * @param internalName canonical ID, for example {@code REFINED_MITHRIL} or {@code SHARPNESS;6}
 * @param displayName  name without Minecraft formatting codes
 * @param requirements unlock requirements parsed from {@code crafttext} (all must be met); empty if none
 * @param island       NEU island ID for NPCs ({@code hub}, {@code mining_3}), null for other items
 * @param rarity       rarity from the last lore line ({@code LEGENDARY}), null if the lore has none
 * @param material     the 1.8 Minecraft item it is drawn as ({@code minecraft:enchanted_book}), null if unknown
 */
public record NeuItem(String internalName, String displayName, List<Requirement> requirements, String island,
                      String rarity, String material) {

    public NeuItem {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    public NeuItem(String internalName, String displayName, List<Requirement> requirements, String island,
                   String rarity) {
        this(internalName, displayName, requirements, island, rarity, null);
    }

    public NeuItem(String internalName, String displayName, List<Requirement> requirements, String island) {
        this(internalName, displayName, requirements, island, null);
    }

    public NeuItem(String internalName, String displayName, List<Requirement> requirements) {
        this(internalName, displayName, requirements, null, null);
    }

    /**
     * One part of {@code crafttext} such as "Requires: HotM 2", "Requires: Diamond IV" or
     * "Requires: Gemstone X &amp; HotM 5" (two requirements).
     *
     * @param kind  HOTM for Heart of the Mountain, OTHER for collections, slayers and the rest
     * @param name  what is required ("HotM", "Diamond", "Zombie Slayer")
     * @param level required level or tier
     */
    public record Requirement(Kind kind, String name, int level) {

        public enum Kind { HOTM, OTHER }
    }
}
