package com.hyflip.server.engine;

/** Shared engine test values; the fees match the config defaults. */
public final class EngineFixtures {

    public static final Fees FEES = new Fees(0.0125,
            new Fees.AuctionFees(0.01, 0.02, 0.025, 10_000_000, 100_000_000, 0.01, 1_000_000));

    private EngineFixtures() {
    }
}
