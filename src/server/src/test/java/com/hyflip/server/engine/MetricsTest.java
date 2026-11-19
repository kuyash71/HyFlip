package com.hyflip.server.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricsTest {

    static CostSheet profit(double net) {
        return new CostSheet(List.of(), 0, 1000, null, net + 1000, 0, net, List.of());
    }

    @Test
    void shortAndMediumRunsEarnOneCyclePerSession() {
        Duration away = Duration.ofHours(8);
        Metrics quick = Metrics.of(profit(1000), 30, away);
        Metrics sixHours = Metrics.of(profit(1000), 6 * 3600, away);
        assertThat(quick.sessions()).isEqualTo(1);
        assertThat(sixHours.sessions()).isEqualTo(1);
        assertThat(quick.profitPerSession()).isEqualTo(sixHours.profitPerSession()).isEqualTo(1000);
        assertThat(quick.profitPerHour()).isGreaterThan(sixHours.profitPerHour());
    }

    @Test
    void longRunsSpreadProfitOverTheSessionsTheyOccupy() {
        Metrics week = Metrics.of(profit(21_000), 7 * 24 * 3600, Duration.ofHours(8));
        assertThat(week.sessions()).isEqualTo(21);
        assertThat(week.profitPerSession()).isEqualTo(1000);
        assertThat(Metrics.of(profit(100), 8 * 3600 + 1, Duration.ofHours(8)).sessions()).isEqualTo(2);
    }

    @Test
    void roiIsNullWithoutCost() {
        CostSheet free = new CostSheet(List.of(), 0, 0, null, 10, 0, 10, List.of());
        assertThat(Metrics.of(free, 60, Duration.ofHours(1)).roi()).isNull();
        assertThat(Metrics.of(profit(500), 60, Duration.ofHours(1)).roi()).isEqualTo(0.5);
    }
}
