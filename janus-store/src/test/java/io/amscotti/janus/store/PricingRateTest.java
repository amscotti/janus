package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * {@link PricingRate} construction validation. Non-finite rates are rejected:
 * TOML admits {@code nan}/{@code inf} spellings, and a {@code NaN} rate would
 * slip past a {@code < 0} range check and silently price every request at $0
 * (a budget bypass), so finiteness is part of the non-negative contract.
 */
class PricingRateTest {

    @Test
    void nanRatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PricingRate(Double.NaN, 0.01, 4096));
        assertThrows(IllegalArgumentException.class, () -> new PricingRate(0.01, Double.NaN, 4096));
        assertThrows(IllegalArgumentException.class, () -> new PricingRate(0.01, 0.02, Double.NaN, 0.0, 4096));
        assertThrows(IllegalArgumentException.class, () -> new PricingRate(0.01, 0.02, 0.0, Double.NaN, 4096));
        assertThrows(IllegalArgumentException.class, () -> new PricingRate(0.01, 0.02, 0.0, 0.0, 4096, Double.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PricingRate(0.01, 0.02, 0.0, 0.0, 4096, 0.0, 200_000, Double.NaN, 0.02, 0.0, 0.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PricingRate(0.01, 0.02, 0.0, 0.0, 4096, 0.0, 0, 0.0, 0.0, 0.0, 0.0, Double.NaN, 0.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PricingRate(0.01, 0.02, 0.0, 0.0, 4096, 0.0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, Double.NaN));
    }

    @Test
    void infiniteRatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PricingRate(Double.POSITIVE_INFINITY, 0.01, 4096));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PricingRate(
                        0.01, 0.02, 0.0, 0.0, 4096, 0.0, 200_000, 0.02, Double.POSITIVE_INFINITY, 0.0, 0.0));
    }

    @Test
    void everyRateFieldRejectsEveryNonFiniteValue() {
        List<Function<Double, PricingRate>> factories = List.of(
                v -> new PricingRate(v, 0.02, 0.0, 0.0, 4096),
                v -> new PricingRate(0.01, v, 0.0, 0.0, 4096),
                v -> new PricingRate(0.01, 0.02, v, 0.0, 4096),
                v -> new PricingRate(0.01, 0.02, 0.0, v, 4096),
                v -> new PricingRate(0.01, 0.02, 0.0, 0.0, 4096, v),
                v -> new PricingRate(0.01, 0.02, 0.0, 0.0, 4096, 0.0, 200_000, v, 0.02, 0.0, 0.0),
                v -> new PricingRate(0.01, 0.02, 0.0, 0.0, 4096, 0.0, 200_000, 0.02, v, 0.0, 0.0),
                v -> new PricingRate(0.01, 0.02, 0.0, 0.0, 4096, 0.0, 200_000, 0.02, 0.03, v, 0.0),
                v -> new PricingRate(0.01, 0.02, 0.0, 0.0, 4096, 0.0, 200_000, 0.02, 0.03, 0.0, v),
                v -> new PricingRate(0.01, 0.02, 0.0, 0.0, 4096, 0.0, 0, 0.0, 0.0, 0.0, 0.0, v, 0.0),
                v -> new PricingRate(0.01, 0.02, 0.0, 0.0, 4096, 0.0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, v));
        for (Double invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (Function<Double, PricingRate> factory : factories) {
                assertThrows(IllegalArgumentException.class, () -> factory.apply(invalid), "value=" + invalid);
            }
        }
    }

    @Test
    void negativeRatesStayRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PricingRate(-0.01, 0.01, 4096));
    }
}
