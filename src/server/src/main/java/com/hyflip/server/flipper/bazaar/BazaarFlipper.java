package com.hyflip.server.flipper.bazaar;

import com.hyflip.server.alert.Alert;
import com.hyflip.server.alert.AlertType;
import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.engine.CostSheet;
import com.hyflip.server.engine.CraftPlanner;
import com.hyflip.server.engine.Liquidity;
import com.hyflip.server.engine.Market;
import com.hyflip.server.engine.Metrics;
import com.hyflip.server.engine.Plan;
import com.hyflip.server.engine.PriceSource;
import com.hyflip.server.engine.Quote;
import com.hyflip.server.engine.Venue;
import com.hyflip.server.flipper.Coins;
import com.hyflip.server.flipper.FlipContext;
import com.hyflip.server.flipper.FlipList;
import com.hyflip.server.flipper.FlipQuery;
import com.hyflip.server.flipper.FlipSupport;
import com.hyflip.server.flipper.FlipType;
import com.hyflip.server.flipper.Flipper;
import com.hyflip.server.flipper.Guide;
import com.hyflip.server.flipper.Opportunity;
import com.hyflip.server.flipper.OpportunityDetail;
import com.hyflip.server.flipper.RecipeBooks;
import com.hyflip.server.market.BazaarBook;
import com.hyflip.server.market.MarketService;
import com.hyflip.server.profile.PlayerContext;
import com.hyflip.server.profile.PlayerContextService;
import com.hyflip.server.profile.Unlocks;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Bazaar spreads: place a buy order just above the top buy order, then a sell offer just under the
 * lowest sell offer. Prices come from the live order book ({@link BazaarBook}) when the latest poll
 * has it, else from {@code quick_status}. One run is one unit; the daily estimate is a share of the
 * slower side's filled volume.
 * Information only: HyFlip never places orders. See {@code docs/flippers.md#bazaar-flipper}.
 */
@Service
public class BazaarFlipper implements Flipper {

    /** Smallest Bazaar price step: an order this much better than the best one goes first. */
    static final double STEP = com.hyflip.server.engine.Pricing.ORDER_STEP;
    /** The best order is "isolated" when the next price level is this far behind it. */
    static final double ISOLATED = 0.25;

    private final RecipeBooks books;
    private final MarketService markets;
    private final BazaarBook book;
    private final PlayerContextService contexts;
    private final Clock clock;

    @Autowired
    public BazaarFlipper(RecipeBooks books, MarketService markets, BazaarBook book, PlayerContextService contexts) {
        this(books, markets, book, contexts, Clock.systemUTC());
    }

    BazaarFlipper(RecipeBooks books, MarketService markets, BazaarBook book, PlayerContextService contexts,
                  Clock clock) {
        this.books = books;
        this.markets = markets;
        this.book = book;
        this.contexts = contexts;
        this.clock = clock;
    }

    @Override
    public FlipType type() {
        return FlipType.BAZAAR;
    }

    @Override
    public FlipList list(FlipQuery query) {
        PlayerContext player = query.apply(contexts.current());
        FlipContext ctx = context(query, player);
        HyFlipProperties.Bazaar settings = contexts.bazaarSettings();
        List<Opportunity> all = new ArrayList<>();
        for (String itemId : markets.bazaarItems()) {
            spread(itemId, live(query, itemId), ctx, settings).ifPresent(d -> all.add(d.summary()));
        }
        Double budget = query.budget() != null ? query.budget() : player.budget();
        return FlipSupport.page(all, query, budget, ctx.now(), new FlipList.Settings(PriceSource.ORDER,
                ctx.nextSessionIn().toSeconds(), budget, query.sort().param(), null));
    }

    @Override
    public Optional<OpportunityDetail> detail(String id, FlipQuery query) {
        return spread(id, live(query, id), context(query, query.apply(contexts.current())),
                contexts.bazaarSettings());
    }

    /** The live book top, only for a live query: a backtest uses stored prices. */
    private Optional<BazaarBook.Top> live(FlipQuery query, String itemId) {
        return query.at() == null ? book.top(itemId) : Optional.empty();
    }

    /** Spreads are always order to order, whatever the player's price source. */
    private FlipContext context(FlipQuery query, PlayerContext p) {
        PlayerContext orders = p.withPriceSource(PriceSource.ORDER);
        Duration next = query.nextSessionIn() != null ? query.nextSessionIn() : p.nextSessionIn();
        return new FlipContext(books.current(), markets.view(query.at()), orders, contexts.collections(), next,
                query.time(clock.millis()),
                CraftPlanner.DEFAULT_DEPTH).withHistory(markets::at).withRules(contexts.rules());
    }

    /**
     * Buy order one step above the best buy order, sell offer one step under the best sell offer, tax
     * on the sale. Uses the live book when it is fresh, else {@code quick_status}. Empty unless both
     * sides of the book have orders.
     */
    static Optional<OpportunityDetail> spread(String itemId, Optional<BazaarBook.Top> live, FlipContext ctx,
                                              HyFlipProperties.Bazaar settings) {
        // quick_status as stored: volumes, order counts and the fallback prices.
        Market stored = ctx.reference() != null ? ctx.reference() : ctx.market();
        Market.BazaarQuote q = stored.bazaar(itemId).orElse(null);
        if (q == null) {
            return Optional.empty();
        }
        BazaarBook.Top top = live.filter(t -> ctx.now() - t.at() <= FlipSupport.STALE_BAZAAR.toMillis()).orElse(null);
        boolean twoSided = top != null ? top.buyOrder() > 0 && top.sellOffer() > 0
                : q.buyPrice() > 0 && q.sellPrice() > 0;
        if (!twoSided) {
            return Optional.empty();
        }
        double bid = top != null ? top.buyOrder() + STEP : q.sellPrice();
        double ask = top != null ? top.sellOffer() - STEP : q.buyPrice();
        long at = top != null ? top.at() : q.takenAt();
        CostSheet sheet = sheet(itemId, bid, ask, at, ctx);
        Metrics m = Metrics.of(sheet, 0, ctx.nextSessionIn());
        Map<String, String> names = Map.of(itemId, ctx.book().name(itemId));

        // Buy orders fill when others insta-sell, sell offers when others insta-buy: the slower side limits.
        double filledPerDay = Math.min(q.sellMovingWeek(), q.buyMovingWeek()) / 7.0;
        Liquidity liquidity = Liquidity.rate(filledPerDay, 1);
        double runs = filledPerDay * settings.marketShare();

        // Stale, recent-change and volume checks compare quick_status with quick_status history, so a
        // live book price is not mistaken for a sudden move.
        CostSheet reference = sheet(itemId, q.sellPrice(), q.buyPrice(), q.takenAt(), ctx);
        // A wide spread is what this flipper looks for, not a warning.
        List<Alert> alerts = new ArrayList<>(FlipSupport.alerts(reference, liquidity, Unlocks.Unlock.NONE, names, ctx)
                .stream().filter(a -> a.type() != AlertType.WIDE_SPREAD).toList());
        if (q.sellOrders() < settings.thinBook() || q.buyOrders() < settings.thinBook()) {
            alerts.add(new Alert(AlertType.THIN_BOOK, "Only " + orders(q.sellOrders(), "buy order") + " and "
                    + orders(q.buyOrders(), "sell offer") + ": one player can move this price", itemId));
        }
        if (top != null) {
            isolated("buy order", top.buyOrder(), top.buyOrderAmount(), top.nextBuyOrder(), itemId, alerts);
            isolated("sell offer", top.sellOffer(), top.sellOfferAmount(), top.nextSellOffer(), itemId, alerts);
        }
        Opportunity o = new Opportunity(itemId, FlipType.BAZAAR, names.get(itemId), itemId, 1, sheet.cost(),
                sheet.value(), sheet.fees(), m.netProfit(), m.roi(), 0, 0, null, m.sessions(), m.profitPerSession(),
                Venue.BAZAAR, List.of(Venue.BAZAAR), liquidity, Unlocks.State.MET, true, List.copyOf(alerts), runs, m.netProfit() * runs);
        return Optional.of(new OpportunityDetail(o, sheet, Unlocks.Unlock.NONE,
                guide(bid, ask, top != null, sheet, m, filledPerDay, runs, names.get(itemId), ctx, settings), names));
    }

    private static CostSheet sheet(String itemId, double bid, double ask, long at, FlipContext ctx) {
        Quote buy = new Quote(itemId, 1, Venue.BAZAAR, bid, bid, 0, at, false);
        Quote sell = new Quote(itemId, 1, Venue.BAZAAR, ask, ask, ctx.player().fees().bazaar(ask), at, false);
        return CostSheet.of(List.of(new CostSheet.Line(itemId, 1, buy, Plan.buy(buy))), 0, itemId, sell);
    }

    /**
     * THIN_BOOK when the best order stands alone far ahead of the next price level: once it fills or
     * is pulled, the spread is much narrower.
     */
    private static void isolated(String side, double best, double amount, double next, String itemId,
                                 List<Alert> alerts) {
        if (next > 0 && Math.abs(best - next) / next > ISOLATED) {
            alerts.add(new Alert(AlertType.THIN_BOOK, "The best " + side + " (" + Coins.price(best) + " for "
                    + Coins.amount(amount) + ") stands far from the next one (" + Coins.price(next) + ")", itemId));
        }
    }

    static Guide guide(double bid, double ask, boolean live, CostSheet sheet, Metrics m, double filledPerDay,
                       double runs, String name, FlipContext ctx, HyFlipProperties.Bazaar settings) {
        long units = Math.max(1, (long) Math.floor(runs));
        List<String> steps = List.of(
                "Place a buy order for " + Coins.amount(units) + " × " + name + " at " + Coins.price(bid)
                        + " each, just above the best buy order (about " + Coins.format(units * bid) + ")",
                "When it fills, place a sell offer at " + Coins.price(ask) + " each, just under the best sell offer",
                "Check the order book before each order: the spread may have closed");
        double taxRate = ctx.player().fees().bazaarTaxRate();
        StringBuilder logic = new StringBuilder()
                .append("Buying at ").append(Coins.price(bid)).append(" and selling at ").append(Coins.price(ask))
                .append(" is a spread of ").append(Coins.price(ask - bid))
                .append(". After the ").append(trimPercent(taxRate)).append(" tax on the sale each unit ")
                .append(m.netProfit() >= 0 ? "earns " : "loses ").append(Coins.price(Math.abs(m.netProfit())))
                .append(". About ").append(Coins.amount(Math.round(filledPerDay)))
                .append(" units a day fill on the slower side; at a ")
                .append(trimPercent(settings.marketShare())).append(" share that is about ")
                .append(Coins.amount(Math.floor(runs))).append(" units, or ")
                .append(Coins.format(m.netProfit() * runs)).append(" a day.")
                .append(live ? " Prices are the best orders from the latest poll."
                        : " Prices are Bazaar averages over the top of the book (no live book yet), so the best"
                        + " single order can differ.");
        return new Guide(steps, logic.toString());
    }

    private static String orders(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    private static String trimPercent(double fraction) {
        double pct = fraction * 100;
        return (pct == Math.rint(pct) ? String.valueOf((long) pct) : String.valueOf(pct)) + "%";
    }
}
