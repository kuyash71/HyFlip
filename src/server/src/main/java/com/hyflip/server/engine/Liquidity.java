package com.hyflip.server.engine;

/**
 * How easily the output sells, relative to the quantity one run produces.
 *
 * @param level       HIGH (daily volume ≥ 20 × quantity), MEDIUM (≥ 3 ×), LOW, or UNKNOWN
 * @param dailyVolume units a day that can fill the trade, null if unknown (NPC sales have no limit). On the
 *                    Bazaar only the side the trade waits on counts: insta-buys for a sell offer, insta-sells for
 *                    a buy order. Both sides together would overstate it for one-sided items.
 */
public record Liquidity(Level level, Double dailyVolume) {

    public enum Level { HIGH, MEDIUM, LOW, UNKNOWN }

    static final double HIGH_RATIO = 20;
    static final double MEDIUM_RATIO = 3;

    public static final Liquidity UNKNOWN = new Liquidity(Level.UNKNOWN, null);

    /** Liquidity of selling {@code output} at its venue, with Bazaar prices taken as {@code source}. */
    public static Liquidity of(Market market, Quote output, PriceSource source) {
        if (output == null) {
            return UNKNOWN;
        }
        return switch (output.venue()) {
            case NPC -> new Liquidity(Level.HIGH, null);
            case BAZAAR -> market.bazaar(output.itemId())
                    .map(q -> rate(q.sellsPerDay(source), output.quantity())).orElse(UNKNOWN);
            case AUCTION -> market.auction(output.itemId())
                    .map(a -> rate(a.salesPerDay(), output.quantity())).orElse(UNKNOWN);
        };
    }

    /** Liquidity of buying {@code input} on the Bazaar; NPC and Auction House inputs as for selling. */
    public static Liquidity buying(Market market, Quote input, PriceSource source) {
        if (input != null && input.venue() == Venue.BAZAAR) {
            return market.bazaar(input.itemId())
                    .map(q -> rate(q.buysPerDay(source), input.quantity())).orElse(UNKNOWN);
        }
        return of(market, input, source);
    }

    /** Rates a daily volume against the quantity needed or produced. */
    public static Liquidity rate(double daily, double quantity) {
        double ratio = daily / Math.max(quantity, 1e-9);
        Level level = ratio >= HIGH_RATIO ? Level.HIGH : ratio >= MEDIUM_RATIO ? Level.MEDIUM : Level.LOW;
        return new Liquidity(level, daily);
    }
}
