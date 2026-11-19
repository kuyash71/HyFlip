package com.hyflip.server.flipper;

import com.hyflip.server.alert.Alert;
import com.hyflip.server.alert.AlertType;
import com.hyflip.server.engine.CostSheet;
import com.hyflip.server.engine.Liquidity;
import com.hyflip.server.engine.Market;
import com.hyflip.server.engine.Metrics;
import com.hyflip.server.engine.Plan;
import com.hyflip.server.engine.PriceSource;
import com.hyflip.server.engine.Pricing;
import com.hyflip.server.engine.Quote;
import com.hyflip.server.engine.Venue;
import com.hyflip.server.profile.Unlocks;
import com.hyflip.server.source.neu.NeuRecipe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Building blocks shared by the recipe flippers: filters, paging, alerts, names and guide steps. */
public final class FlipSupport {

    /** Bazaar prices older than this get a STALE_PRICE alert (the collector polls every minute). */
    public static final Duration STALE_BAZAAR = Duration.ofMinutes(30);
    /** Lowest BINs older than this get a STALE_PRICE alert (the auction scan runs every 30 minutes). */
    public static final Duration STALE_AUCTION = Duration.ofHours(3);
    /** Bazaar prices are compared with this long ago for RECENT_CHANGE. */
    public static final Duration RECENT_WINDOW = Duration.ofMinutes(10);
    /** Relative move over {@link #RECENT_WINDOW} that raises RECENT_CHANGE. */
    public static final double RECENT_CHANGE = 0.25;
    /**
     * WIDE_SPREAD is raised only when acting instantly instead of waiting for the order would take
     * more than this share of the profit: a wide spread on Iron Ingot costs nothing that matters.
     */
    public static final double WIDE_SPREAD_PROFIT_SHARE = 0.5;

    private FlipSupport() {
    }

    /** Builds the summary row of a priced recipe. */
    public static Opportunity opportunity(FlipType type, NeuRecipe r, CostSheet sheet, Metrics m, long baseSeconds,
                                          FlipContext ctx, Map<String, String> names) {
        Liquidity liquidity = Liquidity.of(ctx.market(), sheet.output());
        Unlocks.Unlock unlock = ctx.unlock(r.output());
        return new Opportunity(r.output(), type, names.get(r.output()), r.output(), r.outputCount(), sheet.cost(),
                sheet.value(), sheet.fees(), m.netProfit(), m.roi(), baseSeconds, m.durationSeconds(),
                m.profitPerHour(), m.sessions(), m.profitPerSession(),
                sheet.output() == null ? null : sheet.output().venue(), buyVenues(sheet), liquidity, unlock.state(), sheet.complete(),
                alerts(sheet, liquidity, unlock, names, ctx), null, null, fill(sheet, ctx).seconds());
    }

    /**
     * Time for one run's Bazaar orders to fill at current volume, if they stay the best order: buy
     * orders fill as others insta-sell, sell offers as others insta-buy. The input orders run side
     * by side, the sale starts once the output exists.
     *
     * @param buySeconds  slowest input buy order, 0 if none
     * @param sellSeconds the output's sell offer, 0 if none
     * @param known       false if a leg trades nothing
     */
    public record Fill(double buySeconds, double sellSeconds, boolean known) {
        static final Fill NONE = new Fill(0, 0, true);

        public Long seconds() {
            double total = buySeconds + sellSeconds;
            return !known || total <= 0 ? null : Math.round(total);
        }
    }

    public static Fill fill(CostSheet sheet, FlipContext ctx) {
        if (ctx.player().priceSource() != PriceSource.ORDER) {
            return Fill.NONE;
        }
        Market market = ctx.market();
        double buy = 0;
        boolean known = true;
        Map<String, Double> quantities = new LinkedHashMap<>();
        for (CostSheet.Line line : sheet.inputs()) {
            line.plan().purchases().values().stream().filter(q -> q.venue() == Venue.BAZAAR)
                    .forEach(q -> quantities.merge(q.itemId(), q.quantity(), Double::sum));
        }
        for (Map.Entry<String, Double> e : quantities.entrySet()) {
            Market.BazaarQuote b = market.bazaar(e.getKey()).orElse(null);
            if (b == null || b.sellMovingWeek() <= 0) {
                known = false;
                continue;
            }
            buy = Math.max(buy, e.getValue() / (b.sellMovingWeek() / WEEK_SECONDS));
        }
        double sell = 0;
        Quote out = sheet.output();
        if (out != null && out.venue() == Venue.BAZAAR) {
            Market.BazaarQuote b = market.bazaar(out.itemId()).orElse(null);
            if (b == null || b.buyMovingWeek() <= 0) {
                known = false;
            } else {
                sell = out.quantity() / (b.buyMovingWeek() / WEEK_SECONDS);
            }
        }
        return new Fill(buy, sell, known);
    }

    private static final double WEEK_SECONDS = 7 * 24 * 3600.0;

    /** Guide line for {@link #fill}, empty when nothing waits on an order. */
    public static java.util.Optional<String> fillStep(CostSheet sheet, FlipContext ctx) {
        Fill f = fill(sheet, ctx);
        if (f.buySeconds() + f.sellSeconds() <= 0) {
            return java.util.Optional.empty();
        }
        if (!f.known()) {
            return java.util.Optional.of("Some orders may not fill: an item here has not traded this week");
        }
        StringBuilder s = new StringBuilder("At current volume the orders fill in about ")
                .append(Coins.duration(Math.max(1, f.seconds())));
        if (f.buySeconds() > 0 && f.sellSeconds() > 0) {
            s.append(" (inputs ").append(Coins.duration(Math.max(1, Math.round(f.buySeconds()))))
                    .append(", the sale ").append(Coins.duration(Math.max(1, Math.round(f.sellSeconds())))).append(")");
        }
        return java.util.Optional.of(s.append(" if they stay the best; slower when others outbid you").toString());
    }

    /** Venues of every purchase in the sheet, crafted intermediates included. */
    public static List<Venue> buyVenues(CostSheet sheet) {
        java.util.Set<Venue> venues = new java.util.LinkedHashSet<>();
        for (CostSheet.Line line : sheet.inputs()) {
            line.plan().purchases().values().forEach(q -> venues.add(q.venue()));
        }
        return List.copyOf(venues);
    }

    public static boolean accept(Opportunity o, FlipQuery query, Double budget) {
        if (query.search() != null && (o.name() == null
                || !o.name().toLowerCase(java.util.Locale.ROOT).contains(query.search()))) {
            return false;
        }
        if (!query.hideAlerts().isEmpty() && o.alerts().stream().anyMatch(a -> query.hideAlerts().contains(a.type()))) {
            return false;
        }
        if (!query.includeLocked() && o.unlock() == Unlocks.State.LOCKED) {
            return false;
        }
        if (!query.includeIncomplete() && !o.complete()) {
            return false;
        }
        if (budget != null && o.cost() > budget) {
            return false;
        }
        Double volume = o.liquidity().dailyVolume();
        return query.minVolume() == null || volume == null || volume >= query.minVolume();
    }

    /** Filters, sorts and pages evaluated opportunities. */
    public static FlipList page(List<Opportunity> all, FlipQuery query, Double budget, long generatedAt,
                                FlipList.Settings settings) {
        List<Opportunity> matching = new ArrayList<>(all.stream().filter(o -> accept(o, query, budget)).toList());
        matching.sort(query.sort().order());
        List<Opportunity> page = matching.stream().skip(query.offset()).limit(query.limit()).toList();
        return new FlipList(matching.size(), generatedAt, settings, page);
    }

    /** Display names of the output and every item in the sheet's plans. */
    public static Map<String, String> names(RecipeBook book, CostSheet sheet, String outputId) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(outputId, book.name(outputId));
        for (CostSheet.Line line : sheet.inputs()) {
            addNames(book, line.plan(), names);
        }
        return names;
    }

    private static void addNames(RecipeBook book, Plan plan, Map<String, String> names) {
        names.computeIfAbsent(plan.itemId(), book::name);
        plan.inputs().forEach(p -> addNames(book, p, names));
    }

    public static List<Alert> alerts(CostSheet sheet, Liquidity liquidity, Unlocks.Unlock unlock,
                                     Map<String, String> names, FlipContext ctx) {
        Market market = ctx.market();
        long now = ctx.now();
        List<Alert> alerts = new ArrayList<>();
        for (String id : sheet.missingPrices()) {
            alerts.add(new Alert(AlertType.MISSING_PRICE, "No price for " + names.getOrDefault(id, id), id));
        }
        // One quote per item: the same ingredient can appear in several lines and crafted parts.
        Map<String, Quote> merged = new LinkedHashMap<>();
        for (CostSheet.Line line : sheet.inputs()) {
            line.plan().purchases().values().forEach(q -> merged.merge(q.itemId(), q, (a, b) -> new Quote(a.itemId(),
                    a.quantity() + b.quantity(), a.venue(), a.unitPrice(), a.gross() + b.gross(), 0, a.priceAt(),
                    a.estimated() || b.estimated())));
        }
        List<Quote> quotes = new ArrayList<>(merged.values());
        double profit = sheet.netProfit();
        for (Quote q : quotes) {
            String name = names.getOrDefault(q.itemId(), q.itemId());
            if (q.estimated()) {
                alerts.add(new Alert(AlertType.ESTIMATED_PRICE,
                        name + ": valued at the median of recent sales, not a live listing", q.itemId()));
            }
            if (q.venue() == Venue.BAZAAR) {
                market.bazaar(q.itemId()).ifPresent(b -> {
                    if (Liquidity.rate(b.dailyVolume(), q.quantity()).level() == Liquidity.Level.LOW) {
                        alerts.add(new Alert(AlertType.LOW_VOLUME, name + ": only about "
                                + Math.round(b.dailyVolume()) + " traded per day for " + Coins.amount(q.quantity())
                                + " needed", q.itemId()));
                    }
                    // Buy orders fill when others insta-sell (sellMovingWeek).
                    double extra = (b.buyPrice() - q.unitPrice()) * q.quantity();
                    if (wide(b, extra, profit, ctx)) {
                        alerts.add(new Alert(AlertType.WIDE_SPREAD, name + ": a buy order at "
                                + Coins.price(q.unitPrice()) + " fills slowly (about "
                                + Coins.amount(Math.round(b.sellMovingWeek() / 7.0)) + " a day); buying now costs "
                                + Coins.price(b.buyPrice()) + " each, " + Coins.format(extra) + " more, "
                                + share(extra, profit) + " of the profit", q.itemId()));
                    }
                });
            }
        }
        recentChanges(quotes, sheet.output(), names, ctx, alerts);
        manipulation(quotes, sheet.output(), names, ctx, alerts);
        if (sheet.output() != null) {
            quotes.add(sheet.output());
        }
        for (Quote q : quotes) {
            Duration limit = q.venue() == Venue.BAZAAR ? STALE_BAZAAR : STALE_AUCTION;
            if (q.priceAt() != null && now - q.priceAt() > limit.toMillis()) {
                alerts.add(new Alert(AlertType.STALE_PRICE, names.getOrDefault(q.itemId(), q.itemId())
                        + ": price is " + Coins.duration((now - q.priceAt()) / 1000) + " old", q.itemId()));
            }
        }
        Quote out = sheet.output();
        if (out != null && out.venue() == Venue.AUCTION) {
            String name = names.getOrDefault(out.itemId(), out.itemId());
            market.auction(out.itemId()).ifPresent(a -> {
                if (a.sales() < ctx.rules().minAuctionSales()) {
                    alerts.add(new Alert(AlertType.FEW_SALES, name + ": " + (a.sales() == 0
                            ? "no clean sales in the last 7 days; the value is the lowest of "
                            + a.listings() + (a.listings() == 1 ? " listing" : " listings")
                            : "only " + a.sales() + (a.sales() == 1 ? " clean sale" : " clean sales")
                            + " in the last 7 days"), out.itemId()));
                }
            });
        }
        if (out != null && out.venue() == Venue.BAZAAR && ctx.player().priceSource() == PriceSource.ORDER) {
            market.bazaar(out.itemId()).ifPresent(b -> {
                // Sell offers fill when others insta-buy (buyMovingWeek).
                double extra = (out.unitPrice() - b.sellPrice()) * out.quantity();
                if (wide(b, extra, profit, ctx)) {
                    alerts.add(new Alert(AlertType.WIDE_SPREAD, names.getOrDefault(out.itemId(), out.itemId())
                            + ": a sell offer at " + Coins.price(out.unitPrice()) + " fills slowly (about "
                            + Coins.amount(Math.round(b.buyMovingWeek() / 7.0)) + " a day); selling now pays "
                            + Coins.price(b.sellPrice()) + " each, " + Coins.format(extra) + " less, "
                            + share(extra, profit) + " of the profit", out.itemId()));
                }
            });
        }
        if (liquidity.level() == Liquidity.Level.LOW) {
            long daily = Math.round(liquidity.dailyVolume());
            alerts.add(new Alert(AlertType.LOW_VOLUME, daily == 0 ? "No recent sales seen"
                    : "Only about " + daily + " traded per day", sheet.output() == null ? null : sheet.output().itemId()));
        }
        if (unlock.state() == Unlocks.State.UNKNOWN) {
            String unknown = String.join(", ", unlock.checks().stream()
                    .filter(c -> c.state() == Unlocks.State.UNKNOWN).map(Unlocks.Check::requirement).toList());
            alerts.add(new Alert(AlertType.UNLOCK_UNKNOWN, "Cannot check " + unknown
                    + "; set it in the config to hide locked recipes", null));
        }
        return List.copyOf(alerts);
    }

    /**
     * The order sits more than {@code alerts.wide-spread} from the instant price, and acting
     * instantly would take more than {@link #WIDE_SPREAD_PROFIT_SHARE} of a positive profit.
     */
    private static boolean wide(Market.BazaarQuote b, double extra, double profit, FlipContext ctx) {
        return ctx.player().priceSource() == PriceSource.ORDER && b.sellPrice() > 0 && b.buyPrice() > 0
                && b.buyPrice() / b.sellPrice() - 1 > ctx.rules().wideSpread()
                && profit > 0 && extra > WIDE_SPREAD_PROFIT_SHARE * profit;
    }

    private static String share(double extra, double profit) {
        double pct = extra / profit * 100;
        return pct >= 100 ? "more than all" : Math.round(pct) + "%";
    }

    /** Averages from less history than this are not trusted for MANIPULATION. */
    static final double MANIPULATION_MIN_DAYS = 1;

    /**
     * MANIPULATION for Bazaar purchases and a Bazaar-sold output priced far above the 7-day average
     * of the same side of the book.
     */
    private static void manipulation(List<Quote> purchases, Quote output, Map<String, String> names,
                                     FlipContext ctx, List<Alert> alerts) {
        boolean orders = ctx.player().priceSource() == PriceSource.ORDER;
        for (Quote q : purchases) {
            if (q.venue() == Venue.BAZAAR) {
                // Buying by order pays about the insta-sell price, buying instantly the insta-buy price.
                ctx.market().bazaarAverage(q.itemId()).ifPresent(a -> manipulation(q.itemId(), q.unitPrice(),
                        orders ? a.sellPrice() : a.buyPrice(), a.days(), names, ctx, alerts));
            }
        }
        if (output != null && output.venue() == Venue.BAZAAR) {
            ctx.market().bazaar(output.itemId()).ifPresent(b -> ctx.market().bazaarAverage(output.itemId())
                    .ifPresent(a -> manipulation(output.itemId(), orders ? b.buyPrice() : b.sellPrice(),
                            orders ? a.buyPrice() : a.sellPrice(), a.days(), names, ctx, alerts)));
        }
    }

    private static void manipulation(String itemId, double price, double average, double days,
                                     Map<String, String> names, FlipContext ctx, List<Alert> alerts) {
        double ratio = ctx.rules().manipulationRatio();
        if (days < MANIPULATION_MIN_DAYS || average <= 0 || price < 1 || price <= ratio * average) {
            return;
        }
        String history = days < 6.5 ? " (" + Math.round(days * 10) / 10.0 + " days of history)" : "";
        alerts.add(new Alert(AlertType.MANIPULATION, names.getOrDefault(itemId, itemId) + ": price is "
                + Coins.price(price) + ", " + Math.round(price / average * 10) / 10.0 + "× its 7-day average of "
                + Coins.price(average) + history, itemId));
    }

    /** RECENT_CHANGE for Bazaar purchases and a Bazaar-sold output whose price moved sharply. */
    private static void recentChanges(List<Quote> purchases, Quote output, Map<String, String> names, FlipContext ctx,
                                      List<Alert> alerts) {
        // Stored snapshots on both ends: the live book's best order is not compared with an average.
        Market before = ctx.recent();
        Market current = ctx.reference() != null ? ctx.reference() : ctx.market();
        if (before == null) {
            return;
        }
        PriceSource source = ctx.player().priceSource();
        for (Quote q : purchases) {
            if (q.venue() == Venue.BAZAAR) {
                Pricing.buyOnBazaar(current, q.itemId(), 1, source).ifPresent(now ->
                        Pricing.buyOnBazaar(before, q.itemId(), 1, source)
                                .ifPresent(old -> recentChange(q.itemId(), now.unitPrice(), old.unitPrice(), names,
                                        alerts)));
            }
        }
        if (output != null && output.venue() == Venue.BAZAAR) {
            Pricing.sellOnBazaar(current, output.itemId(), 1, source, ctx.player().fees()).ifPresent(now ->
                    Pricing.sellOnBazaar(before, output.itemId(), 1, source, ctx.player().fees())
                            .ifPresent(old -> recentChange(output.itemId(), now.unitPrice(), old.unitPrice(), names,
                                    alerts)));
        }
    }

    private static void recentChange(String itemId, double now, double before, Map<String, String> names,
                                     List<Alert> alerts) {
        // Moves under a coin are noise on items worth a few coins (Iron Ingot going from 0.6 to 0.9).
        if (before > 0 && Math.abs(now - before) >= 1 && Math.abs(now - before) / before > RECENT_CHANGE) {
            alerts.add(new Alert(AlertType.RECENT_CHANGE, names.getOrDefault(itemId, itemId)
                    + ": price is " + Coins.price(now) + ", was " + Coins.price(before) + " "
                    + RECENT_WINDOW.toMinutes() + " min ago", itemId));
        }
    }

    /**
     * Steps to get the inputs: purchases summed per item, then intermediate crafts deepest first.
     */
    public static List<String> acquireSteps(CostSheet sheet, Map<String, String> names, PriceSource source) {
        List<String> steps = new ArrayList<>();
        Map<String, Quote> buys = new LinkedHashMap<>();
        List<Plan> crafts = new ArrayList<>();
        for (CostSheet.Line line : sheet.inputs()) {
            line.plan().purchases().forEach((id, q) -> buys.merge(id, q, (a, b) -> new Quote(a.itemId(),
                    a.quantity() + b.quantity(), a.venue(), a.unitPrice(), a.gross() + b.gross(), 0, a.priceAt(),
                    a.estimated() || b.estimated())));
            crafts.addAll(line.plan().craftSteps());
        }
        for (Quote q : buys.values()) {
            String what = Coins.amount(q.quantity()) + " × " + names.getOrDefault(q.itemId(), q.itemId());
            steps.add(q.venue() == Venue.BAZAAR
                    ? "Buy " + what + " on the Bazaar " + (source == PriceSource.ORDER
                    ? "with a buy order at " + Coins.price(q.unitPrice()) + " each, just above the best buy order"
                    : "(insta-buy)") + ", about " + Coins.format(q.gross())
                    : "Buy " + what + " on the Auction House (BIN), about " + Coins.format(q.gross()));
        }
        for (CostSheet.Line line : sheet.inputs()) {
            if (line.plan().method() == Plan.Method.MISSING) {
                steps.add("Get " + Coins.amount(line.quantity()) + " × " + names.getOrDefault(line.itemId(),
                        line.itemId()) + " (no market price known)");
            }
        }
        for (Plan c : crafts) {
            String step = "Craft " + Coins.amount(c.quantity()) + " × " + names.getOrDefault(c.itemId(), c.itemId());
            if (c.savings() != null) {
                step += " (saves about " + Coins.format(c.savings()) + " over buying)";
            }
            steps.add(step);
        }
        return steps;
    }

    public static String sellStep(Quote sell, String outputName, PriceSource source) {
        if (sell == null) {
            return "Sell " + outputName + " (no market price known)";
        }
        return switch (sell.venue()) {
            case BAZAAR -> "Sell on the Bazaar " + (source == PriceSource.ORDER ? "with a sell offer" : "(insta-sell)");
            case AUCTION -> "List on the Auction House as BIN just under the lowest BIN";
            case NPC -> "Sell to any NPC";
        };
    }

    /** "Inputs cost X; the output is worth Y minus Z in fees, so one run earns N." */
    public static StringBuilder profitSentence(CostSheet sheet, Metrics m) {
        StringBuilder logic = new StringBuilder();
        logic.append("Inputs cost ").append(Coins.format(sheet.cost())).append("; the output is worth ")
                .append(Coins.format(sheet.value()));
        if (sheet.fees() > 0) {
            logic.append(" minus ").append(Coins.format(sheet.fees())).append(" in fees");
        }
        logic.append(", so one run ").append(m.netProfit() >= 0 ? "earns " : "loses ")
                .append(Coins.format(Math.abs(m.netProfit()))).append('.');
        long crafted = sheet.inputs().stream().filter(l -> l.plan().method() == Plan.Method.CRAFT).count();
        if (crafted > 0) {
            logic.append(" Crafting ").append(crafted == 1 ? "one input" : crafted + " inputs")
                    .append(" yourself is cheaper than buying ").append(crafted == 1 ? "it" : "them").append('.');
        }
        return logic;
    }
}
