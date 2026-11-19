package com.hyflip.server.engine;

/**
 * How easily the output sells, relative to the quantity one run produces.
 *
 * @param level       HIGH (daily volume ≥ 20 × quantity), MEDIUM (≥ 3 ×), LOW, or UNKNOWN
 * @param dailyVolume units traded per day, null if unknown (NPC sales have no limit)
 */
public record Liquidity(Level level, Double dailyVolume) {

    public enum Level { HIGH, MEDIUM, LOW, UNKNOWN }

    static final double HIGH_RATIO = 20;
    static final double MEDIUM_RATIO = 3;

    public static final Liquidity UNKNOWN = new Liquidity(Level.UNKNOWN, null);

    /** Liquidity of selling {@code output} at its venue. */
    public static Liquidity of(Market market, Quote output) {
        if (output == null) {
            return UNKNOWN;
        }
        return switch (output.venue()) {
            case NPC -> new Liquidity(Level.HIGH, null);
            case BAZAAR -> market.bazaar(output.itemId())
                    .map(q -> rate(q.dailyVolume(), output.quantity())).orElse(UNKNOWN);
            case AUCTION -> market.auction(output.itemId())
                    .map(a -> rate(a.salesPerDay(), output.quantity())).orElse(UNKNOWN);
        };
    }

    /** Rates a daily volume against the quantity needed or produced. */
    public static Liquidity rate(double daily, double quantity) {
        double ratio = daily / Math.max(quantity, 1e-9);
        Level level = ratio >= HIGH_RATIO ? Level.HIGH : ratio >= MEDIUM_RATIO ? Level.MEDIUM : Level.LOW;
        return new Liquidity(level, daily);
    }
}
