package com.hyflip.server.engine;

import java.time.Duration;

/**
 * Profit metrics of one opportunity, per slot.
 *
 * @param netProfit        coins earned per run after fees
 * @param roi              netProfit ÷ cost, null when the cost is 0
 * @param durationSeconds  real duration of one run
 * @param profitPerHour    netProfit ÷ duration in hours; null for instant flips (duration 0)
 * @param sessions         sessions the slot is busy: ⌈duration ÷ time to next session⌉, at least 1
 * @param profitPerSession netProfit ÷ sessions, the default ranking metric
 */
public record Metrics(double netProfit, Double roi, long durationSeconds, Double profitPerHour, long sessions,
                      double profitPerSession) {

    /**
     * A run that finishes before the next session earns its full profit for that session, however
     * short it is: the slot then sits idle until the player returns. A run longer than the gap
     * between sessions spreads its profit over every session it keeps the slot busy.
     */
    public static Metrics of(CostSheet sheet, long durationSeconds, Duration nextSessionIn) {
        double net = sheet.netProfit();
        Double roi = sheet.cost() > 0 ? net / sheet.cost() : null;
        long duration = Math.max(0, durationSeconds);
        long gap = Math.max(60, nextSessionIn.toSeconds());
        long sessions = Math.max(1, (duration + gap - 1) / gap);
        Double perHour = duration == 0 ? null : net / (duration / 3600.0);
        return new Metrics(net, roi, duration, perHour, sessions, net / sessions);
    }
}
