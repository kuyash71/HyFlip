package com.hyflip.server.engine;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Prices the engine reads, keyed by canonical item ID (NEU internal name). Implementations decide
 * the point in time; the engine never queries a database itself.
 */
public interface Market {

    /** Latest Bazaar state of the item, empty if it is not a Bazaar product. */
    Optional<BazaarQuote> bazaar(String itemId);

    /** Auction House prices of the item, empty if nothing is known. */
    Optional<AuctionQuote> auction(String itemId);

    /** What an NPC pays for one item, empty if NPCs do not buy it. */
    OptionalDouble npcSellPrice(String itemId);

    /** Average Bazaar prices over the last 7 days, empty if unknown (the default). */
    default Optional<BazaarAverage> bazaarAverage(String itemId) {
        return Optional.empty();
    }

    /**
     * Hour-weighted average {@code quick_status} prices over the stored history (at most 7 days).
     *
     * @param buyPrice  average insta-buy price, 0 if unknown
     * @param sellPrice average insta-sell price, 0 if unknown
     * @param days      days of history behind the averages
     * @param volatility daily price swing: standard deviation of hour-to-hour log changes of the
     *                   insta-sell price, scaled to a day; null with fewer than 6 hours of history
     */
    record BazaarAverage(double buyPrice, double sellPrice, double days, Double volatility) {

        public BazaarAverage(double buyPrice, double sellPrice, double days) {
            this(buyPrice, sellPrice, days, null);
        }
    }

    /**
     * Bazaar {@code quick_status} of one product. A price of 0 means that side of the book is empty.
     *
     * @param buyPrice  insta-buy price (about the lowest sell offer)
     * @param sellPrice insta-sell price (about the highest buy order)
     * @param buyMovingWeek  units insta-bought over the last 7 days (filled sell offers)
     * @param sellMovingWeek units insta-sold over the last 7 days (filled buy orders)
     * @param takenAt        when the snapshot was taken (epoch ms)
     * @param buyOrders      sell offers in the book (the side insta-buys take from)
     * @param sellOrders     buy orders in the book (the side insta-sells fill)
     * @param live           true if the prices are the best single orders of the latest poll rather
     *                       than {@code quick_status} averages
     */
    record BazaarQuote(double buyPrice, double sellPrice, long buyMovingWeek, long sellMovingWeek, long takenAt,
                       int buyOrders, int sellOrders, boolean live) {

        /** Stored {@code quick_status} averages. */
        public BazaarQuote(double buyPrice, double sellPrice, long buyMovingWeek, long sellMovingWeek, long takenAt,
                           int buyOrders, int sellOrders) {
            this(buyPrice, sellPrice, buyMovingWeek, sellMovingWeek, takenAt, buyOrders, sellOrders, false);
        }

        /** A quote without order counts (tests, callers that do not need them). */
        public BazaarQuote(double buyPrice, double sellPrice, long buyMovingWeek, long sellMovingWeek, long takenAt) {
            this(buyPrice, sellPrice, buyMovingWeek, sellMovingWeek, takenAt, 0, 0);
        }

        /** Units traded per day on both sides, from the 7-day moving volumes. */
        public double dailyVolume() {
            return (buyMovingWeek + sellMovingWeek) / 7.0;
        }

        /**
         * Units a day that can fill a sale: sell offers fill as others insta-buy ({@code buyMovingWeek});
         * insta-selling fills buy orders, which others refill about as fast as they insta-sell.
         */
        public double sellsPerDay(PriceSource source) {
            return (source == PriceSource.ORDER ? buyMovingWeek : sellMovingWeek) / 7.0;
        }

        /**
         * Units a day that can fill a purchase: buy orders fill as others insta-sell ({@code sellMovingWeek});
         * insta-buying takes sell offers, which others refill about as fast as they insta-buy.
         */
        public double buysPerDay(PriceSource source) {
            return (source == PriceSource.ORDER ? sellMovingWeek : buyMovingWeek) / 7.0;
        }
    }

    /**
     * Auction House prices per unit, from clean copies only (no stars, enchantments, ...).
     *
     * @param lowestBin   lowest BIN per unit from the latest scan, null if no listing was seen
     * @param lowestBinAt when the lowest BIN was recorded, null if none
     * @param medianSale  median per-unit price of recent ended auctions, null if none
     * @param salesPerDay units sold per day over the recent window
     * @param sales       ended auctions in the recent window
     * @param listings    BIN listings in the latest scan
     */
    record AuctionQuote(Double lowestBin, Long lowestBinAt, Double medianSale, double salesPerDay, int sales,
                        int listings) {

        /** Counts derived from the rate: a week of sales, one listing if a BIN is known (tests). */
        public AuctionQuote(Double lowestBin, Long lowestBinAt, Double medianSale, double salesPerDay) {
            this(lowestBin, lowestBinAt, medianSale, salesPerDay, (int) Math.round(salesPerDay * 7),
                    lowestBin == null ? 0 : 1);
        }
    }
}
