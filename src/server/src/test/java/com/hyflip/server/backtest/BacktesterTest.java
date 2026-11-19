package com.hyflip.server.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class BacktesterTest {

    @Test
    void spearmanHandlesOrderTiesAndConstants() {
        assertThat(Backtester.spearman(new double[] {1, 2, 3, 4}, new double[] {10, 20, 30, 40})).isEqualTo(1.0);
        assertThat(Backtester.spearman(new double[] {1, 2, 3, 4}, new double[] {4, 3, 2, 1})).isEqualTo(-1.0);
        assertThat(Backtester.spearman(new double[] {1, 2, 3}, new double[] {5, 5, 5})).isEqualTo(0.0);
        assertThat(Backtester.spearman(new double[] {1, 2, 2, 3}, new double[] {1, 2, 3, 4})).isCloseTo(0.9487, within(1e-4));
    }
}
