package com.hyflip.server.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cost and value of one recipe run at current prices.
 *
 * @param inputs        one line per ingredient with how it is obtained (bought or crafted)
 * @param coins         coins the recipe itself costs (NPC shops, some forge recipes)
 * @param cost          coins + every priced ingredient
 * @param output        how the output is sold, null when no price is known
 * @param value         output gross value before fees, 0 if unknown
 * @param fees          sell fees on the output
 * @param netProfit     value − fees − cost
 * @param missingPrices item IDs without any price; the sheet is incomplete if not empty
 */
public record CostSheet(List<Line> inputs, double coins, double cost, Quote output, double value, double fees,
                        double netProfit, List<String> missingPrices) {

    /**
     * @param quote the purchase if bought, otherwise what buying would cost; null if not buyable
     * @param plan  how the ingredient is obtained
     */
    public record Line(String itemId, double quantity, Quote quote, Plan plan) {
    }

    public boolean complete() {
        return missingPrices.isEmpty();
    }

    /** Prices a recipe buying every input and selling the output. */
    public static CostSheet of(Map<String, Double> inputs, double coins, String outputId, double outputCount,
                               Market market, PriceSource source, Fees fees) {
        return of(inputs, coins, outputId, outputCount, market, source, fees, CraftPlanner.buyOnly(market, source));
    }

    /** Prices a recipe, obtaining each input the cheapest way the planner finds, and sells the output. */
    public static CostSheet of(Map<String, Double> inputs, double coins, String outputId, double outputCount,
                               Market market, PriceSource source, Fees fees, CraftPlanner planner) {
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String, Double> in : inputs.entrySet()) {
            Plan plan = planner.plan(in.getKey(), in.getValue(), Set.of(outputId));
            lines.add(new Line(in.getKey(), in.getValue(), plan.quote(), plan));
        }
        Quote out = Pricing.sell(market, outputId, outputCount, source, fees).orElse(null);
        return of(lines, coins, outputId, out);
    }

    /** Totals for inputs already planned and an output already quoted (null if it has no price). */
    public static CostSheet of(List<Line> lines, double coins, String outputId, Quote out) {
        List<String> missing = new ArrayList<>();
        double cost = coins;
        for (Line line : lines) {
            if (line.plan().method() == Plan.Method.MISSING) {
                missing.add(line.itemId());
            } else {
                cost += line.plan().cost();
            }
        }
        if (out == null) {
            missing.add(outputId);
        }
        double value = out == null ? 0 : out.gross();
        double fee = out == null ? 0 : out.fees();
        return new CostSheet(List.copyOf(lines), coins, cost, out, value, fee, value - fee - cost, List.copyOf(missing));
    }
}
