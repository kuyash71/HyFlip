package com.hyflip.server.flipper;

import com.hyflip.server.engine.PriceSource;
import java.util.List;

/**
 * A page of ranked opportunities and the settings they were computed with.
 *
 * @param total matching opportunities before paging
 */
public record FlipList(long total, long generatedAt, Settings settings, List<Opportunity> items) {

    /**
     * @param forgeSpeed forge speed in use; null for flippers without durations
     */
    public record Settings(PriceSource priceSource, long nextSessionInSeconds, Double budget, String sort,
                           ForgeSpeedInfo forgeSpeed) {
    }

    /**
     * @param knownReduction   Quick Forge + Molten Forge
     * @param calibration      NONE, PERCENTAGE, FIXED or INCONSISTENT
     * @param provisional      true while the calibration needs more samples
     */
    public record ForgeSpeedInfo(double knownReduction, Double quickForgeReduction, boolean moltenForge,
                                 String calibration, double calibrationFactor, long calibrationFixedSeconds,
                                 boolean provisional) {
    }
}
