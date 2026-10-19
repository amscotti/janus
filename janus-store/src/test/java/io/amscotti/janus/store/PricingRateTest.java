package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void negativeRatesStayRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PricingRate(-0.01, 0.01, 4096));
    }
}
