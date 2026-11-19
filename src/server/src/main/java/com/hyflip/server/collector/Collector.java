package com.hyflip.server.collector;

import java.time.Duration;

/** A scheduled job that reads one source and stores what it got. */
public interface Collector {

    /** Stable name stored in {@code collector_run.collector}, for example {@code BAZAAR}. */
    String name();

    Duration interval();

    /** Runs once; returns the number of rows written. Exceptions mark the run as failed. */
    int collect() throws Exception;

    /** True for collectors that build time series, where downtime leaves a gap in history. */
    default boolean recordsHistory() {
        return false;
    }

    /** True if the collector should run again right after the NEU repository was updated. */
    default boolean runsAfterNeuUpdate() {
        return false;
    }
}
