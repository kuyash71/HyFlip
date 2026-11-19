package com.hyflip.server.source.neu;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Parsed snapshot of the NEU repository.
 *
 * @param commit       repository commit SHA
 * @param loadedAt     when it was parsed
 * @param items        items by internal name
 * @param recipes      forge, crafting and NPC shop recipes
 * @param bazaarStocks Bazaar product ID to NEU internal name ({@code constants/bazaarstocks.json})
 */
public record NeuData(String commit, Instant loadedAt, Map<String, NeuItem> items, List<NeuRecipe> recipes,
                      Map<String, String> bazaarStocks) {

    public long recipeCount(NeuRecipe.Type type) {
        return recipes.stream().filter(r -> r.type() == type).count();
    }
}
