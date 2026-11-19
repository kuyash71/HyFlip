package com.hyflip.server.flipper.forge;

import com.hyflip.server.engine.CostSheet;
import com.hyflip.server.engine.CraftPlanner;
import com.hyflip.server.engine.ForgeSpeed;
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
import com.hyflip.server.source.neu.NeuData;
import com.hyflip.server.source.neu.NeuRecipe;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Ranks NEU forge recipes by profit per session (default), using the player's real forge speed.
 * Inputs are bought or crafted, whichever is cheaper. See {@code docs/flippers.md#forge-flipper}.
 */
@Service
public class ForgeFlipper implements Flipper {

    private final RecipeBooks books;
    private final MarketService markets;
    private final PlayerContextService contexts;
    private final ForgeCalibrationService calibration;
    private final Clock clock;

    @Autowired
    public ForgeFlipper(RecipeBooks books, MarketService markets, PlayerContextService contexts,
                        ForgeCalibrationService calibration) {
        this(books, markets, contexts, calibration, Clock.systemUTC());
    }

    ForgeFlipper(RecipeBooks books, MarketService markets, PlayerContextService contexts,
                 ForgeCalibrationService calibration, Clock clock) {
        this.books = books;
        this.markets = markets;
        this.contexts = contexts;
        this.calibration = calibration;
        this.clock = clock;
    }

    @Override
    public FlipType type() {
        return FlipType.FORGE;
    }

    @Override
    public FlipList list(FlipQuery query) {
        PlayerContext player = query.apply(contexts.current());
        FlipContext ctx = context(query, player);
        ForgeSpeed speed = calibration.speed(player);
        List<Opportunity> all = new ArrayList<>();
        for (NeuRecipe r : ctx.book().recipes(NeuRecipe.Type.FORGE)) {
            all.add(summary(r, ctx, speed));
        }
        Double budget = query.budget() != null ? query.budget() : player.budget();
        return FlipSupport.page(all, query, budget, ctx.now(), new FlipList.Settings(player.priceSource(),
                ctx.nextSessionIn().toSeconds(), budget, query.sort().param(),
                ForgeCalibrationService.info(speed, player)));
    }

    @Override
    public Optional<OpportunityDetail> detail(String id, FlipQuery query) {
        PlayerContext player = query.apply(contexts.current());
        FlipContext ctx = context(query, player);
        ForgeSpeed speed = calibration.speed(player);
        return ctx.book().recipes(NeuRecipe.Type.FORGE, id).stream().findFirst().map(r -> evaluate(r, ctx, speed));
    }

    private FlipContext context(FlipQuery query, PlayerContext player) {
        Duration next = query.nextSessionIn() != null ? query.nextSessionIn() : player.nextSessionIn();
        return new FlipContext(books.current(), markets.view(query.at()), player, contexts.collections(), next,
                query.time(clock.millis()),
                CraftPlanner.DEFAULT_DEPTH).withHistory(markets::at).withRules(contexts.rules());
    }

    /** The list row only; the guide is built for the detail view. */
    static Opportunity summary(NeuRecipe r, FlipContext ctx, ForgeSpeed speed) {
        CostSheet sheet = ctx.sheet(r);
        long base = r.durationSeconds() == null ? 0 : r.durationSeconds();
        Metrics m = Metrics.of(sheet, speed.realSeconds(base), ctx.nextSessionIn());
        return FlipSupport.opportunity(FlipType.FORGE, r, sheet, m, base, ctx, FlipSupport.names(ctx.book(), sheet,
                r.output()));
    }

    static OpportunityDetail evaluate(NeuRecipe r, FlipContext ctx, ForgeSpeed speed) {
        CostSheet sheet = ctx.sheet(r);
        long base = r.durationSeconds() == null ? 0 : r.durationSeconds();
        Metrics m = Metrics.of(sheet, speed.realSeconds(base), ctx.nextSessionIn());
        Map<String, String> names = FlipSupport.names(ctx.book(), sheet, r.output());
        Opportunity summary = FlipSupport.opportunity(FlipType.FORGE, r, sheet, m, base, ctx, names);
        return new OpportunityDetail(summary, sheet, ctx.unlock(r.output()), guide(r, sheet, m, ctx, names),
                Map.copyOf(names));
    }

    static Guide guide(NeuRecipe r, CostSheet sheet, Metrics m, FlipContext ctx, Map<String, String> names) {
        List<String> steps = new ArrayList<>(FlipSupport.acquireSteps(sheet, names, ctx.player().priceSource()));
        FlipSupport.fillStep(sheet, ctx).ifPresent(steps::add);
        if (r.coins() > 0) {
            steps.add("Keep " + Coins.format(r.coins()) + " coins for the recipe cost");
        }
        String output = names.get(r.output());
        steps.add("Start " + output + " in a free slot at the Forge (Dwarven Mines)");
        steps.add("Collect it after about " + Coins.duration(m.durationSeconds()));
        steps.add(FlipSupport.sellStep(sheet.output(), output, ctx.player().priceSource()));

        StringBuilder logic = FlipSupport.profitSentence(sheet, m);
        logic.append(" It takes ").append(Coins.duration(m.durationSeconds())).append(" at your forge speed");
        if (m.sessions() > 1) {
            logic.append(", which keeps the slot busy for ").append(m.sessions())
                    .append(" sessions, so it earns ").append(Coins.format(m.profitPerSession())).append(" per session");
        } else {
            logic.append(" and finishes before your next session");
        }
        logic.append(". Prices can move while the forge runs; record the job in the Forge Tracker to compare"
                + " expected and realised value.");
        if (!sheet.complete()) {
            logic.append(" Some prices are missing, so these numbers are incomplete.");
        }
        return new Guide(List.copyOf(steps), logic.toString());
    }

    static String name(NeuData data, String id) {
        var item = data.items().get(id);
        return item != null && item.displayName() != null ? item.displayName() : id;
    }
}
