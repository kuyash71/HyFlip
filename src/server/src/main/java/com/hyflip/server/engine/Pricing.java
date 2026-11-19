package com.hyflip.server.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Turns market data into buy and sell quotes.
 *
 * <p>Bazaar side mapping (verified against live data, see {@code docs/calculation-engine.md}):
 * buying uses {@code buyPrice} with INSTANT and {@code sellPrice} with ORDER; selling uses
 * {@code sellPrice} with INSTANT and {@code buyPrice} with ORDER. If the preferred side of the book
 * is empty, the other side is used.
 *
 * <p>With ORDER and a live order book, an order goes one {@link #ORDER_STEP} ahead of the best one so
 * it is first in line: a buy order just above the best buy order, a sell offer just under the best
 * sell offer. {@code quick_status} averages have no single order to beat and are used as they are.
 */
public final class Pricing {

    /** Smallest Bazaar price step. */
    public static final double ORDER_STEP = 0.1;

    private Pricing() {
    }

    /** Cheapest way to buy {@code quantity} units: Bazaar or Auction House (lowest BIN). */
    public static Optional<Quote> buy(Market market, String itemId, double quantity, PriceSource source) {
        List<Quote> options = new ArrayList<>();
        buyOnBazaar(market, itemId, quantity, source).ifPresent(options::add);
        market.auction(itemId).ifPresent(a -> {
            if (a.lowestBin() != null && a.lowestBin() > 0) {
                options.add(new Quote(itemId, quantity, Venue.AUCTION, a.lowestBin(), a.lowestBin() * quantity, 0,
                        a.lowestBinAt(), false));
            } else if (a.medianSale() != null && a.medianSale() > 0) {
                options.add(new Quote(itemId, quantity, Venue.AUCTION, a.medianSale(), a.medianSale() * quantity, 0,
                        null, true));
            }
        });
        return options.stream().min(Comparator.comparingDouble(Quote::gross));
    }

    /** Buying on the Bazaar only; empty if the item is not a Bazaar product or both sides are empty. */
    public static Optional<Quote> buyOnBazaar(Market market, String itemId, double quantity, PriceSource source) {
        return market.bazaar(itemId).flatMap(q -> {
            double price = source == PriceSource.INSTANT ? pick(q.buyPrice(), q.sellPrice())
                    : q.sellPrice() > 0 ? q.sellPrice() + step(q) : q.buyPrice();
            return price > 0
                    ? Optional.of(new Quote(itemId, quantity, Venue.BAZAAR, price, price * quantity, 0, q.takenAt(), false))
                    : Optional.empty();
        });
    }

    /** Selling on the Bazaar only, with the Bazaar tax as fees. */
    public static Optional<Quote> sellOnBazaar(Market market, String itemId, double quantity, PriceSource source,
                                               Fees fees) {
        return market.bazaar(itemId).flatMap(q -> {
            double price = source == PriceSource.INSTANT ? pick(q.sellPrice(), q.buyPrice())
                    : q.buyPrice() > step(q) ? q.buyPrice() - step(q) : q.sellPrice();
            double gross = price * quantity;
            return price > 0
                    ? Optional.of(new Quote(itemId, quantity, Venue.BAZAAR, price, gross, fees.bazaar(gross), q.takenAt(),
                    false))
                    : Optional.empty();
        });
    }

    /** Selling to an NPC (no fees); empty if NPCs do not buy the item. */
    public static Optional<Quote> sellToNpc(Market market, String itemId, double quantity) {
        double price = market.npcSellPrice(itemId).orElse(0);
        return price > 0 ? Optional.of(new Quote(itemId, quantity, Venue.NPC, price, price * quantity, 0, null, false))
                : Optional.empty();
    }

    /** Best way to sell {@code quantity} units after fees: Bazaar, Auction House or NPC. */
    public static Optional<Quote> sell(Market market, String itemId, double quantity, PriceSource source, Fees fees) {
        List<Quote> options = new ArrayList<>();
        sellOnBazaar(market, itemId, quantity, source, fees).ifPresent(options::add);
        sellOnAuction(market, itemId, quantity, fees).ifPresent(options::add);
        sellToNpc(market, itemId, quantity).ifPresent(options::add);
        return options.stream().max(Comparator.comparingDouble(Quote::net));
    }

    /**
     * Selling on the Auction House: the lower of the lowest BIN and the median of recent sales (a
     * thin market's lowest BIN can be a rare variant far above what sells), with listing fees.
     */
    public static Optional<Quote> sellOnAuction(Market market, String itemId, double quantity, Fees fees) {
        return market.auction(itemId).flatMap(a -> {
            boolean listed = a.lowestBin() != null && a.lowestBin() > 0;
            boolean sold = a.medianSale() != null && a.medianSale() > 0;
            boolean useSales = sold && (!listed || a.medianSale() < a.lowestBin());
            Double unit = useSales ? a.medianSale() : listed ? a.lowestBin() : null;
            // One listing per unit: forge and craft outputs that sell on the AH rarely stack.
            return unit == null ? Optional.empty() : Optional.of(new Quote(itemId, quantity, Venue.AUCTION, unit,
                    unit * quantity, fees.auction().total(unit) * quantity, useSales ? null : a.lowestBinAt(), useSales));
        });
    }

    /** Selling at one venue; empty if the item has no price there. */
    public static Optional<Quote> sellOn(Venue venue, Market market, String itemId, double quantity, PriceSource source,
                                         Fees fees) {
        return switch (venue) {
            case BAZAAR -> sellOnBazaar(market, itemId, quantity, source, fees);
            case AUCTION -> sellOnAuction(market, itemId, quantity, fees);
            case NPC -> sellToNpc(market, itemId, quantity);
        };
    }

    private static double step(Market.BazaarQuote q) {
        return q.live() ? ORDER_STEP : 0;
    }

    private static double pick(double preferred, double fallback) {
        return preferred > 0 ? preferred : fallback;
    }
}
