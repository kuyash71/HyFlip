package com.hyflip.server.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How to obtain a quantity of one item: buy it, craft it from cheaper parts, or no known way.
 *
 * @param itemId   canonical item ID
 * @param quantity units needed
 * @param method   BUY, CRAFT or MISSING
 * @param cost     coins for {@code quantity} units (0 if MISSING)
 * @param quote    BUY: the purchase; CRAFT: what buying would cost instead (null if not buyable)
 * @param crafts   CRAFT: how many times the recipe runs (fractional when it yields several units)
 * @param coins    CRAFT: coins the recipe itself costs
 * @param inputs   CRAFT: plans for the recipe inputs
 */
public record Plan(String itemId, double quantity, Method method, double cost, Quote quote, double crafts,
                   double coins, List<Plan> inputs) {

    public enum Method { BUY, CRAFT, MISSING }

    public static Plan buy(Quote q) {
        return new Plan(q.itemId(), q.quantity(), Method.BUY, q.gross(), q, 0, 0, List.of());
    }

    public static Plan missing(String itemId, double quantity) {
        return new Plan(itemId, quantity, Method.MISSING, 0, null, 0, 0, List.of());
    }

    /** The same plan for {@code factor} times the quantity. */
    public Plan scale(double factor) {
        Quote q = quote == null ? null : new Quote(quote.itemId(), quote.quantity() * factor, quote.venue(),
                quote.unitPrice(), quote.gross() * factor, quote.fees() * factor, quote.priceAt(), quote.estimated());
        List<Plan> scaled = inputs.stream().map(p -> p.scale(factor)).toList();
        return new Plan(itemId, quantity * factor, method, cost * factor, q, crafts * factor, coins * factor, scaled);
    }

    /** Coins saved by crafting instead of buying; null unless crafted and buyable. */
    public Double savings() {
        return method == Method.CRAFT && quote != null ? quote.gross() - cost : null;
    }

    /** Purchases at the leaves of the plan, summed per item. */
    public Map<String, Quote> purchases() {
        Map<String, Quote> out = new LinkedHashMap<>();
        collectPurchases(out);
        return out;
    }

    private void collectPurchases(Map<String, Quote> out) {
        if (method == Method.BUY) {
            out.merge(itemId, quote, (a, b) -> new Quote(a.itemId(), a.quantity() + b.quantity(), a.venue(),
                    a.unitPrice(), a.gross() + b.gross(), 0, a.priceAt(), a.estimated() || b.estimated()));
        }
        inputs.forEach(p -> p.collectPurchases(out));
    }

    /** Crafted steps, deepest first, so they can be done in order. */
    public List<Plan> craftSteps() {
        List<Plan> out = new ArrayList<>();
        collectCrafts(out);
        return out;
    }

    private void collectCrafts(List<Plan> out) {
        inputs.forEach(p -> p.collectCrafts(out));
        if (method == Method.CRAFT) {
            out.add(this);
        }
    }

    /** True if this plan or any part of it involves one of {@code itemIds}. */
    boolean touches(Set<String> itemIds) {
        return itemIds.contains(itemId) || inputs.stream().anyMatch(p -> p.touches(itemIds));
    }
}
