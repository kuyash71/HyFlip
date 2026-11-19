package com.hyflip.server.engine;

/**
 * A priced quantity of one item at one venue.
 *
 * @param itemId    canonical item ID
 * @param quantity  units
 * @param venue     where it is bought or sold
 * @param unitPrice coins per unit before fees
 * @param gross     {@code quantity × unitPrice}
 * @param fees      fees taken (sells only; buys have none)
 * @param priceAt   when the price was observed (epoch ms), null for NPC prices
 * @param estimated true if the price is the median of recent sales rather than a live listing
 */
public record Quote(String itemId, double quantity, Venue venue, double unitPrice, double gross, double fees,
                    Long priceAt, boolean estimated) {

    /** Coins received (sells) or paid (buys) after fees. */
    public double net() {
        return gross - fees;
    }
}
