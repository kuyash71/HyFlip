package com.hyflip.server.flipper;

import com.hyflip.server.alert.Alert;
import com.hyflip.server.engine.Liquidity;
import com.hyflip.server.engine.Venue;
import com.hyflip.server.profile.Unlocks;
import java.util.List;

/**
 * One ranked row. Money is per run of one slot; see {@code docs/calculation-engine.md}.
 *
 * @param id                  stable ID used by {@code /flips/{type}/{id}} (the output item for forge)
 * @param cost                ingredient cost including recipe coins
 * @param value               output value before fees
 * @param fees                sell fees on the output
 * @param baseDurationSeconds NEU base duration, 0 for instant flips
 * @param durationSeconds     real duration for this player, 0 for instant flips
 * @param profitPerHour       null for instant flips
 * @param sellVenue           where the output sells best, null if unpriced
 * @param buyVenues           where the inputs are bought, in recipe order without repeats
 * @param unlock              whether the player meets the recipe requirements
 * @param complete            false if any price is missing; such rows rank last
 * @param runsPerDay          NPC Flipper: how many runs a day are realistic (daily limit, market share); else null
 * @param profitPerDay        netProfit × runsPerDay; null when runsPerDay is null
 * @param fillSeconds         with buy orders and sell offers: about how long one run's orders take to fill
 *                            at current volume if they stay the best (inputs, then the sale); null if
 *                            nothing waits on an order or the volume is unknown
 */
public record Opportunity(String id, FlipType type, String name, String outputId, double outputCount,
                          double cost, double value, double fees, double netProfit, Double roi,
                          long baseDurationSeconds, long durationSeconds, Double profitPerHour, long sessions,
                          double profitPerSession, Venue sellVenue, List<Venue> buyVenues, Liquidity liquidity, Unlocks.State unlock,
                          boolean complete, List<Alert> alerts, Double runsPerDay, Double profitPerDay,
                          Long fillSeconds) {

    /** Without a fill estimate (NPC and Bazaar flips size their daily batch to the volume instead). */
    public Opportunity(String id, FlipType type, String name, String outputId, double outputCount, double cost,
                       double value, double fees, double netProfit, Double roi, long baseDurationSeconds,
                       long durationSeconds, Double profitPerHour, long sessions, double profitPerSession,
                       Venue sellVenue, List<Venue> buyVenues, Liquidity liquidity, Unlocks.State unlock,
                       boolean complete, List<Alert> alerts, Double runsPerDay, Double profitPerDay) {
        this(id, type, name, outputId, outputCount, cost, value, fees, netProfit, roi, baseDurationSeconds,
                durationSeconds, profitPerHour, sessions, profitPerSession, sellVenue, buyVenues, liquidity, unlock,
                complete, alerts, runsPerDay, profitPerDay, null);
    }
}
