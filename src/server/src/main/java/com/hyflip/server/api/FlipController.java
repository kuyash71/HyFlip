package com.hyflip.server.api;

import com.hyflip.server.alert.AlertType;
import com.hyflip.server.engine.CostSheet;
import com.hyflip.server.flipper.FlipExtras;
import com.hyflip.server.flipper.FlipList;
import com.hyflip.server.engine.PriceSource;
import com.hyflip.server.engine.Venue;
import com.hyflip.server.flipper.FlipQuery;
import com.hyflip.server.flipper.FlipSort;
import com.hyflip.server.flipper.FlipType;
import com.hyflip.server.flipper.Flipper;
import com.hyflip.server.flipper.Guide;
import com.hyflip.server.flipper.Opportunity;
import com.hyflip.server.flipper.OpportunityDetail;
import com.hyflip.server.profile.Unlocks;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Ranked opportunities per flipper: {@code /flips/forge}, {@code /craft}, {@code /npc}, {@code /bazaar}. */
@RestController
@RequestMapping("/api/v1/flips")
public class FlipController {

    private final Map<FlipType, Flipper> flippers = new EnumMap<>(FlipType.class);
    private final FlipExtras extras;

    public FlipController(List<Flipper> flippers, FlipExtras extras) {
        flippers.forEach(f -> this.flippers.put(f.type(), f));
        this.extras = extras;
    }

    /**
     * A {@link FlipList} with display extras per row.
     *
     * @param extras rarity, requirements, trend and price swing by opportunity ID
     */
    public record ListView(long total, long generatedAt, FlipList.Settings settings, List<Opportunity> items,
                           Map<String, FlipExtras.Row> extras) {
    }

    /**
     * An {@link OpportunityDetail} with the recipe grids of every crafted step and the item rarities.
     *
     * @param recipes  by output ID: the flip's own recipe and those of crafted ingredients
     * @param rarities by item ID, for every item in {@code names} that has one
     */
    public record DetailView(Opportunity summary, CostSheet sheet, Unlocks.Unlock unlock, Guide guide,
                             Map<String, String> names, Map<String, FlipExtras.Recipe> recipes,
                             Map<String, String> rarities, FlipExtras.Row extras) {
    }

    @GetMapping("/{type}")
    public ListView list(@PathVariable String type,
                         @RequestParam(required = false) Double budget,
                         @RequestParam(required = false) String nextSessionIn,
                         @RequestParam(required = false) String sort,
                         @RequestParam(required = false) Double minVolume,
                         @RequestParam(defaultValue = "false") boolean includeLocked,
                         @RequestParam(defaultValue = "false") boolean includeIncomplete,
                         @RequestParam(defaultValue = "" + FlipQuery.DEFAULT_LIMIT) int limit,
                         @RequestParam(defaultValue = "0") int offset,
                         @RequestParam(required = false) List<String> hideAlerts,
                         @RequestParam(required = false) String search,
                         @RequestParam(required = false) Integer hotm,
                         @RequestParam(required = false) Integer craftSteps,
                         @RequestParam(required = false) String priceSource,
                         @RequestParam(required = false) String sellVenue) {
        Flipper flipper = flipper(type);
        String order = sort != null ? sort : flipper.type().defaultSort().param();
        if (hotm != null && (hotm < 0 || hotm > 10)) {
            throw bad("hotm must be between 0 and 10");
        }
        checkCraftSteps(craftSteps);
        FlipQuery q = query(budget, nextSessionIn, order, minVolume, includeLocked, includeIncomplete, limit, offset);
        FlipList list = flipper.list(new FlipQuery(q.budget(), q.nextSessionIn(), q.sort(), q.minVolume(),
                q.includeLocked(), q.includeIncomplete(), q.limit(), q.offset(), alertTypes(hideAlerts), search, hotm,
                null, craftSteps, priceSource(priceSource), venue(sellVenue)));
        return new ListView(list.total(), list.generatedAt(), list.settings(), list.items(),
                extras.rows(flipper.type(), list.items()));
    }

    /** Comma-separated or repeated alert type names; unknown names are rejected. */
    static Set<AlertType> alertTypes(List<String> values) {
        Set<AlertType> types = EnumSet.noneOf(AlertType.class);
        if (values == null) {
            return types;
        }
        for (String value : values) {
            for (String name : value.split(",")) {
                if (name.isBlank()) {
                    continue;
                }
                try {
                    types.add(AlertType.valueOf(name.strip().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw bad("Unknown alert type " + name.strip());
                }
            }
        }
        return types;
    }

    @GetMapping("/{type}/{id}")
    public DetailView detail(@PathVariable String type, @PathVariable String id,
                             @RequestParam(required = false) String nextSessionIn,
                             @RequestParam(required = false) Integer craftSteps,
                             @RequestParam(required = false) List<String> hideAlerts,
                             @RequestParam(required = false) String priceSource) {
        checkCraftSteps(craftSteps);
        FlipQuery query = query(null, nextSessionIn, "profitPerSession", null, true, true, 1, 0)
                .withCraftSteps(craftSteps).withHideAlerts(alertTypes(hideAlerts))
                .withPriceSource(priceSource(priceSource));
        Flipper flipper = flipper(type);
        OpportunityDetail d = flipper.detail(id, query)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No " + type + " flip " + id));
        return new DetailView(d.summary(), d.sheet(), d.unlock(), d.guide(), d.names(),
                extras.recipes(flipper.type(), d), extras.rarities(d.names().keySet()),
                extras.rows(flipper.type(), List.of(d.summary())).get(d.summary().id()));
    }

    /** {@code ORDER} or {@code INSTANT} (any case); null when unset. */
    static PriceSource priceSource(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return PriceSource.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad("priceSource must be ORDER or INSTANT");
        }
    }

    /** {@code BAZAAR}, {@code AUCTION} or {@code NPC} (any case); null when unset. */
    static Venue venue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Venue.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad("sellVenue must be BAZAAR, AUCTION or NPC");
        }
    }

    private static void checkCraftSteps(Integer craftSteps) {
        if (craftSteps != null && (craftSteps < 1 || craftSteps > FlipQuery.MAX_CRAFT_STEPS)) {
            throw bad("craftSteps must be between 1 and " + FlipQuery.MAX_CRAFT_STEPS);
        }
    }

    private Flipper flipper(String type) {
        Flipper f = FlipType.fromPath(type).map(flippers::get).orElse(null);
        if (f == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No flipper " + type);
        }
        return f;
    }

    static FlipQuery query(Double budget, String nextSessionIn, String sort, Double minVolume, boolean includeLocked,
                           boolean includeIncomplete, int limit, int offset) {
        FlipSort order = FlipSort.fromParam(sort).orElseThrow(() -> bad("Unknown sort " + sort));
        if (limit < 1 || limit > FlipQuery.MAX_LIMIT) {
            throw bad("limit must be between 1 and " + FlipQuery.MAX_LIMIT);
        }
        if (offset < 0) {
            throw bad("offset must not be negative");
        }
        if (budget != null && budget < 0) {
            throw bad("budget must not be negative");
        }
        return new FlipQuery(budget, duration(nextSessionIn), order, minVolume, includeLocked, includeIncomplete, limit,
                offset);
    }

    /** Accepts ISO-8601 ({@code PT8H}) and the config style ({@code 8h}). */
    static Duration duration(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Duration d;
        try {
            d = DurationStyle.detectAndParse(value.trim());
        } catch (IllegalArgumentException e) {
            throw bad("Invalid duration " + value);
        }
        if (d.isNegative() || d.isZero()) {
            throw bad("nextSessionIn must be positive");
        }
        return d;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
