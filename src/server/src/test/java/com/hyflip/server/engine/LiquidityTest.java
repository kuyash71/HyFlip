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
        assertThat(Liquidity.of(m, bazaar)).isEqualTo(new Liquidity(Level.HIGH, 200.0));
        Quote auction = Pricing.sell(m, "A", 1, PriceSource.ORDER, EngineFixtures.FEES).orElseThrow();
        assertThat(Liquidity.of(m, auction).level()).isEqualTo(Level.LOW);
        assertThat(Liquidity.rate(30, 10).level()).isEqualTo(Level.MEDIUM);
        assertThat(Liquidity.of(m, null)).isEqualTo(Liquidity.UNKNOWN);
    }
}
