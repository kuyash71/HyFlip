package com.hyflip.server.flipper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoinsTest {

    @Test
    void unitPricesKeepOneDecimalBelowAThousand() {
        assertThat(Coins.price(342.84)).isEqualTo("342.8");
        assertThat(Coins.price(5.0)).isEqualTo("5");
        assertThat(Coins.price(0.1)).isEqualTo("0.1");
        assertThat(Coins.price(1_250.1)).isEqualTo("1.25k");
        assertThat(Coins.format(342.84)).isEqualTo("343");
    }
}
