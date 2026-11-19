package com.hyflip.server.engine;

/**
 * The player's forge speed: known modifiers plus the calibration fit.
 *
 * <p>{@code calculated = base × (1 − quickForge − moltenForge)}, then the calibration fit maps the
 * calculated duration to the real one. Perk values are to verify in game.
 *
 * @param quickForge  Quick Forge perk reduction as a fraction (0 if unknown)
 * @param moltenForge whether Cole's Molten Forge perk is active
 * @param calibration fit from the calibration wizard
 */
public record ForgeSpeed(double quickForge, boolean moltenForge, Calibration.Fit calibration) {

    /** Cole's Molten Forge perk. */
    public static final double MOLTEN_FORGE = 0.25;
    /** Never assume more than this total reduction from modifiers. */
    static final double MAX_KNOWN_REDUCTION = 0.9;

    public static final ForgeSpeed BASE = new ForgeSpeed(0, false, Calibration.Fit.NONE);

    /** Quick Forge plus Molten Forge, added together. */
    public double knownReduction() {
        double r = quickForge + (moltenForge ? MOLTEN_FORGE : 0);
        return Math.min(MAX_KNOWN_REDUCTION, Math.max(0, r));
    }

    public long calculatedSeconds(long baseSeconds) {
        return Math.round(baseSeconds * (1 - knownReduction()));
    }

    public long realSeconds(long baseSeconds) {
        return calibration.apply(calculatedSeconds(baseSeconds));
    }
}
