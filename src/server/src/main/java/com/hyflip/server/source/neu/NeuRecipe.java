package com.hyflip.server.source.neu;

import java.util.List;
import java.util.Map;

/**
 * A recipe from the NEU repository. Amounts are doubles because NEU writes some as {@code 10.0}.
 *
 * @param type            recipe kind
 * @param output          canonical ID of the result
 * @param outputCount     result amount
 * @param inputs          canonical ID to amount, duplicates in a crafting grid summed
 * @param coins           coin cost (NPC shops, some forge recipes), 0 if none
 * @param durationSeconds base forge duration, null for other types
 * @param sourceItem      NEU file the recipe was read from
 * @param grid            crafting only: the nine cells A1, A2, A3, B1 … C3 as {@code ID:amount}, null for an
 *                        empty cell; null for other types
 */
public record NeuRecipe(Type type, String output, double outputCount, Map<String, Double> inputs, double coins,
                        Long durationSeconds, String sourceItem, List<String> grid) {

    public enum Type { FORGE, CRAFTING, NPC_SHOP }

    public NeuRecipe {
        grid = grid == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(grid));
    }

    public NeuRecipe(Type type, String output, double outputCount, Map<String, Double> inputs, double coins,
                     Long durationSeconds, String sourceItem) {
        this(type, output, outputCount, inputs, coins, durationSeconds, sourceItem, null);
    }
}
