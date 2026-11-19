package com.hyflip.server.flipper;

import com.hyflip.server.engine.CostSheet;
import com.hyflip.server.engine.CraftPlanner;
import com.hyflip.server.engine.Market;
import com.hyflip.server.profile.CollectionCatalog;
import com.hyflip.server.profile.PlayerContext;
import com.hyflip.server.profile.Unlocks;
import com.hyflip.server.source.neu.NeuRecipe;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Everything one flipper request evaluates against: recipes, one market view (lookups cached),
 * the player context and one craft planner (memoised). Not thread-safe; one per request.
 */
public final class FlipContext {

    private final RecipeBook book;
    private final Market market;
    private final PlayerContext player;
    private final CollectionCatalog catalog;
    private final Duration nextSessionIn;
    private final long now;
    private final int craftDepth;
    private final Map<String, Unlocks.Unlock> unlocks = new HashMap<>();
    private Market recent;
    private Market reference;
    private AlertRules rules = AlertRules.DEFAULT;
    private java.util.function.Function<Long, Market> history = t -> null;
    private CraftPlanner planner;

    public FlipContext(RecipeBook book, Market market, PlayerContext player, CollectionCatalog catalog,
                       Duration nextSessionIn, long now, int craftDepth) {
        this.book = book;
        this.market = market;
        this.player = player;
        this.catalog = catalog;
        this.nextSessionIn = nextSessionIn;
        this.now = now;
        this.craftDepth = craftDepth;
    }

    /** Supplies past market views for recent-change alerts (none by default). */
    public FlipContext withHistory(java.util.function.Function<Long, Market> history) {
        this.history = history;
        return this;
    }

    /** Thresholds for data-quality alerts ({@link AlertRules#DEFAULT} unless set). */
    public FlipContext withRules(AlertRules rules) {
        this.rules = rules;
        return this;
    }

    public AlertRules rules() {
        return rules;
    }

    /**
     * Stored snapshots at {@code now}, without the live order book, or null if no history is
     * available. Compared with {@link #recent()} so a live price is not mistaken for a sudden move.
     */
    public Market reference() {
        if (reference == null) {
            reference = history.apply(now);
        }
        return reference;
    }

    /** The market {@link FlipSupport#RECENT_WINDOW} ago, or null if no history is available. */
    public Market recent() {
        if (recent == null) {
            recent = history.apply(now - FlipSupport.RECENT_WINDOW.toMillis());
        }
        return recent;
    }

    public RecipeBook book() {
        return book;
    }

    public Market market() {
        return market;
    }

    public PlayerContext player() {
        return player;
    }

    public Duration nextSessionIn() {
        return nextSessionIn;
    }

    public long now() {
        return now;
    }

    /** Whether the player meets the requirements to make {@code itemId}. */
    public Unlocks.Unlock unlock(String itemId) {
        return unlocks.computeIfAbsent(itemId, id -> Unlocks.check(book.requirements(id), player, catalog));
    }

    /** Planner choosing buy or craft for ingredients; never crafts with locked recipes. */
    public CraftPlanner planner() {
        if (planner == null) {
            planner = new CraftPlanner(market, player.priceSource(), book::crafting,
                    r -> unlock(r.output()).state() != Unlocks.State.LOCKED, craftDepth);
        }
        return planner;
    }

    /** Prices one NEU recipe with the cheapest way to get each input. */
    public CostSheet sheet(NeuRecipe r) {
        return CostSheet.of(r.inputs(), r.coins(), r.output(), r.outputCount(), market, player.priceSource(),
                player.fees(), planner());
    }
}
