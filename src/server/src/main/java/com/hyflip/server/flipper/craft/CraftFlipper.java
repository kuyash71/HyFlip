package com.hyflip.server.flipper.craft;

import com.hyflip.server.engine.CostSheet;
import com.hyflip.server.engine.CraftPlanner;
import com.hyflip.server.engine.Metrics;
import com.hyflip.server.flipper.Coins;
import com.hyflip.server.flipper.FlipContext;
import com.hyflip.server.flipper.FlipList;
import com.hyflip.server.flipper.FlipQuery;
import com.hyflip.server.flipper.FlipSupport;
import com.hyflip.server.flipper.FlipType;
import com.hyflip.server.flipper.Flipper;
import com.hyflip.server.flipper.Guide;
import com.hyflip.server.flipper.Opportunity;
import com.hyflip.server.flipper.OpportunityDetail;
import com.hyflip.server.flipper.RecipeBooks;
import com.hyflip.server.market.MarketService;
import com.hyflip.server.profile.PlayerContext;
import com.hyflip.server.profile.PlayerContextService;
import com.hyflip.server.source.neu.NeuRecipe;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Ranks NEU crafting recipes by ingredient cost versus output value. Each ingredient is bought or
 * crafted from its own ingredients, whichever is cheaper (up to {@link CraftPlanner#DEFAULT_DEPTH}
 * levels). One opportunity per output item: the best of its recipes.
 */
@Service
public class CraftFlipper implements Flipper {

    private final RecipeBooks books;
    private final MarketService markets;
    private final PlayerContextService contexts;
    private final Clock clock;

    @Autowired
    public CraftFlipper(RecipeBooks books, MarketService markets, PlayerContextService contexts) {
        this(books, markets, contexts, Clock.systemUTC());
    }

    CraftFlipper(RecipeBooks books, MarketService markets, PlayerContextService contexts, Clock clock) {
        this.books = books;
        this.markets = markets;
        this.contexts = contexts;
        this.clock = clock;
    }

    @Override
    public FlipType type() {
        return FlipType.CRAFT;
    }

    @Override
    public FlipList list(FlipQuery query) {
        PlayerContext player = query.apply(contexts.current());
        FlipContext ctx = context(query, player);
        List<Opportunity> all = new ArrayList<>();
        for (List<NeuRecipe> recipes : byOutput(ctx).values()) {
            all.add(bestEvaluation(recipes, ctx).summary());
        }
        Double budget = query.budget() != null ? query.budget() : player.budget();
        return FlipSupport.page(all, query, budget, ctx.now(), new FlipList.Settings(player.priceSource(),
                ctx.nextSessionIn().toSeconds(), budget, query.sort().param(), null));
    }

    @Override
    public Optional<OpportunityDetail> detail(String id, FlipQuery query) {
        FlipContext ctx = context(query, query.apply(contexts.current()));
        List<NeuRecipe> recipes = ctx.book().recipes(NeuRecipe.Type.CRAFTING, id).stream()
                .filter(r -> !r.inputs().isEmpty()).toList();
        return recipes.isEmpty() ? Optional.empty() : Optional.of(best(recipes, ctx));
    }

    private FlipContext context(FlipQuery query, PlayerContext player) {
        Duration next = query.nextSessionIn() != null ? query.nextSessionIn() : player.nextSessionIn();
        return new FlipContext(books.current(), markets.view(query.at()), player, contexts.collections(), next,
                query.time(clock.millis()),
                CraftPlanner.DEFAULT_DEPTH).withHistory(markets::at).withRules(contexts.rules());
    }

    static Map<String, List<NeuRecipe>> byOutput(FlipContext ctx) {
        Map<String, List<NeuRecipe>> out = new LinkedHashMap<>();
        for (NeuRecipe r : ctx.book().recipes(NeuRecipe.Type.CRAFTING)) {
            if (!r.inputs().isEmpty() && r.outputCount() > 0) {
                out.computeIfAbsent(r.output(), o -> new ArrayList<>()).add(r);
            }
        }
        return out;
    }

    /** The most profitable recipe for one output; complete sheets win over incomplete ones. */
    static OpportunityDetail best(List<NeuRecipe> recipes, FlipContext ctx) {
        return detail(bestEvaluation(recipes, ctx), ctx);
    }

    /** A recipe priced without its guide: lists show thousands and need only the summary. */
    record Evaluation(NeuRecipe recipe, CostSheet sheet, Metrics metrics, Map<String, String> names,
                      Opportunity summary) {
    }

    static Evaluation bestEvaluation(List<NeuRecipe> recipes, FlipContext ctx) {
        return recipes.stream()
                .map(r -> evaluation(r, ctx))
                .max(Comparator.comparing((Evaluation e) -> e.summary().complete())
                        .thenComparingDouble(e -> e.summary().netProfit()))
                .orElseThrow();
    }

    static Evaluation evaluation(NeuRecipe r, FlipContext ctx) {
        CostSheet sheet = ctx.sheet(r);
        Metrics m = Metrics.of(sheet, 0, ctx.nextSessionIn());
        Map<String, String> names = FlipSupport.names(ctx.book(), sheet, r.output());
        return new Evaluation(r, sheet, m, names, FlipSupport.opportunity(FlipType.CRAFT, r, sheet, m, 0, ctx, names));
    }

    static OpportunityDetail detail(Evaluation e, FlipContext ctx) {
        return new OpportunityDetail(e.summary(), e.sheet(), ctx.unlock(e.recipe().output()),
                guide(e.recipe(), e.sheet(), e.metrics(), e.summary(), ctx, e.names()), Map.copyOf(e.names()));
    }

    static OpportunityDetail evaluate(NeuRecipe r, FlipContext ctx) {
        return detail(evaluation(r, ctx), ctx);
    }

    static Guide guide(NeuRecipe r, CostSheet sheet, Metrics m, Opportunity o, FlipContext ctx,
                       Map<String, String> names) {
        List<String> steps = new ArrayList<>(FlipSupport.acquireSteps(sheet, names, ctx.player().priceSource()));
        FlipSupport.fillStep(sheet, ctx).ifPresent(steps::add);
        String output = names.get(r.output());
        String made = r.outputCount() == 1 ? output : Coins.amount(r.outputCount()) + " × " + output;
        steps.add("Craft " + made + (r.coins() > 0 ? " (recipe costs " + Coins.format(r.coins()) + " coins)" : ""));
        steps.add(FlipSupport.sellStep(sheet.output(), output, ctx.player().priceSource()));

        StringBuilder logic = FlipSupport.profitSentence(sheet, m);
        logic.append(" Crafting is instant, so the limit is how much the market takes");
        if (o.liquidity().dailyVolume() != null) {
            logic.append(": about ").append(Math.round(o.liquidity().dailyVolume())).append(" traded per day");
        }
        logic.append(". Repeating it moves prices: buy orders push input prices up and sell offers push the"
                + " output down.");
        if (!sheet.complete()) {
            logic.append(" Some prices are missing, so these numbers are incomplete.");
        }
        return new Guide(List.copyOf(steps), logic.toString());
    }
}
