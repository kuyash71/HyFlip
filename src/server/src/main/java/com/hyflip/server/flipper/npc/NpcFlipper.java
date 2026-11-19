package com.hyflip.server.flipper.npc;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.engine.CostSheet;
import com.hyflip.server.engine.CraftPlanner;
import com.hyflip.server.engine.Liquidity;
import com.hyflip.server.engine.Metrics;
import com.hyflip.server.engine.Plan;
import com.hyflip.server.engine.Pricing;
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
import com.hyflip.server.flipper.RecipeBook;
import com.hyflip.server.flipper.RecipeBooks;
import com.hyflip.server.market.MarketService;
import com.hyflip.server.profile.PlayerContext;
import com.hyflip.server.profile.PlayerContextService;
import com.hyflip.server.profile.Unlocks;
import com.hyflip.server.source.neu.NeuItem;
import com.hyflip.server.source.neu.NeuRecipe;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Two routes between NPCs and the markets, ranked by profit per day:
 * <ul>
 *   <li><b>shop</b>: buy from an NPC shop, sell on the Bazaar or AH (NPC shop purchases are capped
 *       per item per day);</li>
 *   <li><b>sell</b>: buy on the Bazaar, sell to an NPC.</li>
 * </ul>
 * One run is one shop purchase or one unit. See {@code docs/flippers.md#npc-flipper}.
 */
@Service
public class NpcFlipper implements Flipper {

    static final String SHOP = "shop:";
    static final String SELL = "sell:";

    private final RecipeBooks books;
    private final MarketService markets;
    private final PlayerContextService contexts;
    private final Clock clock;

    @Autowired
    public NpcFlipper(RecipeBooks books, MarketService markets, PlayerContextService contexts) {
        this(books, markets, contexts, Clock.systemUTC());
    }

    NpcFlipper(RecipeBooks books, MarketService markets, PlayerContextService contexts, Clock clock) {
        this.books = books;
        this.markets = markets;
        this.contexts = contexts;
        this.clock = clock;
    }

    @Override
    public FlipType type() {
        return FlipType.NPC;
    }

    @Override
    public FlipList list(FlipQuery query) {
        PlayerContext player = query.apply(contexts.current());
        FlipContext ctx = context(query, player);
        HyFlipProperties.Npc limits = contexts.npcLimits();
        List<Opportunity> all = new ArrayList<>();
        for (NeuRecipe r : ctx.book().recipes(NeuRecipe.Type.NPC_SHOP)) {
            all.add(shop(r, ctx, limits).summary());
        }
        for (String itemId : markets.npcSellableBazaarItems()) {
            sell(itemId, ctx, limits).ifPresent(d -> all.add(d.summary()));
        }
        Double budget = query.budget() != null ? query.budget() : player.budget();
        return FlipSupport.page(all, query, budget, ctx.now(), new FlipList.Settings(player.priceSource(),
                ctx.nextSessionIn().toSeconds(), budget, query.sort().param(), null));
    }

    @Override
    public Optional<OpportunityDetail> detail(String id, FlipQuery query) {
        FlipContext ctx = context(query, query.apply(contexts.current()));
        HyFlipProperties.Npc limits = contexts.npcLimits();
        if (id.startsWith(SELL)) {
            return sell(id.substring(SELL.length()), ctx, limits);
        }
        if (id.startsWith(SHOP)) {
            return ctx.book().recipes(NeuRecipe.Type.NPC_SHOP).stream()
                    .filter(r -> id.equals(shopId(r)))
                    .findFirst()
                    .map(r -> shop(r, ctx, limits));
        }
        return Optional.empty();
    }

    private FlipContext context(FlipQuery query, PlayerContext player) {
        Duration next = query.nextSessionIn() != null ? query.nextSessionIn() : player.nextSessionIn();
        return new FlipContext(books.current(), markets.view(query.at()), player, contexts.collections(), next,
                query.time(clock.millis()),
                CraftPlanner.DEFAULT_DEPTH).withHistory(markets::at).withRules(contexts.rules());
    }

    static String shopId(NeuRecipe r) {
        return SHOP + r.sourceItem() + ":" + r.output();
    }

    /** NPC shop → Bazaar / AH. Other shop costs (items) are bought or crafted like recipe inputs. */
    static OpportunityDetail shop(NeuRecipe r, FlipContext ctx, HyFlipProperties.Npc limits) {
        CostSheet sheet = ctx.sheet(r);
        Metrics m = Metrics.of(sheet, 0, ctx.nextSessionIn());
        Map<String, String> names = FlipSupport.names(ctx.book(), sheet, r.output());
        Liquidity liquidity = Liquidity.of(ctx.market(), sheet.output());
        // The daily cap counts items bought from NPCs; selling to an NPC has no known cap.
        double units = limits.dailyLimit();
        if (sheet.output() != null && sheet.output().venue() != Venue.NPC) {
            Double volume = liquidity.dailyVolume();
            units = Math.min(units, volume == null ? 0 : volume * limits.marketShare());
        }
        double runs = units / r.outputCount();
        String npc = npcName(ctx.book(), r.sourceItem());
        String name = names.get(r.output()) + " · from " + npc;
        Opportunity o = summary(shopId(r), name, r.output(), r.outputCount(), sheet, m, liquidity, runs, ctx, names);
        return new OpportunityDetail(o, sheet, Unlocks.Unlock.NONE, shopGuide(r, sheet, m, runs, ctx, names, npc, limits),
                Map.copyOf(names));
    }

    /** Bazaar → NPC, per unit. Empty if the item cannot be bought on the Bazaar or sold to an NPC. */
    static Optional<OpportunityDetail> sell(String itemId, FlipContext ctx, HyFlipProperties.Npc limits) {
        Quote buy = Pricing.buyOnBazaar(ctx.market(), itemId, 1, ctx.player().priceSource()).orElse(null);
        Quote npc = Pricing.sellToNpc(ctx.market(), itemId, 1).orElse(null);
        if (buy == null || npc == null) {
            return Optional.empty();
        }
        CostSheet sheet = CostSheet.of(List.of(new CostSheet.Line(itemId, 1, buy, Plan.buy(buy))), 0, itemId, npc);
        Metrics m = Metrics.of(sheet, 0, ctx.nextSessionIn());
        Map<String, String> names = new LinkedHashMap<>();
        names.put(itemId, ctx.book().name(itemId));
        // Liquidity is about buying here: the NPC takes any amount.
        Liquidity liquidity = Liquidity.of(ctx.market(), buy);
        double runs = liquidity.dailyVolume() == null ? 0 : liquidity.dailyVolume() * limits.marketShare();
        Opportunity o = summary(SELL + itemId, names.get(itemId) + " · to NPC", itemId, 1, sheet, m, liquidity, runs,
                ctx, names);
        return Optional.of(new OpportunityDetail(o, sheet, Unlocks.Unlock.NONE, sellGuide(sheet, m, runs, ctx, names),
                Map.copyOf(names)));
    }

    private static Opportunity summary(String id, String name, String outputId, double outputCount, CostSheet sheet,
                                       Metrics m, Liquidity liquidity, double runs, FlipContext ctx,
                                       Map<String, String> names) {
        return new Opportunity(id, FlipType.NPC, name, outputId, outputCount, sheet.cost(), sheet.value(), sheet.fees(),
                m.netProfit(), m.roi(), 0, 0, null, m.sessions(), m.profitPerSession(),
                sheet.output() == null ? null : sheet.output().venue(), FlipSupport.buyVenues(sheet), liquidity,
                Unlocks.State.MET, sheet.complete(),
                FlipSupport.alerts(sheet, liquidity, Unlocks.Unlock.NONE, names, ctx),
                runs, sheet.complete() ? m.netProfit() * runs : null);
    }

    static Guide shopGuide(NeuRecipe r, CostSheet sheet, Metrics m, double runs, FlipContext ctx,
                           Map<String, String> names, String npc, HyFlipProperties.Npc limits) {
        List<String> steps = new ArrayList<>(FlipSupport.acquireSteps(sheet, names, ctx.player().priceSource()));
        String output = names.get(r.output());
        String what = (r.outputCount() == 1 ? "" : Coins.amount(r.outputCount()) + " × ") + output;
        StringBuilder buy = new StringBuilder("Buy ").append(what).append(" from ").append(npc);
        String where = location(ctx.book().data().items().get(r.sourceItem()));
        if (where != null) {
            buy.append(" (").append(where).append(')');
        }
        if (r.coins() > 0) {
            buy.append(" for ").append(Coins.format(r.coins())).append(" coins");
        }
        steps.add(buy.toString());
        steps.add(FlipSupport.sellStep(sheet.output(), output, ctx.player().priceSource()));
        steps.add("Repeat about " + Coins.amount(Math.floor(runs)) + " times a day");

        StringBuilder logic = FlipSupport.profitSentence(sheet, m);
        logic.append(" NPC shops sell at most ").append(limits.dailyLimit()).append(" of an item per player per day");
        if (sheet.output() != null && sheet.output().venue() != Venue.NPC) {
            logic.append(", and the market takes only part of its daily volume (")
                    .append(Math.round(limits.marketShare() * 100)).append("% assumed) without the price dropping");
        }
        logic.append(", so expect about ").append(Coins.format(m.netProfit() * runs)).append(" a day.");
        if (!sheet.complete()) {
            logic.append(" Some prices are missing (often a special currency such as Bits or Copper), so these"
                    + " numbers are incomplete.");
        }
        return new Guide(List.copyOf(steps), logic.toString());
    }

    static Guide sellGuide(CostSheet sheet, Metrics m, double runs, FlipContext ctx, Map<String, String> names) {
        List<String> steps = new ArrayList<>();
        Quote buy = sheet.inputs().get(0).plan().quote();
        Quote sell = sheet.output();
        String name = names.getOrDefault(sell.itemId(), sell.itemId());
        long units = Math.max(1, (long) Math.floor(runs));
        boolean order = ctx.player().priceSource() == com.hyflip.server.engine.PriceSource.ORDER;
        steps.add((order ? "Place a buy order for " : "Insta-buy ") + Coins.amount(units) + " × " + name + " at "
                + Coins.price(buy.unitPrice()) + " each" + (order ? ", just above the best buy order" : "")
                + " (about " + Coins.format(units * buy.unitPrice()) + ")");
        steps.add("Sell them to any NPC for " + Coins.price(sell.unitPrice()) + " each");
        if (order) {
            steps.add("If someone outbids your order, move it up while the margin lasts");
        }
        StringBuilder logic = FlipSupport.profitSentence(sheet, m);
        logic.append(" NPCs buy any amount at a fixed price, so the limit is how many you can buy on the Bazaar:"
                + " about ").append(Coins.amount(Math.floor(runs))).append(" a day without moving the price, or ")
                .append(Coins.format(m.netProfit() * runs)).append(" a day. Margins this small close quickly"
                + " when others notice them.");
        return new Guide(List.copyOf(steps), logic.toString());
    }

    /** "Zog" from the NEU display name "Zog (NPC)". */
    static String npcName(RecipeBook book, String npcId) {
        String name = book.name(npcId);
        return name.endsWith(" (NPC)") ? name.substring(0, name.length() - 6) : name;
    }

    private static final Map<String, String> ISLANDS = Map.ofEntries(
            Map.entry("hub", "Hub"), Map.entry("dynamic", "Private Island"), Map.entry("garden", "Garden"),
            Map.entry("farming_1", "The Farming Islands"), Map.entry("foraging_1", "The Park"),
            Map.entry("mining_1", "Gold Mine"), Map.entry("mining_2", "Deep Caverns"),
            Map.entry("mining_3", "Dwarven Mines"), Map.entry("crystal_hollows", "Crystal Hollows"),
            Map.entry("combat_1", "Spider's Den"), Map.entry("combat_3", "The End"),
            Map.entry("crimson_isle", "Crimson Isle"), Map.entry("winter", "Jerry's Workshop"),
            Map.entry("dungeon_hub", "Dungeon Hub"), Map.entry("rift", "The Rift"),
            Map.entry("fishing_1", "Backwater Bayou"));

    static String location(NeuItem npc) {
        if (npc == null || npc.island() == null) {
            return null;
        }
        String known = ISLANDS.get(npc.island());
        if (known != null) {
            return known;
        }
        // Unknown ID: "lotus_atoll" → "Lotus Atoll".
        StringBuilder out = new StringBuilder();
        for (String word : npc.island().split("_")) {
            if (!word.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }
}
