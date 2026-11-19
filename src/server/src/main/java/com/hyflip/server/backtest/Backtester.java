package com.hyflip.server.backtest;

import com.hyflip.server.alert.Alert;
import com.hyflip.server.alert.AlertType;
import com.hyflip.server.engine.Market;
import com.hyflip.server.engine.Pricing;
import com.hyflip.server.flipper.FlipQuery;
import com.hyflip.server.flipper.FlipType;
import com.hyflip.server.flipper.Flipper;
import com.hyflip.server.flipper.Opportunity;
import com.hyflip.server.market.MarketService;
import com.hyflip.server.profile.PlayerContext;
import com.hyflip.server.profile.PlayerContextService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Replays a flipper over stored history. At each moment {@code t} the flipper ranks opportunities
 * with the prices stored at {@code t} (no live order book); each of the top rows is then sold at
 * the prices of {@code t + horizon} (forge: {@code t +} the job's duration) at the venue it was
 * meant for. The inputs keep their cost at {@code t}: buy orders are assumed to fill at once,
 * which flatters order-based flips.
 */
@Service
public class Backtester {

    /** At most this many moments per backtest, to keep a request under a minute. */
    public static final int MAX_RUNS = 48;
    public static final int MAX_TOP = 50;

    private final Map<FlipType, Flipper> flippers = new EnumMap<>(FlipType.class);
    private final MarketService markets;
    private final PlayerContextService contexts;
    private final Clock clock;

    @Autowired
    public Backtester(List<Flipper> flippers, MarketService markets, PlayerContextService contexts) {
        this(flippers, markets, contexts, Clock.systemUTC());
    }

    Backtester(List<Flipper> flippers, MarketService markets, PlayerContextService contexts, Clock clock) {
        flippers.forEach(f -> this.flippers.put(f.type(), f));
        this.markets = markets;
        this.contexts = contexts;
        this.clock = clock;
    }

    /**
     * @param from    first moment (epoch ms)
     * @param to      last moment; later moments whose sale time is still in the future are skipped
     * @param step    time between moments
     * @param horizon time until the sale (ignored for forge, which sells after the job's duration)
     * @param top     rows evaluated per moment, from the top of the ranking
     * @param hide    alert types whose rows are left out, as in the list
     */
    public record Request(FlipType type, long from, long to, Duration step, Duration horizon, int top,
                          Set<AlertType> hide) {
    }

    /**
     * One recommended flip and its outcome.
     *
     * @param predictedProfit profit per run shown at {@code at}
     * @param realisedProfit  value at the sale time − cost at {@code at}; null if the output had no
     *                        price then
     * @param soldAt          when it was sold in the replay
     */
    public record Item(long at, int rank, String id, String name, double cost, double predictedProfit,
                       Double realisedProfit, long soldAt, List<AlertType> alerts) {
    }

    /**
     * @param flips           flips with an outcome
     * @param predicted       sum of predicted profit
     * @param realised        sum of realised profit
     * @param capture         realised / predicted (1 = the estimates held), null if nothing predicted
     * @param profitable      share of flips that made a profit
     * @param rankCorrelation mean Spearman correlation between predicted and realised profit within
     *                        a moment (1 = the ranking held), null with fewer than 3 flips a moment
     */
    public record Stats(int flips, double predicted, double realised, Double capture, Double profitable,
                        Double rankCorrelation) {
    }

    /** Per opportunity across all moments. */
    public record Line(String id, String name, int flips, double meanPredicted, double meanRealised) {
    }

    /**
     * @param moments   moments evaluated
     * @param skipped   moments skipped because the sale time is in the future or no history existed
     * @param beyondHistory flips left out because they would sell after the newest stored prices
     * @param byAlert   flips that carried each alert
     * @param noAlerts  flips without any alert
     * @param shortfall opportunities whose realised profit fell furthest below the prediction
     * @param items     every flip, oldest first
     */
    public record Result(FlipType type, long from, long to, long stepSeconds, long horizonSeconds, int top,
                         int moments, int skipped, int beyondHistory, Stats overall, Map<AlertType, Stats> byAlert, Stats noAlerts,
                         List<Line> shortfall, List<Item> items) {
    }

    public Result run(Request r) {
        Flipper flipper = flippers.get(r.type());
        // Sales are valued at stored prices only: after the newest snapshot there is nothing to compare.
        long now = Math.min(clock.millis(), markets.historyEnd().orElse(Long.MIN_VALUE));
        // Before the first snapshot only NPC prices exist: lists would rank NPC-to-NPC rows and say nothing.
        long start = markets.historyStart().orElse(Long.MAX_VALUE);
        Map<Long, Market> later = new java.util.HashMap<>();
        int beyond = 0;
        PlayerContext player = contexts.current();
        FlipQuery base = new FlipQuery(null, null, r.type().defaultSort(), null, false, false, r.top(), 0, r.hide(),
                null, null);
        List<Item> items = new ArrayList<>();
        Map<Long, List<Item>> byMoment = new TreeMap<>();
        int moments = 0;
        int skipped = 0;
        for (long t = r.from(); t <= r.to() && moments + skipped < MAX_RUNS; t += r.step().toMillis()) {
            if (t < start || (r.type() != FlipType.FORGE && t + r.horizon().toMillis() > now)) {
                skipped++;
                continue;
            }
            FlipQuery q = base.at(t);
            List<Opportunity> list = flipper.list(q).items();
            if (list.isEmpty()) {
                skipped++;
                continue;
            }
            moments++;
            int rank = 0;
            for (Opportunity o : list) {
                rank++;
                if (!o.complete()) {
                    continue;
                }
                long soldAt = t + (r.type() == FlipType.FORGE ? o.durationSeconds() * 1000 : r.horizon().toMillis());
                if (soldAt > now) {
                    beyond++;
                    continue;
                }
                if (o.sellVenue() == null) {
                    continue;
                }
                Market then = later.computeIfAbsent(soldAt, markets::at);
                Double realised = Pricing.sellOn(o.sellVenue(), then, o.outputId(), o.outputCount(),
                                player.priceSource(), player.fees())
                        .map(sale -> sale.net() - o.cost()).orElse(null);
                Item item = new Item(t, rank, o.id(), o.name(), o.cost(), o.netProfit(), realised, soldAt,
                        o.alerts().stream().map(Alert::type).distinct().toList());
                items.add(item);
                byMoment.computeIfAbsent(t, k -> new ArrayList<>()).add(item);
            }
        }

        Map<AlertType, List<Item>> withAlert = new TreeMap<>();
        List<Item> clean = new ArrayList<>();
        for (Item i : items) {
            if (i.alerts().isEmpty()) {
                clean.add(i);
            }
            i.alerts().forEach(a -> withAlert.computeIfAbsent(a, k -> new ArrayList<>()).add(i));
        }
        Map<AlertType, Stats> byAlert = new LinkedHashMap<>();
        withAlert.forEach((k, v) -> byAlert.put(k, stats(v, null)));

        List<Line> shortfall = items.stream().filter(i -> i.realisedProfit() != null)
                .collect(Collectors.groupingBy(Item::id, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(v -> new Line(v.get(0).id(), v.get(0).name(), v.size(),
                        v.stream().mapToDouble(Item::predictedProfit).average().orElse(0),
                        v.stream().mapToDouble(Item::realisedProfit).average().orElse(0)))
                .sorted(Comparator.comparingDouble((Line l) -> l.meanRealised() - l.meanPredicted()))
                .limit(10)
                .toList();

        return new Result(r.type(), r.from(), r.to(), r.step().toSeconds(), r.horizon().toSeconds(), r.top(), moments,
                skipped, beyond, stats(items, byMoment.values()), byAlert, stats(clean, null), shortfall, List.copyOf(items));
    }

    static Stats stats(List<Item> items, java.util.Collection<List<Item>> moments) {
        List<Item> done = items.stream().filter(i -> i.realisedProfit() != null).toList();
        double predicted = done.stream().mapToDouble(Item::predictedProfit).sum();
        double realised = done.stream().mapToDouble(Item::realisedProfit).sum();
        long won = done.stream().filter(i -> i.realisedProfit() > 0).count();
        Double rank = null;
        if (moments != null) {
            List<Double> rhos = new ArrayList<>();
            for (List<Item> m : moments) {
                List<Item> priced = m.stream().filter(i -> i.realisedProfit() != null).toList();
                if (priced.size() >= 3) {
                    rhos.add(spearman(priced.stream().mapToDouble(Item::predictedProfit).toArray(),
                            priced.stream().mapToDouble(Item::realisedProfit).toArray()));
                }
            }
            rank = rhos.isEmpty() ? null : rhos.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
        return new Stats(done.size(), predicted, realised, predicted > 0 ? realised / predicted : null,
                done.isEmpty() ? null : won / (double) done.size(), rank);
    }

    /** Spearman rank correlation with average ranks for ties; 0 if either side is constant. */
    static double spearman(double[] a, double[] b) {
        double[] ra = ranks(a);
        double[] rb = ranks(b);
        double ma = mean(ra);
        double mb = mean(rb);
        double cov = 0;
        double va = 0;
        double vb = 0;
        for (int i = 0; i < ra.length; i++) {
            cov += (ra[i] - ma) * (rb[i] - mb);
            va += (ra[i] - ma) * (ra[i] - ma);
            vb += (rb[i] - mb) * (rb[i] - mb);
        }
        return va == 0 || vb == 0 ? 0 : cov / Math.sqrt(va * vb);
    }

    private static double[] ranks(double[] v) {
        Integer[] idx = new Integer[v.length];
        for (int i = 0; i < v.length; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, Comparator.comparingDouble(i -> v[i]));
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; ) {
            int j = i;
            while (j + 1 < v.length && v[idx[j + 1]] == v[idx[i]]) {
                j++;
            }
            double avg = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) {
                r[idx[k]] = avg;
            }
            i = j + 1;
        }
        return r;
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }
}
