package com.hyflip.server.flipper;

import com.hyflip.server.engine.CraftPlanner;
import com.hyflip.server.source.neu.NeuData;
import com.hyflip.server.source.neu.NeuItem;
import com.hyflip.server.source.neu.NeuRecipe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** NEU recipes indexed for the flippers: by type and, for crafting, by output. */
public final class RecipeBook {

    public static final RecipeBook EMPTY = of(new NeuData("", Instant.EPOCH, Map.of(), List.of(), Map.of()));

    private final NeuData data;
    private final Map<NeuRecipe.Type, List<NeuRecipe>> byType;
    private final Map<String, List<CraftPlanner.Recipe>> craftingByOutput;

    private RecipeBook(NeuData data) {
        this.data = data;
        Map<NeuRecipe.Type, List<NeuRecipe>> types = new HashMap<>();
        Map<String, List<CraftPlanner.Recipe>> crafting = new HashMap<>();
        for (NeuRecipe r : data.recipes()) {
            types.computeIfAbsent(r.type(), t -> new ArrayList<>()).add(r);
            if (r.type() == NeuRecipe.Type.CRAFTING && !r.inputs().isEmpty()) {
                crafting.computeIfAbsent(r.output(), o -> new ArrayList<>()).add(toPlanner(r));
            }
        }
        types.replaceAll((t, list) -> List.copyOf(list));
        crafting.replaceAll((o, list) -> List.copyOf(list));
        this.byType = Map.copyOf(types);
        this.craftingByOutput = Map.copyOf(crafting);
    }

    public static RecipeBook of(NeuData data) {
        return new RecipeBook(data);
    }

    public NeuData data() {
        return data;
    }

    public List<NeuRecipe> recipes(NeuRecipe.Type type) {
        return byType.getOrDefault(type, List.of());
    }

    /** Recipes of a type that make {@code output}. */
    public List<NeuRecipe> recipes(NeuRecipe.Type type, String output) {
        return recipes(type).stream().filter(r -> r.output().equals(output)).toList();
    }

    /** Crafting recipes that make {@code output}, for the {@link CraftPlanner}. */
    public List<CraftPlanner.Recipe> crafting(String output) {
        return craftingByOutput.getOrDefault(output, List.of());
    }

    public List<NeuItem.Requirement> requirements(String itemId) {
        NeuItem item = data.items().get(itemId);
        return item == null ? List.of() : item.requirements();
    }

    /** Display name, or the ID if NEU has none. */
    public String name(String itemId) {
        NeuItem item = data.items().get(itemId);
        return item != null && item.displayName() != null ? item.displayName() : itemId;
    }

    public static CraftPlanner.Recipe toPlanner(NeuRecipe r) {
        return new CraftPlanner.Recipe(r.output(), r.outputCount(), r.inputs(), r.coins());
    }
}
