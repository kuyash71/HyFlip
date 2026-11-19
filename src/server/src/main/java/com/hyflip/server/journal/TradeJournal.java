package com.hyflip.server.journal;

import com.hyflip.server.alert.Alert;
import com.hyflip.server.alert.AlertType;
import com.hyflip.server.flipper.FlipQuery;
import com.hyflip.server.flipper.FlipType;
import com.hyflip.server.flipper.Flipper;
import com.hyflip.server.flipper.Opportunity;
import com.hyflip.server.flipper.OpportunityDetail;
import com.hyflip.server.storage.ForgeJobRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Records flips with the expected result of the moment and compares them with what the player
 * reports, per flipper and per alert that was on the opportunity. Forge jobs with a sold value
 * from the Forge Tracker count as forge trades.
 */
@Service
public class TradeJournal {

    private final TradeRepository trades;
    private final ForgeJobRepository forgeJobs;
    private final Map<FlipType, Flipper> flippers = new EnumMap<>(FlipType.class);
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public TradeJournal(TradeRepository trades, ForgeJobRepository forgeJobs, List<Flipper> flippers, ObjectMapper json) {
        this(trades, forgeJobs, flippers, json, Clock.systemUTC());
    }

    TradeJournal(TradeRepository trades, ForgeJobRepository forgeJobs, List<Flipper> flippers, ObjectMapper json,
                 Clock clock) {
        this.trades = trades;
        this.forgeJobs = forgeJobs;
        flippers.forEach(f -> this.flippers.put(f.type(), f));
        this.json = json;
        this.clock = clock;
    }

    /** A rejected journal request: 400 or 404. */
    public static class TradeException extends RuntimeException {
        private final boolean notFound;

        TradeException(String message, boolean notFound) {
            super(message);
            this.notFound = notFound;
        }

        public boolean notFound() {
            return notFound;
        }
    }

    /**
     * @param type flipper: forge, craft, npc or bazaar (any case)
     * @param runs crafts, units or routes; null means 1
     */
    public record RecordRequest(String type, String id, Double runs, String note) {
    }

    /**
     * @param actualCost    coins paid for everything, null until entered
     * @param actualRevenue coins received after fees, null until entered
     * @param finished      set when the flip is complete (sold)
     */
    public record ResultRequest(Double actualCost, Double actualRevenue, Boolean finished, String note) {
    }

    /**
     * @param expectedProfit expected value − expected cost
     * @param actualProfit   actual revenue − actual cost (expected cost when only the revenue is known)
     * @param difference     actual − expected profit, null until a revenue is entered
     */
    public record Trade(long id, FlipType type, String opportunityId, String name, double runs, long startedAt,
                        double expectedCost, double expectedValue, double expectedProfit, List<AlertType> alerts,
                        Double actualCost, Double actualRevenue, Double actualProfit, Double difference,
                        Long finishedAt, String note) {
    }

    /**
     * Statistics over trades with an actual result.
     *
     * @param trades         trades counted
     * @param withResult     of those, trades with an actual revenue
     * @param expectedProfit sum of expected profit of trades with a result
     * @param actualProfit   sum of their actual profit
     * @param accuracy       actual / expected profit (1 = estimates were right), null if nothing expected
     * @param profitable     share of trades with a result that made a profit
     */
    public record Stats(int trades, int withResult, double expectedProfit, double actualProfit, Double accuracy,
                        Double profitable) {
    }

    /**
     * @param byType     per flipper (forge includes Forge Tracker jobs with a sold value)
     * @param byAlert    trades that carried each alert when recorded
     * @param noAlerts   trades recorded without any alert
     */
    public record Summary(Stats all, Map<FlipType, Stats> byType, Map<AlertType, Stats> byAlert, Stats noAlerts) {
    }

    public Trade record(RecordRequest request) {
        if (request.type() == null || request.id() == null || request.id().isBlank()) {
            throw new TradeException("type and id are required", false);
        }
        FlipType type = FlipType.fromPath(request.type().trim().toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new TradeException("Unknown type " + request.type(), false));
        double runs = request.runs() == null ? 1 : request.runs();
        if (!(runs > 0) || runs > 1_000_000) {
            throw new TradeException("runs must be above 0", false);
        }
        Flipper flipper = flippers.get(type);
        OpportunityDetail detail = flipper == null ? null
                : flipper.detail(request.id(), FlipQuery.defaults()).orElse(null);
        if (detail == null) {
            throw new TradeException("No " + type.path() + " flip " + request.id(), true);
        }
        Opportunity o = detail.summary();
        if (!o.complete()) {
            throw new TradeException("The flip has a missing price; it cannot be recorded", false);
        }
        List<String> alerts = o.alerts().stream().map(Alert::type).map(Enum::name).distinct().toList();
        long id = trades.insert(type.name(), o.id(), o.name(), runs, clock.millis(), o.cost() * runs,
                (o.value() - o.fees()) * runs, json.writeValueAsString(alerts), blankToNull(request.note()));
        return trades.find(id).map(this::view).orElseThrow();
    }

    public Optional<Trade> result(long id, ResultRequest request) {
        for (Double v : new Double[] {request.actualCost(), request.actualRevenue()}) {
            if (v != null && (v < 0 || v.isNaN() || v.isInfinite())) {
                throw new TradeException("amounts must be zero or more", false);
            }
        }
        Optional<TradeRepository.Row> row = trades.find(id);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Long finished = Boolean.TRUE.equals(request.finished())
                ? (row.get().finishedAt() != null ? row.get().finishedAt() : clock.millis())
                : Boolean.FALSE.equals(request.finished()) ? null : row.get().finishedAt();
        trades.result(id, request.actualCost(), request.actualRevenue(), finished, blankToNull(request.note()));
        return trades.find(id).map(this::view);
    }

    public boolean delete(long id) {
        return trades.delete(id);
    }

    public List<Trade> recent(FlipType type, int limit) {
        return trades.recent(type == null ? null : type.name(), limit).stream().map(this::view).toList();
    }

    public Summary summary() {
        List<Trade> all = new ArrayList<>(trades.all().stream().map(this::view).toList());
        // Forge Tracker jobs the player sold count as forge trades (without alerts: not stored there).
        forgeJobs.recent(10_000).stream()
                .filter(j -> j.soldValue() != null && j.expectedCost() != null && j.expectedValue() != null)
                .forEach(j -> {
                    double expected = j.expectedValue() - j.expectedCost();
                    double actual = j.soldValue() - j.expectedCost();
                    all.add(new Trade(-j.id(), FlipType.FORGE, j.recipeId(), j.recipeId(), 1, j.startedAt(),
                            j.expectedCost(), j.expectedValue(), expected, null, null, j.soldValue(), actual,
                            actual - expected, j.collectedAt(), "Forge Tracker"));
                });
        Map<FlipType, List<Trade>> byType = new EnumMap<>(FlipType.class);
        Map<AlertType, List<Trade>> byAlert = new TreeMap<>();
        List<Trade> clean = new ArrayList<>();
        for (Trade t : all) {
            byType.computeIfAbsent(t.type(), k -> new ArrayList<>()).add(t);
            if (t.alerts() == null) {
                continue;
            }
            if (t.alerts().isEmpty()) {
                clean.add(t);
            }
            t.alerts().forEach(a -> byAlert.computeIfAbsent(a, k -> new ArrayList<>()).add(t));
        }
        Map<FlipType, Stats> types = new LinkedHashMap<>();
        byType.forEach((k, v) -> types.put(k, stats(v)));
        Map<AlertType, Stats> alerts = new LinkedHashMap<>();
        byAlert.forEach((k, v) -> alerts.put(k, stats(v)));
        return new Summary(stats(all), types, alerts, stats(clean));
    }

    static Stats stats(List<Trade> list) {
        List<Trade> done = list.stream().filter(t -> t.actualProfit() != null).toList();
        double expected = done.stream().mapToDouble(Trade::expectedProfit).sum();
        double actual = done.stream().mapToDouble(Trade::actualProfit).sum();
        long won = done.stream().filter(t -> t.actualProfit() > 0).count();
        return new Stats(list.size(), done.size(), expected, actual, expected > 0 ? actual / expected : null,
                done.isEmpty() ? null : won / (double) done.size());
    }

    private Trade view(TradeRepository.Row r) {
        double expectedProfit = r.expectedValue() - r.expectedCost();
        Double actualProfit = r.actualRevenue() == null ? null
                : r.actualRevenue() - (r.actualCost() != null ? r.actualCost() : r.expectedCost());
        List<AlertType> alerts = new ArrayList<>();
        for (String name : json.readValue(r.alerts(), new TypeReference<List<String>>() { })) {
            try {
                alerts.add(AlertType.valueOf(name));
            } catch (IllegalArgumentException e) {
                // an alert type that no longer exists
            }
        }
        return new Trade(r.id(), FlipType.valueOf(r.flipType()), r.opportunityId(), r.name(), r.runs(), r.startedAt(),
                r.expectedCost(), r.expectedValue(), expectedProfit, List.copyOf(alerts), r.actualCost(),
                r.actualRevenue(), actualProfit, actualProfit == null ? null : actualProfit - expectedProfit,
                r.finishedAt(), r.note());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
