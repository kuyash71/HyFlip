package com.hyflip.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyflip.server.engine.Liquidity.Level;
import com.hyflip.server.engine.Market.AuctionQuote;
import com.hyflip.server.engine.Market.BazaarQuote;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiquidityTest {

    @Test
    void ratesDailyVolumeAgainstQuantity() {
        Market m = new StaticMarket(Map.of("B", new BazaarQuote(1, 1, 700, 700, 1)),
                Map.of("A", new AuctionQuote(10.0, 1L, null, 2)), Map.of());
        Quote bazaar = Pricing.sell(m, "B", 1, PriceSource.ORDER, EngineFixtures.FEES).orElseThrow();
        // a sell offer waits on insta-buys only: 700 a week
        assertThat(Liquidity.of(m, bazaar, PriceSource.ORDER)).isEqualTo(new Liquidity(Level.HIGH, 100.0));
        Quote auction = Pricing.sell(m, "A", 1, PriceSource.ORDER, EngineFixtures.FEES).orElseThrow();
        assertThat(Liquidity.of(m, auction, PriceSource.ORDER).level()).isEqualTo(Level.LOW);
        assertThat(Liquidity.rate(30, 10).level()).isEqualTo(Level.MEDIUM);
        assertThat(Liquidity.of(m, null, PriceSource.ORDER)).isEqualTo(Liquidity.UNKNOWN);
    }

    @Test
    void countsOnlyTheSideTheTradeWaitsOn() {
        // Many insta-sell it (7,000 a week fill buy orders), few insta-buy it (70 a week fill sell offers).
        Market m = new StaticMarket(Map.of("ONE_SIDED", new BazaarQuote(10, 9, 70, 7_000, 1)), Map.of(), Map.of());
        Quote sell = Pricing.sell(m, "ONE_SIDED", 1, PriceSource.ORDER, EngineFixtures.FEES).orElseThrow();
        assertThat(Liquidity.of(m, sell, PriceSource.ORDER).dailyVolume()).isEqualTo(10.0);
        assertThat(Liquidity.of(m, sell, PriceSource.INSTANT).dailyVolume()).isEqualTo(1_000.0);
        Quote buy = Pricing.buyOnBazaar(m, "ONE_SIDED", 1, PriceSource.ORDER).orElseThrow();
        assertThat(Liquidity.buying(m, buy, PriceSource.ORDER).dailyVolume()).isEqualTo(1_000.0);
        assertThat(Liquidity.buying(m, buy, PriceSource.INSTANT).dailyVolume()).isEqualTo(10.0);
    }
}
