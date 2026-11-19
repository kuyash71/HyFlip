package com.hyflip.server.flipper.forge;

import com.hyflip.server.alert.AlertType;
import com.hyflip.server.engine.Liquidity;
import com.hyflip.server.flipper.FlipQuery;
import com.hyflip.server.flipper.FlipSort;
import com.hyflip.server.flipper.Opportunity;
import com.hyflip.server.profile.PlayerContextService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Picks what to start in the free forge slots: the set of recipes with the highest total profit
 * per session that fits the budget. A recipe may fill several slots unless its output sells
 * slowly (one copy at LOW liquidity, two at MEDIUM), since copies compete for the same buyers.
 */
@Service
public class ForgePlanner {

    /** Only the best candidates by profit per session are combined (enough for 7 slots). */
    static final int CANDIDATES = 15;

    private final ForgeFlipper flipper;
    private final ForgeTracker tracker;
    private final PlayerContextService contexts;

    public ForgePlanner(ForgeFlipper flipper, ForgeTracker tracker, PlayerContextService contexts) {
        this.flipper = flipper;
        this.tracker = tracker;
        this.contexts = contexts;
    }

    public record Pick(int slot, Opportunity opportunity) {
    }

    /**
     * @param slots       slots planned: empty or finished (ready to collect) ones, or 1..n when
     *                    the request gave a count
     * @param budget      coins available, null for no limit
     * @param limitedBy   BUDGET when the budget left slots empty or forced cheaper picks, SLOTS
     *                    when every slot is used, NO_CANDIDATES when nothing profitable was found
     * @param candidates  recipes considered
     */
    public record Plan(List<Integer> slots, Double budget, long nextSessionInSeconds, List<Pick> picks,
                       double totalCost, double totalProfitPerSession, String limitedBy, int candidates) {
    }

    /**
     * @param budget        null uses the configured budget
     * @param nextSessionIn null uses the configured value
     * @param slotCount     plan this many slots instead of the free slots of the tracker
     * @param hide          rows with these alerts are not picked
     */
    public Plan plan(Double budget, Duration nextSessionIn, Integer slotCount, Set<AlertType> hide) {
        List<Integer> slots = new ArrayList<>();
        if (slotCount != null) {
            for (int i = 1; i <= slotCount; i++) {
                slots.add(i);
            }
        } else {
            tracker.slots().slots().stream()
                    .filter(s -> s.job() == null || s.job().finished())
                    .forEach(s -> slots.add(s.slot()));
        }
        Double money = budget != null ? budget : contexts.current().budget();
        FlipQuery query = new FlipQuery(null, nextSessionIn, FlipSort.PROFIT_PER_SESSION, null, false, false,
                FlipQuery.MAX_LIMIT, 0, hide, null, null);
        var list = flipper.list(query);
        List<Opportunity> candidates = list.items().stream()
                .filter(o -> o.complete() && o.profitPerSession() > 0 && o.cost() >= 0)
                .filter(o -> money == null || o.cost() <= money)
                .sorted(Comparator.comparingDouble(Opportunity::profitPerSession).reversed())
                .limit(CANDIDATES)
                .toList();
        long session = list.settings().nextSessionInSeconds();
        if (slots.isEmpty() || candidates.isEmpty()) {
            return new Plan(List.copyOf(slots), money, session, List.of(), 0, 0,
                    slots.isEmpty() ? "SLOTS" : "NO_CANDIDATES", candidates.size());
        }

        Search s = new Search(candidates, slots.size(), money);
        s.dfs(0, 0, 0, 0, new int[candidates.size()]);
        List<Pick> picks = new ArrayList<>();
        int next = 0;
        double cost = 0;
        double profit = 0;
        for (int i = 0; i < candidates.size(); i++) {
            for (int c = 0; c < s.best[i]; c++) {
                Opportunity o = candidates.get(i);
                picks.add(new Pick(slots.get(next++), o));
                cost += o.cost();
                profit += o.profitPerSession();
            }
        }
        // Unconstrained, the top candidate would fill every slot it may: did the budget cost anything?
        Search free = new Search(candidates, slots.size(), null);
        free.dfs(0, 0, 0, 0, new int[candidates.size()]);
        String limit = free.bestProfit > profit + 1e-6 ? "BUDGET" : picks.size() == slots.size() ? "SLOTS" : "NO_CANDIDATES";
        return new Plan(List.copyOf(slots), money, session, List.copyOf(picks), cost, profit, limit, candidates.size());
    }

    static int maxCopies(Opportunity o, int slots) {
        Liquidity.Level level = o.liquidity().level();
        return level == Liquidity.Level.LOW || level == Liquidity.Level.UNKNOWN ? 1
                : level == Liquidity.Level.MEDIUM ? Math.min(2, slots) : slots;
    }

    /** Exhaustive search over how many copies of each candidate to start, best total first. */
    static final class Search {
        final List<Opportunity> c;
        final int slots;
        final Double budget;
        final int[] best;
        double bestProfit = -1;
        final double bestPps;

        Search(List<Opportunity> candidates, int slots, Double budget) {
            this.c = candidates;
            this.slots = slots;
            this.budget = budget;
            this.best = new int[candidates.size()];
            this.bestPps = candidates.isEmpty() ? 0 : candidates.get(0).profitPerSession();
        }

        void dfs(int i, int used, double cost, double profit, int[] counts) {
            if (profit > bestProfit) {
                bestProfit = profit;
                System.arraycopy(counts, 0, best, 0, counts.length);
            }
            if (i >= c.size() || used >= slots) {
                return;
            }
            // Candidates are sorted by profit per session: the rest cannot beat this bound.
            if (profit + (slots - used) * c.get(i).profitPerSession() <= bestProfit) {
                return;
            }
            Opportunity o = c.get(i);
            int max = Math.min(maxCopies(o, slots), slots - used);
            for (int k = max; k >= 0; k--) {
                double total = cost + k * o.cost();
                if (budget != null && total > budget) {
                    continue;
                }
                counts[i] = k;
                dfs(i + 1, used + k, total, profit + k * o.profitPerSession(), counts);
            }
            counts[i] = 0;
        }
    }
}
