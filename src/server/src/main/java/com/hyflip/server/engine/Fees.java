package com.hyflip.server.engine;

/**
 * Sell-side fees. All rates come from the config because Hypixel changes them.
 *
 * @param bazaarTaxRate fraction of the sale value taken on insta-sells and filled sell offers
 * @param auction       Auction House fees
 */
public record Fees(double bazaarTaxRate, AuctionFees auction) {

    /** Fees taken when {@code gross} coins of an item are sold on the Bazaar. */
    public double bazaar(double gross) {
        return gross * bazaarTaxRate;
    }

    /**
     * Auction House fees for one BIN listing: a listing fee by price tier and a claim tax above a
     * threshold. Duration fees are small and not modelled.
     */
    public record AuctionFees(double smallRate, double mediumRate, double largeRate, double mediumFrom,
                              double largeFrom, double claimTaxRate, double claimTaxFrom) {

        public double listingFee(double price) {
            double rate = price >= largeFrom ? largeRate : price >= mediumFrom ? mediumRate : smallRate;
            return price * rate;
        }

        public double claimTax(double price) {
            return price > claimTaxFrom ? price * claimTaxRate : 0;
        }

        /** Listing fee plus claim tax for one listing at {@code price}. */
        public double total(double price) {
            return listingFee(price) + claimTax(price);
        }
    }
}
