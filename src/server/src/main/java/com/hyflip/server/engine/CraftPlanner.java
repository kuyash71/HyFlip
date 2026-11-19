package com.hyflip.server.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Chooses, for each ingredient, the cheaper of buying it and crafting it from its own ingredients,
 * down to a fixed depth. Cycles (iron ingot ↔ iron block) are cut: an item is never used, bought or
 * crafted, inside its own production chain, and the flip's output never appears in its inputs' plans.
 * Quantities are fractional when a recipe yields several units, so costs are per unit.
 *
 * <p>Not thread-safe; create one per calculation (it memoises unit plans).
 */
public final class CraftPlanner {

    /** Crafting levels below a recipe input. */
    public static final int DEFAULT_DEPTH = 3;

    /** A crafting recipe: {@code outputCount} units of {@code output} from {@code inputs} plus {@code coins}. */
    public record Recipe(String output, double outputCount, Map<String, Double> inputs, double coins) {
    }

    private final Market market;
    private final PriceSource source;
    private final Function<String, List<Recipe>> recipes;
    private final Predicate<Recipe> allowed;
    private final int depth;
    private final Map<String, Plan> memo = new HashMap<>();

    /**
     * @param recipes crafting recipes that make an item
     * @param allowed recipes the player may use (unlocked)
     * @param depth   crafting levels to consider; 0 means always buy
     */
    public CraftPlanner(Market market, PriceSource source, Function<String, List<Recipe>> recipes,
                        Predicate<Recipe> allowed, int depth) {
        this.market = market;
        this.source = source;
        this.recipes = recipes;
        this.allowed = allowed;
        this.depth = depth;
    }

    /** A planner that only buys. */
    public static CraftPlanner buyOnly(Market market, PriceSource source) {
        return new CraftPlanner(market, source, id -> List.of(), r -> false, 0);
    }

    /** Cheapest way to get {@code quantity} of {@code itemId}; {@code exclude} items never appear below it. */
    public Plan plan(String itemId, double quantity, Set<String> exclude) {
        return unit(itemId, depth, new HashSet<>(exclude)).plan().scale(quantity);
    }

    /** @param cut true if a cycle was cut below, so the plan depends on the path and is not memoised */
    private record Result(Plan plan, boolean cut) {
    }

    private Result unit(String itemId, int levels, Set<String> visiting) {
        String key = itemId + '|' + levels;
        Plan cached = memo.get(key);
        if (cached != null && !cached.touches(visiting)) {
            return new Result(cached, false);
        }
        if (visiting.contains(itemId)) {
            // The item is being made further up (or is the flip's output): using it here is circular.
            return new Result(Plan.missing(itemId, 1), true);
        }
        Quote buy = Pricing.buy(market, itemId, 1, source).orElse(null);
        Plan best = buy == null ? null : Plan.buy(buy);
        boolean cut = false;
        if (levels > 0) {
            visiting.add(itemId);
            for (Recipe r : recipes.apply(itemId)) {
                if (r.outputCount() <= 0 || !allowed.test(r)) {
                    continue;
                }
                double crafts = 1 / r.outputCount();
                double cost = r.coins() * crafts;
                List<Plan> parts = new ArrayList<>();
                boolean complete = true;
                for (Map.Entry<String, Double> in : r.inputs().entrySet()) {
                    Result sub = unit(in.getKey(), levels - 1, visiting);
                    cut |= sub.cut();
                    if (sub.plan().method() == Plan.Method.MISSING) {
                        complete = false;
                        break;
                    }
                    Plan part = sub.plan().scale(in.getValue() * crafts);
                    parts.add(part);
                    cost += part.cost();
                }
                if (complete && (best == null || cost < best.cost() - 1e-9)) {
                    best = new Plan(itemId, 1, Plan.Method.CRAFT, cost, buy, crafts, r.coins() * crafts,
                            List.copyOf(parts));
                }
            }
            visiting.remove(itemId);
        }
        if (best == null) {
            best = Plan.missing(itemId, 1);
        }
        if (!cut) {
            memo.put(key, best);
        }
        return new Result(best, cut);
    }
}
