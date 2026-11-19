package com.hyflip.server.flipper;

import com.hyflip.server.alert.AlertType;
import com.hyflip.server.engine.CraftPlanner;
import com.hyflip.server.engine.PriceSource;
import com.hyflip.server.engine.Venue;
import com.hyflip.server.profile.PlayerContext;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Filters and ranking for one flipper request. Null values fall back to the player context.
 *
 * @param budget        hide opportunities costing more; null uses the configured budget
 * @param nextSessionIn time until the player can act again; null uses the configured value
 * @param sort          ranking metric
 * @param minVolume     hide outputs that trade fewer units per day; null for no minimum
 * @param includeLocked also return recipes the player has not unlocked
 * @param includeIncomplete also return opportunities with a missing price (ranked last)
 * @param limit         page size
 * @param offset        page start
 * @param hideAlerts    hide opportunities carrying any of these alerts
 * @param search        only names containing this text (case-insensitive); null or blank for all
 * @param hotmLevel     check HOTM requirements against this level instead of the profile; null uses the profile
 * @param at            evaluate with stored prices as of this moment (epoch ms, for backtests); null
 *                      for now with the live order book
 * @param craftSteps    most crafting steps per flip, the final craft included (1: buy every input; 2: inputs may be
 *                      crafted once); null for the planner's default depth
 * @param priceSource   Bazaar prices as orders or instant for this request; null uses the player's setting
 * @param sellVenue     only flips whose output sells there; null for any
 */
public record FlipQuery(Double budget, Duration nextSessionIn, FlipSort sort, Double minVolume,
                        boolean includeLocked, boolean includeIncomplete, int limit, int offset,
                        Set<AlertType> hideAlerts, String search, Integer hotmLevel, Long at, Integer craftSteps,
                        PriceSource priceSource, Venue sellVenue) {

    /** Most crafting steps a query may ask for: the final craft plus every level the planner can go down. */
    public static final int MAX_CRAFT_STEPS = CraftPlanner.DEFAULT_DEPTH + 1;

    public FlipQuery {
        hideAlerts = hideAlerts == null ? Set.of() : Set.copyOf(hideAlerts);
        search = search == null || search.isBlank() ? null : search.strip().toLowerCase(Locale.ROOT);
    }

    public FlipQuery(Double budget, Duration nextSessionIn, FlipSort sort, Double minVolume, boolean includeLocked,
                     boolean includeIncomplete, int limit, int offset) {
        this(budget, nextSessionIn, sort, minVolume, includeLocked, includeIncomplete, limit, offset, null, null,
                null, null);
    }

    public FlipQuery(Double budget, Duration nextSessionIn, FlipSort sort, Double minVolume, boolean includeLocked,
                     boolean includeIncomplete, int limit, int offset, Set<AlertType> hideAlerts, String search,
                     Integer hotmLevel) {
        this(budget, nextSessionIn, sort, minVolume, includeLocked, includeIncomplete, limit, offset, hideAlerts,
                search, hotmLevel, null, null);
    }

    public FlipQuery(Double budget, Duration nextSessionIn, FlipSort sort, Double minVolume, boolean includeLocked,
                     boolean includeIncomplete, int limit, int offset, Set<AlertType> hideAlerts, String search,
                     Integer hotmLevel, Long at) {
        this(budget, nextSessionIn, sort, minVolume, includeLocked, includeIncomplete, limit, offset, hideAlerts,
                search, hotmLevel, at, null);
    }

    public FlipQuery(Double budget, Duration nextSessionIn, FlipSort sort, Double minVolume, boolean includeLocked,
                     boolean includeIncomplete, int limit, int offset, Set<AlertType> hideAlerts, String search,
                     Integer hotmLevel, Long at, Integer craftSteps) {
        this(budget, nextSessionIn, sort, minVolume, includeLocked, includeIncomplete, limit, offset, hideAlerts,
                search, hotmLevel, at, craftSteps, null, null);
    }

    /** Levels the craft planner may go below a recipe input. */
    public int craftDepth() {
        return craftSteps == null ? CraftPlanner.DEFAULT_DEPTH : Math.max(0, Math.min(craftSteps, MAX_CRAFT_STEPS) - 1);
    }

    /** The same query with at most {@code steps} crafting steps per flip (null for the default). */
    public FlipQuery withCraftSteps(Integer steps) {
        return new FlipQuery(budget, nextSessionIn, sort, minVolume, includeLocked, includeIncomplete, limit, offset,
                hideAlerts, search, hotmLevel, at, steps, priceSource, sellVenue);
    }

    /** The same query with Bazaar prices taken as {@code source} (null: the player's setting). */
    public FlipQuery withPriceSource(PriceSource source) {
        return new FlipQuery(budget, nextSessionIn, sort, minVolume, includeLocked, includeIncomplete, limit, offset,
                hideAlerts, search, hotmLevel, at, craftSteps, source, sellVenue);
    }

    /** The same query hiding opportunities with any of these alerts. */
    public FlipQuery withHideAlerts(Set<AlertType> types) {
        return new FlipQuery(budget, nextSessionIn, sort, minVolume, includeLocked, includeIncomplete, limit, offset,
                types, search, hotmLevel, at, craftSteps, priceSource, sellVenue);
    }

    /**
     * Whether bulk crafts are hidden. Then a plan that needs bulk crafting is replaced by a shallower one that
     * fits an inventory, so the flip stays listed with work one can do.
     */
    public boolean fitsInventory() {
        return hideAlerts.contains(AlertType.BULK_CRAFT);
    }

    /** The same query evaluated as of {@code at}. */
    public FlipQuery at(long at) {
        return new FlipQuery(budget, nextSessionIn, sort, minVolume, includeLocked, includeIncomplete, limit, offset,
                hideAlerts, search, hotmLevel, at, craftSteps, priceSource, sellVenue);
    }

    /** The same query with another page. */
    public FlipQuery page(int limit, int offset) {
        return new FlipQuery(budget, nextSessionIn, sort, minVolume, includeLocked, includeIncomplete, limit, offset,
                hideAlerts, search, hotmLevel, at, craftSteps, priceSource, sellVenue);
    }

    /** Evaluation time: {@code at}, or {@code now} for a live query. */
    public long time(long now) {
        return at == null ? now : at;
    }

    /** The player context with this query's overrides applied. */
    public PlayerContext apply(PlayerContext player) {
        PlayerContext p = hotmLevel == null ? player : player.withHotmLevel(hotmLevel);
        return priceSource == null ? p : p.withPriceSource(priceSource);
    }

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    public static FlipQuery defaults() {
        return new FlipQuery(null, null, FlipSort.PROFIT_PER_SESSION, null, false, false, DEFAULT_LIMIT, 0);
    }
}
