package com.hyflip.server.flipper;

import com.hyflip.server.alert.AlertType;
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
 */
public record FlipQuery(Double budget, Duration nextSessionIn, FlipSort sort, Double minVolume,
                        boolean includeLocked, boolean includeIncomplete, int limit, int offset,
                        Set<AlertType> hideAlerts, String search, Integer hotmLevel, Long at) {

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
                search, hotmLevel, null);
    }

    /** The same query evaluated as of {@code at}. */
    public FlipQuery at(long at) {
        return new FlipQuery(budget, nextSessionIn, sort, minVolume, includeLocked, includeIncomplete, limit, offset,
                hideAlerts, search, hotmLevel, at);
    }

    /** The same query with another page. */
    public FlipQuery page(int limit, int offset) {
        return new FlipQuery(budget, nextSessionIn, sort, minVolume, includeLocked, includeIncomplete, limit, offset,
                hideAlerts, search, hotmLevel, at);
    }

    /** Evaluation time: {@code at}, or {@code now} for a live query. */
    public long time(long now) {
        return at == null ? now : at;
    }

    /** The player context with this query's overrides applied. */
    public PlayerContext apply(PlayerContext player) {
        return hotmLevel == null ? player : player.withHotmLevel(hotmLevel);
    }

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    public static FlipQuery defaults() {
        return new FlipQuery(null, null, FlipSort.PROFIT_PER_SESSION, null, false, false, DEFAULT_LIMIT, 0);
    }
}
