package com.hyflip.server.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.hyflip.server.engine.Market.AuctionQuote;
import com.hyflip.server.engine.Market.BazaarQuote;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PricingTest {

    static final Fees FEES = EngineFixtures.FEES;

    @Test
    void mapsBazaarSidesByPriceSource() {
        Market m = new StaticMarket(Map.of("X", new BazaarQuote(110, 100, 700, 700, 1)), Map.of(), Map.of());

        assertThat(Pricing.buy(m, "X", 2, PriceSource.INSTANT).orElseThrow().gross()).isEqualTo(220);
        assertThat(Pricing.buy(m, "X", 2, PriceSource.ORDER).orElseThrow().gross()).isEqualTo(200);
        assertThat(Pricing.sell(m, "X", 2, PriceSource.INSTANT, FEES).orElseThrow().gross()).isEqualTo(200);
        Quote offer = Pricing.sell(m, "X", 2, PriceSource.ORDER, FEES).orElseThrow();
        assertThat(offer.gross()).isEqualTo(220);
        assertThat(offer.fees()).isCloseTo(220 * 0.0125, within(1e-9));
    }

    @Test
    void fallsBackToTheOtherSideWhenOneIsEmpty() {
        Market m = new StaticMarket(Map.of("X", new BazaarQuote(0, 100, 1, 1, 1)), Map.of(), Map.of());
        assertThat(Pricing.buy(m, "X", 1, PriceSource.INSTANT).orElseThrow().unitPrice()).isEqualTo(100);
        assertThat(Pricing.sell(m, "X", 1, PriceSource.ORDER, FEES).orElseThrow().unitPrice()).isEqualTo(100);

        Market empty = new StaticMarket(Map.of("X", new BazaarQuote(0, 0, 0, 0, 1)), Map.of(), Map.of());
        assertThat(Pricing.buy(empty, "X", 1, PriceSource.ORDER)).isEmpty();
    }

    @Test
    void buysWhereCheapestAndSellsWhereNetIsHighest() {
        Market m = new StaticMarket(
                Map.of("X", new BazaarQuote(120, 100, 1, 1, 1)),
                Map.of("X", new AuctionQuote(90.0, 2L, null, 5)),
                Map.of("X", 101.0));
        assertThat(Pricing.buy(m, "X", 1, PriceSource.INSTANT).orElseThrow().venue()).isEqualTo(Venue.AUCTION);
        // Bazaar insta-sell 100 − 1.25% = 98.75; NPC pays 101 with no tax.
        assertThat(Pricing.sell(m, "X", 1, PriceSource.INSTANT, FEES).orElseThrow().venue()).isEqualTo(Venue.NPC);
    }

    @Test
    void auctionSalesUseListingFeeAndClaimTax() {
        Market m = new StaticMarket(Map.of(), Map.of("P", new AuctionQuote(20_000_000.0, 5L, 21_000_000.0, 3)),
                Map.of());
        Quote q = Pricing.sell(m, "P", 1, PriceSource.ORDER, FEES).orElseThrow();
        assertThat(q.venue()).isEqualTo(Venue.AUCTION);
        // 2% listing fee (10M–100M tier) + 1% claim tax above 1M.
        assertThat(q.fees()).isCloseTo(20_000_000 * 0.03, within(1e-6));
        assertThat(q.estimated()).isFalse();
    }

    @Test
    void usesMedianSaleWhenNothingIsListed() {
        Market m = new StaticMarket(Map.of(), Map.of("P", new AuctionQuote(null, null, 500.0, 2)), Map.of());
        Quote q = Pricing.sell(m, "P", 1, PriceSource.ORDER, FEES).orElseThrow();
        assertThat(q.unitPrice()).isEqualTo(500);
        assertThat(q.estimated()).isTrue();
        assertThat(q.priceAt()).isNull();
    }

    @Test
    void auctionValueIsTheLowerOfLowestBinAndRecentSales() {
        Market thin = new StaticMarket(Map.of(), Map.of("P", new AuctionQuote(89_000_000.0, 5L, 3_000_000.0, 0.4)),
                Map.of());
        Quote q = Pricing.sell(thin, "P", 1, PriceSource.ORDER, FEES).orElseThrow();
        assertThat(q.unitPrice()).isEqualTo(3_000_000);
        assertThat(q.estimated()).isTrue();

        Market falling = new StaticMarket(Map.of(), Map.of("P", new AuctionQuote(900.0, 5L, 1_000.0, 10)), Map.of());
        assertThat(Pricing.sell(falling, "P", 1, PriceSource.ORDER, FEES).orElseThrow().unitPrice()).isEqualTo(900);
    }

    @Test
    void feeTiers() {
        Fees.AuctionFees ah = FEES.auction();
        assertThat(ah.listingFee(1_000_000)).isEqualTo(10_000);
        assertThat(ah.listingFee(10_000_000)).isEqualTo(200_000);
        assertThat(ah.listingFee(100_000_000)).isEqualTo(2_500_000);
        assertThat(ah.claimTax(1_000_000)).isZero();
        assertThat(ah.claimTax(2_000_000)).isEqualTo(20_000);
    }
}
