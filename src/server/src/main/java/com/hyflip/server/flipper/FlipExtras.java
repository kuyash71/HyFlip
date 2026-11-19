package com.hyflip.server.flipper;

import com.hyflip.server.engine.Market;
import com.hyflip.server.engine.Plan;
import com.hyflip.server.engine.Venue;
import com.hyflip.server.market.MarketService;
import com.hyflip.server.source.neu.NeuItem;
import com.hyflip.server.source.neu.NeuRecipe;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * What the UI shows next to a flip beyond its cost sheet: rarity, unlock requirements, the output's
 * price against its 7-day level, its daily price swing, and the crafting grids of every crafted step.
 */
@Service
public class FlipExtras {

    /** Less history than this gives no trend. */
    static final double MIN_TREND_DAYS = 1;

    private final RecipeBooks books;
    private final MarketService markets;

    public FlipExtras(RecipeBooks books, MarketService markets) {
        this.books = books;
        this.markets = markets;
    }

    /**
     * @param rarity       of the output ({@code LEGENDARY}), null if unknown
     * @param requirements unlock requirements of the recipe ("HOTM 2", "Diamond VIII"); empty if none
     * @param trend        output price now ÷ its 7-day level − 1 (Bazaar: insta-sell against its average;
     *                     Auction House: lowest BIN against the median sale); null if unknown
     * @param volatility   daily price swing of a Bazaar output (standard deviation ÷ price); null if unknown
     */
    public record Row(String rarity, List<String> requirements, Double trend, Double volatility) {
    }

    /** One crafting cell: the item and how many. */
    public record Cell(String item, double count) {
    }

    /**
     * How one step is made.
     *
     * @param grid crafting: the nine cells row by row (null = empty); null for forge recipes
     */
    public record Recipe(String type, List<Cell> grid, double outputCount, Long durationSeconds, double coins) {
    }

    /** Extras for a page of rows, against the market of now. */
    public Map<String, Row> rows(FlipType type, List<Opportunity> items) {
        RecipeBook book = books.current();
        Market market = markets.now();
        Map<String, Row> out = new LinkedHashMap<>();
        for (Opportunity o : items) {
            out.put(o.id(), row(type, o, book, market));
        }
        return out;
    }

    Row row(FlipType type, Opportunity o, RecipeBook book, Market market) {
        List<String> needs = type == FlipType.FORGE || type == FlipType.CRAFT
                ? book.requirements(o.outputId()).stream().map(FlipExtras::requirement).toList()
                : List.of();
        Double trend = null;
        Double volatility = null;
        Optional<Market.BazaarAverage> avg = market.bazaarAverage(o.outputId());
        Optional<Market.BazaarQuote> bz = market.bazaar(o.outputId());
        if (bz.isPresent() && o.sellVenue() != Venue.AUCTION) {
            if (avg.isPresent() && avg.get().sellPrice() > 0 && avg.get().days() >= MIN_TREND_DAYS
                    && bz.get().sellPrice() > 0) {
                trend = bz.get().sellPrice() / avg.get().sellPrice() - 1;
            }
            volatility = avg.map(Market.BazaarAverage::volatility).orElse(null);
        } else {
            Market.AuctionQuote ah = market.auction(o.outputId()).orElse(null);
            if (ah != null && ah.lowestBin() != null && ah.medianSale() != null && ah.medianSale() > 0) {
                trend = ah.lowestBin() / ah.medianSale() - 1;
            }
        }
        return new Row(rarity(book, o.outputId()), needs, trend, volatility);
    }

    /** Rarity of every item in a detail's names map, by ID; items without one are left out. */
    public Map<String, String> rarities(Set<String> itemIds) {
        RecipeBook book = books.current();
        Map<String, String> out = new LinkedHashMap<>();
        for (String id : itemIds) {
            String r = rarity(book, id);
            if (r != null) {
                out.put(id, r);
            }
        }
        return out;
    }

    /**
     * The recipe behind the flip itself and behind every crafted ingredient, by output ID. When an item has
     * several recipes, the one whose ingredients match the plan is used.
     */
    public Map<String, Recipe> recipes(FlipType type, OpportunityDetail d) {
        RecipeBook book = books.current();
        Map<String, Recipe> out = new LinkedHashMap<>();
        NeuRecipe.Type rootType = switch (type) {
            case FORGE -> NeuRecipe.Type.FORGE;
            case CRAFT -> NeuRecipe.Type.CRAFTING;
            default -> null;
        };
        if (rootType != null) {
            Set<String> ids = new HashSet<>();
            d.sheet().inputs().forEach(l -> ids.add(l.itemId()));
            match(book.recipes(rootType, d.summary().outputId()), ids).ifPresent(r -> out.put(r.output(), view(r)));
        }
        for (var line : d.sheet().inputs()) {
            crafted(line.plan(), book, out);
        }
        return out;
    }

    private void crafted(Plan p, RecipeBook book, Map<String, Recipe> out) {
        if (p.method() == Plan.Method.CRAFT && !out.containsKey(p.itemId())) {
            Set<String> ids = new HashSet<>();
            p.inputs().forEach(i -> ids.add(i.itemId()));
            match(book.recipes(NeuRecipe.Type.CRAFTING, p.itemId()), ids).ifPresent(r -> out.put(p.itemId(), view(r)));
        }
        p.inputs().forEach(i -> crafted(i, book, out));
    }

    private static Optional<NeuRecipe> match(List<NeuRecipe> recipes, Set<String> inputIds) {
        return recipes.stream().filter(r -> r.inputs().keySet().equals(inputIds)).findFirst()
                .or(() -> recipes.stream().findFirst());
    }

    static Recipe view(NeuRecipe r) {
        List<Cell> grid = null;
        if (r.grid() != null) {
            grid = new ArrayList<>(9);
            for (String raw : r.grid()) {
                grid.add(raw == null ? null : cell(raw));
            }
        }
        return new Recipe(r.type().name(), grid, r.outputCount(), r.durationSeconds(), r.coins());
    }

    private static Cell cell(String raw) {
        int colon = raw.lastIndexOf(':');
        if (colon > 0) {
            try {
                return new Cell(raw.substring(0, colon), Double.parseDouble(raw.substring(colon + 1)));
            } catch (NumberFormatException e) {
                // not an amount
            }
        }
        return new Cell(raw, 1);
    }

    static String rarity(RecipeBook book, String itemId) {
        NeuItem item = book.data().items().get(itemId);
        return item == null ? null : item.rarity();
    }

    /** "HOTM 5" for Heart of the Mountain, "Diamond VIII" for collections and the rest. */
    static String requirement(NeuItem.Requirement r) {
        return r.kind() == NeuItem.Requirement.Kind.HOTM ? "HOTM " + r.level() : r.name() + " " + roman(r.level());
    }

    static String roman(int n) {
        if (n <= 0 || n > 3999) {
            return String.valueOf(n);
        }
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] digits = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (n >= values[i]) {
                s.append(digits[i]);
                n -= values[i];
            }
        }
        return s.toString();
    }
}
