package com.hyflip.server.flipper.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyflip.server.alert.AlertType;
import com.hyflip.server.engine.CraftPlanner;
import com.hyflip.server.engine.EngineFixtures;
import com.hyflip.server.engine.Market;
import com.hyflip.server.engine.Market.BazaarQuote;
import com.hyflip.server.engine.PriceSource;
import com.hyflip.server.engine.StaticMarket;
import com.hyflip.server.flipper.FlipContext;
import com.hyflip.server.flipper.FlipQuery;
import com.hyflip.server.flipper.FlipSort;
import com.hyflip.server.flipper.FlipSupport;
import com.hyflip.server.profile.CollectionCatalog;
import com.hyflip.server.profile.PlayerContext;
import com.hyflip.server.flipper.RecipeBook;
import com.hyflip.server.source.neu.NeuData;
import com.hyflip.server.source.neu.NeuItem;
import com.hyflip.server.source.neu.NeuRecipe;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CraftRecipeChoiceTest {

    static final long NOW = 1_800_000_000_000L;

    static NeuRecipe craft(String output, double count, Map<String, Double> inputs) {
        return new NeuRecipe(NeuRecipe.Type.CRAFTING, output, count, inputs, 0, null, output);
    }

    // GEM: a big batch (40 a craft, 1k profit per item) and a small one (2 a craft, 5k per item).
    // TOOL: one PART, which is dear to buy but crafted from 3,000 cheap DUST (more than an inventory).
    static final NeuRecipe BIG = craft("GEM", 40, Map.of("ORE", 40.0));
    static final NeuRecipe SMALL = craft("GEM", 2, Map.of("ORE", 1.0));
    static final NeuRecipe TOOL = craft("TOOL", 1, Map.of("PART", 1.0));
    static final NeuRecipe PART = craft("PART", 1, Map.of("DUST", 3000.0));

    static final NeuData DATA = new NeuData("test", Instant.EPOCH, Map.of(
            "GEM", new NeuItem("GEM", "Gem", List.of()),
            "TOOL", new NeuItem("TOOL", "Tool", List.of()),
            "PART", new NeuItem("PART", "Part", List.of())),
            List.of(BIG, SMALL, TOOL, PART), Map.of());

    static final Market MARKET = new StaticMarket(Map.of(
            "ORE", new BazaarQuote(1_000, 1_000, 1_000_000, 1_000_000, NOW),
            "GEM", new BazaarQuote(10_000, 10_000, 1_000_000, 1_000_000, NOW),
            "TOOL", new BazaarQuote(100_000, 100_000, 1_000_000, 1_000_000, NOW),
            "PART", new BazaarQuote(50_000, 50_000, 1_000_000, 1_000_000, NOW),
            "DUST", new BazaarQuote(1, 1, 100_000_000, 100_000_000, NOW)), Map.of(), Map.of());

    static FlipContext ctx(int depth) {
        PlayerContext player = new PlayerContext(null, 2, true, Map.of(), 0.2, false, "UNKNOWN", null,
                Duration.ofHours(8), PriceSource.ORDER, EngineFixtures.FEES, "CONFIG");
        return new FlipContext(RecipeBook.of(DATA), MARKET, player, CollectionCatalog.EMPTY, Duration.ofHours(8), NOW,
                depth);
    }

    @Test
    void recipesOfOneOutputAreComparedPerItemMade() {
        CraftFlipper.Evaluation best = CraftFlipper.bestEvaluation(List.of(BIG, SMALL), ctx(CraftPlanner.DEFAULT_DEPTH));
        // BIG earns more a craft (40 items) but less per item than SMALL
        assertThat(best.recipe()).isSameAs(SMALL);
        var detail = CraftFlipper.best(List.of(BIG, SMALL), ctx(CraftPlanner.DEFAULT_DEPTH));
        assertThat(detail.guide().steps()).contains("Craft Gem once: one craft makes 2");
        assertThat(detail.guide().logic()).contains("Gem has 2 recipes, compared per item made")
                .contains("Another makes 40 a craft");
    }

    @Test
    void withBulkCraftsHiddenAShallowerPlanThatFitsIsChosen() {
        FlipQuery any = new FlipQuery(null, null, FlipSort.NET_PROFIT, null, true, true, 50, 0);
        FlipQuery fits = any.withHideAlerts(Set.of(AlertType.BULK_CRAFT));
        FlipContext full = ctx(CraftPlanner.DEFAULT_DEPTH);

        CraftFlipper.Evaluation deep = CraftFlipper.fitting(List.of(TOOL), full, any, CraftRecipeChoiceTest::ctx);
        assertThat(FlipSupport.bulk(deep.summary())).isTrue();

        CraftFlipper.Evaluation shallow = CraftFlipper.fitting(List.of(TOOL), full, fits, CraftRecipeChoiceTest::ctx);
        assertThat(FlipSupport.bulk(shallow.summary())).isFalse();
        assertThat(shallow.summary().cost()).isEqualTo(50_000.0); // PART bought, not crafted
        assertThat(shallow.summary().netProfit()).isLessThan(deep.summary().netProfit());
    }
}
