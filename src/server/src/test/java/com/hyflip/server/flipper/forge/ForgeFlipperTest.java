package com.hyflip.server.flipper.forge;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyflip.server.alert.Alert;
import com.hyflip.server.alert.AlertType;
import com.hyflip.server.engine.Calibration;
import com.hyflip.server.engine.EngineFixtures;
import com.hyflip.server.engine.ForgeSpeed;
import com.hyflip.server.engine.Market;
import com.hyflip.server.engine.Market.AuctionQuote;
import com.hyflip.server.engine.Market.BazaarQuote;
import com.hyflip.server.engine.PriceSource;
import com.hyflip.server.engine.StaticMarket;
import com.hyflip.server.engine.Venue;
import com.hyflip.server.engine.CraftPlanner;
import com.hyflip.server.flipper.FlipContext;
import com.hyflip.server.flipper.FlipQuery;
import com.hyflip.server.flipper.FlipSupport;
import com.hyflip.server.flipper.RecipeBook;
import com.hyflip.server.flipper.FlipSort;
import com.hyflip.server.flipper.Opportunity;
import com.hyflip.server.flipper.OpportunityDetail;
import com.hyflip.server.profile.CollectionCatalog;
import com.hyflip.server.profile.PlayerContext;
import com.hyflip.server.profile.Unlocks;
import com.hyflip.server.source.neu.NeuData;
import com.hyflip.server.source.neu.NeuItem;
import com.hyflip.server.source.neu.NeuItem.Requirement;
import com.hyflip.server.source.neu.NeuRecipe;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ForgeFlipperTest {

    static final long NOW = 1_800_000_000_000L;
    static final long HOUR = 3600;

    static NeuRecipe forge(String output, Map<String, Double> inputs, double coins, long seconds) {
        return new NeuRecipe(NeuRecipe.Type.FORGE, output, 1, inputs, coins, seconds, output);
    }

    static final NeuData DATA = new NeuData("test", Instant.EPOCH, Map.of(
            "QUICK", new NeuItem("QUICK", "Quick Thing", List.of(new Requirement(Requirement.Kind.HOTM, "HotM", 2))),
            "SLOW", new NeuItem("SLOW", "Slow Thing", List.of(new Requirement(Requirement.Kind.HOTM, "HotM", 2))),
            "LOCKED", new NeuItem("LOCKED", "Locked Thing", List.of(new Requirement(Requirement.Kind.HOTM, "HotM", 7))),
            "PET", new NeuItem("PET", "Pet", List.of()),
            "ORE", new NeuItem("ORE", "Ore", List.of())),
            List.of(
                    forge("QUICK", Map.of("ORE", 10.0), 0, 1),            // 1 s run, small profit
                    forge("SLOW", Map.of("ORE", 10.0), 0, 6 * HOUR),      // same cost, bigger profit
                    forge("LOCKED", Map.of("ORE", 1.0), 0, HOUR),
                    forge("PET", Map.of("ORE", 1.0), 50_000, 7 * 24 * HOUR),
                    forge("NOPRICE", Map.of("MYSTERY", 1.0), 0, HOUR)),
            Map.of());

    static final Market MARKET = new StaticMarket(Map.of(
            "ORE", new BazaarQuote(101, 100, 70_000, 70_000, NOW),
            "QUICK", new BazaarQuote(1_500, 1_400, 70_000, 70_000, NOW),
            "SLOW", new BazaarQuote(3_000, 2_900, 70_000, 70_000, NOW - Duration.ofHours(2).toMillis()),
            "LOCKED", new BazaarQuote(100_000, 90_000, 70_000, 70_000, NOW)),
            Map.of("PET", new AuctionQuote(2_000_000.0, NOW, 1_900_000.0, 1)), Map.of());

    static PlayerContext context(Integer hotm) {
        return new PlayerContext(hotm, 2, true, Map.of(), 0.2, false, "UNKNOWN", null, Duration.ofHours(8),
                PriceSource.ORDER, EngineFixtures.FEES, "CONFIG");
    }

    static final ForgeSpeed SPEED = new ForgeSpeed(0.2, false, Calibration.Fit.NONE);

    static FlipContext inputs(Integer hotm) {
        return new FlipContext(RecipeBook.of(DATA), MARKET, context(hotm), CollectionCatalog.EMPTY,
                Duration.ofHours(8), NOW, CraftPlanner.DEFAULT_DEPTH);
    }

    static OpportunityDetail eval(String id, Integer hotm) {
        return ForgeFlipper.evaluate(ForgeCalibrationService.forgeRecipe(DATA, id).orElseThrow(), inputs(hotm), SPEED);
    }

    @Test
    void profitPerSessionDoesNotInflateVeryShortRecipes() {
        List<Opportunity> ranked = DATA.recipes().stream().map(r -> ForgeFlipper.evaluate(r, inputs(5), SPEED).summary())
                .filter(o -> List.of("QUICK", "SLOW", "NOPRICE").contains(o.id()))
                .sorted(FlipSort.PROFIT_PER_SESSION.order()).toList();
        assertThat(ranked.get(0).id()).isEqualTo("SLOW");
        assertThat(ranked.get(ranked.size() - 1).id()).as("missing prices rank last").isEqualTo("NOPRICE");

        List<Opportunity> perHour = ranked.stream().sorted(FlipSort.PROFIT_PER_HOUR.order()).toList();
        assertThat(perHour.get(0).id()).isEqualTo("QUICK");
    }

    @Test
    void appliesForgeSpeedToDurations() {
        Opportunity slow = eval("SLOW", 5).summary();
        assertThat(slow.baseDurationSeconds()).isEqualTo(6 * HOUR);
        assertThat(slow.durationSeconds()).isEqualTo(Math.round(6 * HOUR * 0.8));
        Opportunity pet = eval("PET", 5).summary();
        assertThat(pet.sessions()).isEqualTo((long) Math.ceil(7 * 24 * 0.8 / 8));
        assertThat(pet.cost()).isEqualTo(50_000 + 100);
    }

    @Test
    void filtersLockedRecipesBudgetAndVolume() {
        FlipQuery defaults = FlipQuery.defaults();
        assertThat(eval("LOCKED", 5).summary().unlock()).isEqualTo(Unlocks.State.LOCKED);
        assertThat(FlipSupport.accept(eval("LOCKED", 5).summary(), defaults, null)).isFalse();
        assertThat(FlipSupport.accept(eval("LOCKED", 7).summary(), defaults, null)).isTrue();
        FlipQuery withLocked = new FlipQuery(null, null, FlipSort.NET_PROFIT, null, true, false, 50, 0);
        assertThat(FlipSupport.accept(eval("LOCKED", 5).summary(), withLocked, null)).isTrue();

        assertThat(FlipSupport.accept(eval("SLOW", 5).summary(), defaults, 999.0)).isFalse();
        assertThat(FlipSupport.accept(eval("SLOW", 5).summary(), defaults, 1000.0)).isTrue();
        FlipQuery busy = new FlipQuery(null, null, FlipSort.NET_PROFIT, 100.0, false, false, 50, 0);
        assertThat(FlipSupport.accept(eval("PET", 5).summary(), busy, null)).isFalse();
    }

    @Test
    void unknownHotmIsFlaggedNotHidden() {
        Opportunity o = eval("SLOW", null).summary();
        assertThat(o.unlock()).isEqualTo(Unlocks.State.UNKNOWN);
        assertThat(FlipSupport.accept(o, FlipQuery.defaults(), null)).isTrue();
        assertThat(types(o)).contains(AlertType.UNLOCK_UNKNOWN);
    }

    @Test
    void alertsOnAuctionOutputsLowVolumeStaleAndMissingPrices() {
        Opportunity pet = eval("PET", 5).summary();
        assertThat(pet.sellVenue()).isEqualTo(Venue.AUCTION);
        assertThat(pet.buyVenues()).containsExactly(Venue.BAZAAR);
        assertThat(types(pet)).contains(AlertType.LOW_VOLUME).doesNotContain(AlertType.FEW_SALES);
        assertThat(types(eval("SLOW", 5).summary())).contains(AlertType.STALE_PRICE);
        Opportunity missing = eval("NOPRICE", 5).summary();
        assertThat(missing.complete()).isFalse();
        assertThat(FlipSupport.accept(missing, FlipQuery.defaults(), null)).as("incomplete hidden by default").isFalse();
        assertThat(missing.alerts()).filteredOn(a -> a.type() == AlertType.MISSING_PRICE)
                .extracting(Alert::itemId).containsExactly("MYSTERY", "NOPRICE");
    }

    @Test
    void flagsAuctionValuesFromFewSalesAndWideBazaarSpreads() {
        Market thin = new StaticMarket(Map.of(
                // Insta-buy far above the buy order: buying instantly would wipe out the profit.
                "ORE", new BazaarQuote(100_000_000, 100, 70_000, 70, NOW)),
                Map.of("PET", new AuctionQuote(5_000_000.0, NOW, null, 0, 0, 1)), Map.of());
        FlipContext ctx = new FlipContext(RecipeBook.of(DATA), thin, context(5), CollectionCatalog.EMPTY,
                Duration.ofHours(8), NOW, CraftPlanner.DEFAULT_DEPTH);
        Opportunity pet = ForgeFlipper.evaluate(ForgeCalibrationService.forgeRecipe(DATA, "PET").orElseThrow(), ctx,
                SPEED).summary();
        assertThat(pet.alerts()).filteredOn(a -> a.type() == AlertType.FEW_SALES).extracting(Alert::message)
                .containsExactly("Pet: no clean sales in the last 7 days; the value is the lowest of 1 listing");
        assertThat(pet.alerts()).filteredOn(a -> a.type() == AlertType.WIDE_SPREAD).extracting(Alert::message)
                .containsExactly("Ore: a buy order at 100 fills slowly (about 10 a day); buying now costs 100M each, 100M more,"
                        + " more than all of the profit");

        FlipQuery hide = new FlipQuery(null, null, FlipSort.NET_PROFIT, null, false, false, 50, 0,
                java.util.Set.of(AlertType.FEW_SALES), null, null);
        assertThat(FlipSupport.accept(pet, hide, null)).isFalse();
        assertThat(FlipSupport.accept(pet, FlipQuery.defaults(), null)).isTrue();

        // A wide spread that costs little next to the profit is not worth a warning.
        Market cheap = new StaticMarket(Map.of("ORE", new BazaarQuote(1_000, 100, 70_000, 70, NOW)),
                Map.of("PET", new AuctionQuote(5_000_000.0, NOW, null, 0, 0, 1)), Map.of());
        FlipContext cheapCtx = new FlipContext(RecipeBook.of(DATA), cheap, context(5), CollectionCatalog.EMPTY,
                Duration.ofHours(8), NOW, CraftPlanner.DEFAULT_DEPTH);
        assertThat(types(ForgeFlipper.evaluate(ForgeCalibrationService.forgeRecipe(DATA, "PET").orElseThrow(),
                cheapCtx, SPEED).summary())).doesNotContain(AlertType.WIDE_SPREAD);
    }

    @Test
    void flagsPricesFarAboveTheirWeeklyAverage() {
        // ORE is bought by buy order at 100 (insta-sell side); its 7-day insta-sell average was 40.
        Market spiked = new StaticMarket(Map.of("ORE", new BazaarQuote(101, 100, 70_000, 70_000, NOW)),
                Map.of("PET", new AuctionQuote(5_000_000.0, NOW, null, 0, 0, 1)), Map.of());
        Market withAverage = new Market() {
            public java.util.Optional<BazaarQuote> bazaar(String id) { return spiked.bazaar(id); }
            public java.util.Optional<AuctionQuote> auction(String id) { return spiked.auction(id); }
            public java.util.OptionalDouble npcSellPrice(String id) { return spiked.npcSellPrice(id); }
            public java.util.Optional<BazaarAverage> bazaarAverage(String id) {
                return id.equals("ORE") ? java.util.Optional.of(new BazaarAverage(45, 40, 3)) : java.util.Optional.empty();
            }
        };
        FlipContext ctx = new FlipContext(RecipeBook.of(DATA), withAverage, context(5), CollectionCatalog.EMPTY,
                Duration.ofHours(8), NOW, CraftPlanner.DEFAULT_DEPTH);
        Opportunity o = ForgeFlipper.evaluate(ForgeCalibrationService.forgeRecipe(DATA, "PET").orElseThrow(), ctx,
                SPEED).summary();
        assertThat(o.alerts()).filteredOn(a -> a.type() == AlertType.MANIPULATION).singleElement()
                .satisfies(a -> assertThat(a.message())
                        .isEqualTo("Ore: price is 100, 2.5× its 7-day average of 40 (3.0 days of history)"));

        // Under the ratio, or with less than a day of history: no alert.
        Market calm = new Market() {
            public java.util.Optional<BazaarQuote> bazaar(String id) { return spiked.bazaar(id); }
            public java.util.Optional<AuctionQuote> auction(String id) { return spiked.auction(id); }
            public java.util.OptionalDouble npcSellPrice(String id) { return spiked.npcSellPrice(id); }
            public java.util.Optional<BazaarAverage> bazaarAverage(String id) {
                return java.util.Optional.of(new BazaarAverage(45, 40, 0.5));
            }
        };
        FlipContext calmCtx = new FlipContext(RecipeBook.of(DATA), calm, context(5), CollectionCatalog.EMPTY,
                Duration.ofHours(8), NOW, CraftPlanner.DEFAULT_DEPTH);
        assertThat(types(ForgeFlipper.evaluate(ForgeCalibrationService.forgeRecipe(DATA, "PET").orElseThrow(),
                calmCtx, SPEED).summary())).doesNotContain(AlertType.MANIPULATION);
    }

    @Test
    void searchMatchesNamesAndHotmCanBeOverridden() {
        FlipQuery search = new FlipQuery(null, null, FlipSort.NET_PROFIT, null, true, false, 50, 0, null, " slow ",
                null);
        assertThat(FlipSupport.accept(eval("SLOW", 5).summary(), search, null)).isTrue();
        assertThat(FlipSupport.accept(eval("QUICK", 5).summary(), search, null)).isFalse();

        FlipQuery hotm7 = new FlipQuery(null, null, FlipSort.NET_PROFIT, null, false, false, 50, 0, null, null, 7);
        assertThat(hotm7.apply(context(2)).hotmLevel()).isEqualTo(7);
        assertThat(FlipQuery.defaults().apply(context(2)).hotmLevel()).isEqualTo(2);
    }

    @Test
    void guideDescribesTheManualSteps() {
        OpportunityDetail d = eval("PET", 5);
        assertThat(d.guide().steps()).anyMatch(s -> s.startsWith("Buy 1 × Ore on the Bazaar with a buy order"));
        assertThat(d.guide().steps()).anyMatch(s -> s.contains("50k coins"));
        assertThat(d.guide().steps()).last().asString().contains("Auction House");
        assertThat(d.guide().logic()).contains("sessions");
        assertThat(d.names()).containsEntry("ORE", "Ore");
    }

    private static List<AlertType> types(Opportunity o) {
        return o.alerts().stream().map(Alert::type).sorted(Comparator.naturalOrder()).toList();
    }
}
