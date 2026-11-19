package com.hyflip.server.flipper;

/**
 * Thresholds of the data-quality alerts.
 *
 * @param minAuctionSales an Auction House value from fewer clean sales than this in the last 7 days
 *                        raises FEW_SALES
 * @param wideSpread      relative gap between the order price and the instant price above which a
 *                        Bazaar order raises WIDE_SPREAD (0.5: the instant price is 50% worse)
 * @param manipulationRatio a Bazaar price above this multiple of its 7-day average raises MANIPULATION
 */
public record AlertRules(int minAuctionSales, double wideSpread, double manipulationRatio) {

    public static final AlertRules DEFAULT = new AlertRules(3, 0.5, 2);

    public AlertRules(int minAuctionSales, double wideSpread) {
        this(minAuctionSales, wideSpread, DEFAULT.manipulationRatio());
    }
}
