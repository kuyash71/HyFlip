package com.hyflip.server.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.hyflip.server.engine.CraftPlanner.Recipe;
import com.hyflip.server.engine.Market.BazaarQuote;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CraftPlannerTest {

    static BazaarQuote price(double insta, double order) {
        return new BazaarQuote(insta, order, 70_000, 70_000, 1);
    }

    static final Map<String, List<Recipe>> RECIPES = Map.of(
            "E_DIAMOND", List.of(new Recipe("E_DIAMOND", 1, Map.of("DIAMOND", 160.0), 0)),
            "E_DIAMOND_BLOCK", List.of(new Recipe("E_DIAMOND_BLOCK", 1, Map.of("E_DIAMOND", 160.0), 0)),
            "IRON_BLOCK", List.of(new Recipe("IRON_BLOCK", 1, Map.of("IRON", 9.0), 0)),
            "IRON", List.of(new Recipe("IRON", 9, Map.of("IRON_BLOCK", 1.0), 0)),
            "BAIT", List.of(new Recipe("BAIT", 16, Map.of("MEMBRANE", 1.0), 100)));

    static CraftPlanner planner(Market m, int depth, Set<String> locked) {
        Function<String, List<Recipe>> recipes = id -> RECIPES.getOrDefault(id, List.of());
        return new CraftPlanner(m, PriceSource.ORDER, recipes, r -> !locked.contains(r.output()), depth);
    }

    @Test
    void craftsWhenCheaperThanBuying() {
        Market m = new StaticMarket(Map.of("DIAMOND", price(9, 8), "E_DIAMOND", price(1500, 1400)), Map.of(), Map.of());
        Plan plan = planner(m, 3, Set.of()).plan("E_DIAMOND", 2, Set.of());
        assertThat(plan.method()).isEqualTo(Plan.Method.CRAFT);
        assertThat(plan.cost()).isEqualTo(2 * 160 * 8);
        assertThat(plan.savings()).isEqualTo(2 * 1400 - 2 * 160 * 8.0);
        assertThat(plan.purchases().get("DIAMOND").quantity()).isEqualTo(320);
        assertThat(plan.craftSteps()).extracting(Plan::itemId).containsExactly("E_DIAMOND");
    }

    @Test
    void buysWhenCheaperLockedOrTooDeep() {
        Market cheap = new StaticMarket(Map.of("DIAMOND", price(9, 8), "E_DIAMOND", price(1100, 1000)), Map.of(), Map.of());
        assertThat(planner(cheap, 3, Set.of()).plan("E_DIAMOND", 1, Set.of()).method()).isEqualTo(Plan.Method.BUY);

        Market dear = new StaticMarket(Map.of("DIAMOND", price(9, 8), "E_DIAMOND", price(1500, 1400)), Map.of(), Map.of());
        assertThat(planner(dear, 3, Set.of("E_DIAMOND")).plan("E_DIAMOND", 1, Set.of()).method())
                .isEqualTo(Plan.Method.BUY);
        assertThat(planner(dear, 0, Set.of()).plan("E_DIAMOND", 1, Set.of()).method()).isEqualTo(Plan.Method.BUY);
    }

    @Test
    void craftsSeveralLevelsDeepWithinTheDepth() {
        Market m = new StaticMarket(Map.of("DIAMOND", price(9, 8)), Map.of(), Map.of());
        Plan two = planner(m, 2, Set.of()).plan("E_DIAMOND_BLOCK", 1, Set.of());
        assertThat(two.method()).isEqualTo(Plan.Method.CRAFT);
        assertThat(two.cost()).isEqualTo(160 * 160 * 8);
        assertThat(two.craftSteps()).extracting(Plan::itemId).containsExactly("E_DIAMOND", "E_DIAMOND_BLOCK");
        assertThat(planner(m, 1, Set.of()).plan("E_DIAMOND_BLOCK", 1, Set.of()).method())
                .isEqualTo(Plan.Method.MISSING);
    }

    @Test
    void cutsCyclesAndHonoursExclusions() {
        // Blocks are cheap: 9 iron cost 90 bought, but a block (yielding 9) costs 50.
        Market m = new StaticMarket(Map.of("IRON", price(10, 10), "IRON_BLOCK", price(50, 50)), Map.of(), Map.of());
        CraftPlanner p = planner(m, 3, Set.of());
        Plan iron = p.plan("IRON", 9, Set.of());
        assertThat(iron.method()).isEqualTo(Plan.Method.CRAFT);
        assertThat(iron.cost()).isCloseTo(50, within(1e-9));
        // The memoised plan for IRON goes through IRON_BLOCK, so it must not be reused when blocks are excluded.
        assertThat(p.plan("IRON", 9, Set.of("IRON_BLOCK")).method()).isEqualTo(Plan.Method.BUY);
        assertThat(p.plan("IRON_BLOCK", 1, Set.of()).method()).isEqualTo(Plan.Method.BUY);
    }

    @Test
    void recipesYieldingSeveralUnitsGiveFractionalCrafts() {
        Market m = new StaticMarket(Map.of("MEMBRANE", price(1000, 900), "BAIT", price(200, 190)), Map.of(), Map.of());
        Plan bait = planner(m, 1, Set.of()).plan("BAIT", 8, Set.of());
        assertThat(bait.method()).isEqualTo(Plan.Method.CRAFT);
        assertThat(bait.crafts()).isEqualTo(0.5);
        assertThat(bait.cost()).isCloseTo(0.5 * (900 + 100), within(1e-9));
    }
}
