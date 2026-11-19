package com.hyflip.server.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.hyflip.server.engine.Calibration.Kind;
import com.hyflip.server.engine.Calibration.Sample;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalibrationTest {

    @Test
    void noSamplesMeansNoCorrection() {
        assertThat(Calibration.fit(List.of())).isEqualTo(Calibration.Fit.NONE);
        assertThat(Calibration.Fit.NONE.apply(3600)).isEqualTo(3600);
    }

    @Test
    void constantRatioIsAPercentage() {
        // Calculated with 20% Quick Forge: 1h → 2880 s, 6h → 17280 s, 24h → 69120 s; 30% faster still.
        Calibration.Fit fit = Calibration.fit(List.of(
                new Sample(3600, 0.2, 2016), new Sample(21600, 0.2, 12096), new Sample(86400, 0.2, 48384)));
        assertThat(fit.kind()).isEqualTo(Kind.PERCENTAGE);
        assertThat(fit.factor()).isCloseTo(0.7, within(1e-9));
        assertThat(fit.provisional()).isFalse();
        assertThat(fit.apply(10_000)).isEqualTo(7000);
    }

    @Test
    void constantDifferenceIsAFixedReduction() {
        Calibration.Fit fit = Calibration.fit(List.of(
                new Sample(7200, 0, 3600), new Sample(21600, 0, 18000), new Sample(86400, 0, 82800)));
        assertThat(fit.kind()).isEqualTo(Kind.FIXED);
        assertThat(fit.fixedSeconds()).isEqualTo(3600);
        assertThat(fit.apply(3000)).isZero();
    }

    @Test
    void neitherFitIsInconsistent() {
        Calibration.Fit fit = Calibration.fit(List.of(
                new Sample(3600, 0, 3000), new Sample(21600, 0, 6000), new Sample(86400, 0, 80000)));
        assertThat(fit.kind()).isEqualTo(Kind.INCONSISTENT);
        assertThat(fit.apply(5000)).isEqualTo(5000);
    }

    @Test
    void oneDurationIsAProvisionalPercentage() {
        // The design doc example: listed 17.5 h, finished in 4.5 h.
        Calibration.Fit fit = Calibration.fit(List.of(new Sample(63_000, 0, 16_200)));
        assertThat(fit.kind()).isEqualTo(Kind.PERCENTAGE);
        assertThat(fit.provisional()).isTrue();
        assertThat(fit.factor()).isCloseTo(16_200 / 63_000.0, within(1e-9));
    }

    @Test
    void forgeSpeedAddsKnownModifiersThenCalibrates() {
        ForgeSpeed speed = new ForgeSpeed(0.2, true, new Calibration.Fit(Kind.PERCENTAGE, 0.5, 0, 3, false));
        assertThat(speed.knownReduction()).isCloseTo(0.45, within(1e-9));
        assertThat(speed.calculatedSeconds(10_000)).isEqualTo(5500);
        assertThat(speed.realSeconds(10_000)).isEqualTo(2750);
        assertThat(new ForgeSpeed(0.8, true, Calibration.Fit.NONE).knownReduction()).isEqualTo(0.9);
    }
}
