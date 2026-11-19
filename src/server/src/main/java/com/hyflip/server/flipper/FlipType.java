package com.hyflip.server.flipper;

import java.util.Locale;
import java.util.Optional;

/** Flipper kinds; the lower-case name is the {@code /flips/{type}} path segment. */
public enum FlipType {
    FORGE(FlipSort.PROFIT_PER_SESSION),
    CRAFT(FlipSort.NET_PROFIT),
    NPC(FlipSort.PROFIT_PER_DAY),
    BAZAAR(FlipSort.PROFIT_PER_DAY);

    private final FlipSort defaultSort;

    FlipType(FlipSort defaultSort) {
        this.defaultSort = defaultSort;
    }

    /** Ranking used when the request names none. */
    public FlipSort defaultSort() {
        return defaultSort;
    }

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<FlipType> fromPath(String path) {
        for (FlipType t : values()) {
            if (t.path().equals(path)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
