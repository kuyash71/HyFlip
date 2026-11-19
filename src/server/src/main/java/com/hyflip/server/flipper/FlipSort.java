package com.hyflip.server.flipper;

import java.util.Comparator;
import java.util.Optional;

/** Ranking metrics; {@code param} is the query parameter value. */
public enum FlipSort {
    PROFIT_PER_SESSION("profitPerSession", Comparator.comparingDouble(Opportunity::profitPerSession)),
    PROFIT_PER_HOUR("profitPerHour", Comparator.comparing(Opportunity::profitPerHour,
            Comparator.nullsFirst(Comparator.naturalOrder()))),
    PROFIT_PER_DAY("profitPerDay", Comparator.comparing(Opportunity::profitPerDay,
            Comparator.nullsFirst(Comparator.naturalOrder()))),
    NET_PROFIT("netProfit", Comparator.comparingDouble(Opportunity::netProfit)),
    ROI("roi", Comparator.comparing(Opportunity::roi, Comparator.nullsFirst(Comparator.naturalOrder())));

    private final String param;
    private final Comparator<Opportunity> ascending;

    FlipSort(String param, Comparator<Opportunity> ascending) {
        this.param = param;
        this.ascending = ascending;
    }

    public String param() {
        return param;
    }

    /** Best first; incomplete opportunities (missing prices) always last. */
    public Comparator<Opportunity> order() {
        return Comparator.comparing(Opportunity::complete).reversed().thenComparing(ascending.reversed());
    }

    public static Optional<FlipSort> fromParam(String param) {
        for (FlipSort s : values()) {
            if (s.param.equals(param)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
