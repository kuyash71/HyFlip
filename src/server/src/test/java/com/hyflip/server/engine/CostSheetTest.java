package com.hyflip.server.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.hyflip.server.engine.Market.BazaarQuote;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CostSheetTest {

    /** Live prices of 2026-11-17: 160 Enchanted Mithril → 1 Refined Mithril. */
    static final Market MITHRIL = new StaticMarket(Map.of(
            "ENCHANTED_MITHRIL", new BazaarQuote(1329.96, 1259.30, 19_430_569, 35_473_584, 1),
            "REFINED_MITHRIL", new BazaarQuote(759_230.17, 744_339.37, 127_300, 91_693, 1)), Map.of(), Map.of());

    @Test
    void refinedMithrilGolden() {
        CostSheet sheet = CostSheet.of(Map.of("ENCHANTED_MITHRIL", 160.0), 0, "REFINED_MITHRIL", 1, MITHRIL,
                PriceSource.ORDER, EngineFixtures.FEES);
        assertThat(sheet.cost()).isCloseTo(160 * 1259.30, within(0.01));
        assertThat(sheet.value()).isCloseTo(759_230.17, within(0.01));
        assertThat(sheet.fees()).isCloseTo(759_230.17 * 0.0125, within(0.01));
        assertThat(sheet.netProfit()).isCloseTo(759_230.17 * 0.9875 - 160 * 1259.30, within(0.01));
        assertThat(sheet.complete()).isTrue();
    }

    @Test
    void recipeCoinsAndMissingPrices() {
        Map<String, Double> inputs = new LinkedHashMap<>();
        inputs.put("ENCHANTED_MITHRIL", 1.0);
        inputs.put("UNKNOWN_THING", 2.0);
        CostSheet sheet = CostSheet.of(inputs, 25_000, "NOWHERE", 1, MITHRIL, PriceSource.ORDER, EngineFixtures.FEES);
        assertThat(sheet.cost()).isCloseTo(25_000 + 1259.30, within(0.01));
        assertThat(sheet.missingPrices()).containsExactly("UNKNOWN_THING", "NOWHERE");
        assertThat(sheet.output()).isNull();
        assertThat(sheet.inputs().get(1).quote()).isNull();
    }

    @Test
    void invariantsHoldForRandomPrices() {
        Random random = new Random(42);
        for (int i = 0; i < 500; i++) {
            double buy = random.nextDouble() * 1000;
            double sell = random.nextDouble() * 1000;
            Market m = new StaticMarket(Map.of(
                    "IN", new BazaarQuote(buy, sell, 10, 10, 1),
                    "OUT", new BazaarQuote(random.nextDouble() * 50_000, random.nextDouble() * 50_000, 10, 10, 1)),
                    Map.of(), Map.of());
            CostSheet sheet = CostSheet.of(Map.of("IN", 1.0 + random.nextInt(100)), random.nextInt(1000), "OUT", 1,
                    m, random.nextBoolean() ? PriceSource.ORDER : PriceSource.INSTANT, EngineFixtures.FEES);
            assertThat(sheet.netProfit()).isLessThanOrEqualTo(sheet.value());
            Metrics metrics = Metrics.of(sheet, 1 + random.nextInt(100_000), Duration.ofHours(8));
            if (metrics.roi() != null) {
                assertThat(Math.signum(metrics.roi())).isEqualTo(Math.signum(metrics.netProfit()));
            }
            assertThat(Math.abs(metrics.profitPerSession())).isLessThanOrEqualTo(Math.abs(metrics.netProfit()));
        }
    }
}
