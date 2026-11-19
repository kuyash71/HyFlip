package com.hyflip.server.flipper.forge;

import com.hyflip.server.alert.Alert;
import com.hyflip.server.alert.AlertType;
import com.hyflip.server.engine.CostSheet;
import com.hyflip.server.engine.CraftPlanner;
import com.hyflip.server.flipper.FlipContext;
import com.hyflip.server.flipper.RecipeBooks;
import com.hyflip.server.engine.Market;
import com.hyflip.server.engine.Pricing;
import com.hyflip.server.engine.Quote;
import com.hyflip.server.market.MarketService;
import com.hyflip.server.profile.PlayerContext;
import com.hyflip.server.profile.PlayerContextService;
import com.hyflip.server.source.neu.NeuData;
import com.hyflip.server.source.neu.NeuRecipe;
import com.hyflip.server.source.neu.NeuRepository;
import com.hyflip.server.storage.ForgeJobRepository;
import com.hyflip.server.storage.ForgeJobRepository.Job;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Forge Tracker: jobs the player recorded, time left per slot, and expected vs realised value.
 *
 * <p>Jobs are recorded by hand ({@code MANUAL}) until profile linking can read forge data. A job
 * occupies its slot until it is collected. Finished jobs are settled lazily on read: their
 * realised value comes from the last prices at or before the expected end.
 */
@Service
public class ForgeTracker {

    /** Accept start times this far in the future to absorb clock differences. */
    static final long FUTURE_TOLERANCE_MS = 60_000;

    private final ForgeJobRepository jobs;
    private final NeuRepository neu;
    private final MarketService markets;
    private final PlayerContextService contexts;
    private final ForgeCalibrationService calibration;
    private final RecipeBooks books;
    private final Clock clock;

    @Autowired
    public ForgeTracker(ForgeJobRepository jobs, NeuRepository neu, MarketService markets,
                        PlayerContextService contexts, ForgeCalibrationService calibration, RecipeBooks books) {
        this(jobs, neu, markets, contexts, calibration, books, Clock.systemUTC());
    }

    ForgeTracker(ForgeJobRepository jobs, NeuRepository neu, MarketService markets, PlayerContextService contexts,
                 ForgeCalibrationService calibration, RecipeBooks books, Clock clock) {
        this.jobs = jobs;
        this.neu = neu;
        this.markets = markets;
        this.contexts = contexts;
        this.calibration = calibration;
        this.books = books;
        this.clock = clock;
    }

    /**
     * @param startedAt       when the job started; null means now, or derived from {@code remainingSeconds}
     * @param remainingSeconds time left as shown in game; if set, the end time is now + remaining
     */
    public record StartRequest(Integer slot, String recipeId, Long startedAt, Long remainingSeconds) {
    }

    /**
     * @param expectedProfit expected value − expected cost
     * @param valueChange    realised value − expected value, null until settled
     * @param soldValue      coins the player received for the output, null until entered
     * @param soldProfit     sold value − expected cost, null until a sale is entered
     */
    public record JobView(long id, int slot, String recipeId, String name, long startedAt, long expectedEndAt,
                          long remainingSeconds, boolean finished, Long baseSeconds, Double expectedCost,
                          Double expectedValue, Double expectedProfit, Double realisedValue, Long realisedAt,
                          Double valueChange, Long collectedAt, String source, Double soldValue,
                          Double soldProfit) {
    }

    public record SlotView(int slot, JobView job) {
    }

    /**
     * @param unassigned open jobs in slots above the current slot count (the config changed)
     * @param timeZone   IANA zone the player reads clock times in (settings, else the computer's)
     */
    public record Slots(int slotCount, boolean slotCountKnown, long now, List<SlotView> slots,
                        List<JobView> unassigned, List<Alert> alerts, String timeZone) {
    }

    public Slots slots() {
        settle();
        PlayerContext ctx = contexts.current();
        long now = clock.millis();
        NeuData data = data();
        Map<Integer, Job> bySlot = jobs.open().stream()
                .collect(Collectors.toMap(Job::slot, Function.identity(), (a, b) -> a.startedAt() >= b.startedAt() ? a : b));
        List<SlotView> slots = new ArrayList<>();
        List<Alert> alerts = new ArrayList<>();
        for (int slot = 1; slot <= ctx.forgeSlots(); slot++) {
            Job job = bySlot.get(slot);
            JobView view = job == null ? null : view(job, data, now);
            if (view != null && view.finished()) {
                alerts.add(new Alert(AlertType.FORGE_FINISHED, "Slot " + slot + ": " + view.name() + " is ready",
                        view.recipeId()));
            }
            slots.add(new SlotView(slot, view));
        }
        List<JobView> unassigned = bySlot.values().stream()
                .filter(j -> j.slot() > ctx.forgeSlots())
                .map(j -> view(j, data, now))
                .toList();
        return new Slots(ctx.forgeSlots(), ctx.forgeSlotsKnown(), now, List.copyOf(slots), unassigned,
                List.copyOf(alerts), contexts.zone().getId());
    }

    /**
     * Records a job the player started.
     *
     * @throws ForgeException INVALID for a bad slot or time, NOT_FOUND for an unknown recipe,
     *                        CONFLICT if the slot already holds an uncollected job
     */
    public JobView start(StartRequest request) {
        PlayerContext ctx = contexts.current();
        long now = clock.millis();
        if (request.slot() == null || request.slot() < 1 || request.slot() > ctx.forgeSlots()) {
            throw ForgeException.invalid("slot must be between 1 and " + ctx.forgeSlots());
        }
        if (request.recipeId() == null || request.recipeId().isBlank()) {
            throw ForgeException.invalid("recipeId is required");
        }
        NeuData data = data();
        NeuRecipe recipe = ForgeCalibrationService.forgeRecipe(data, request.recipeId())
                .orElseThrow(() -> ForgeException.notFound("No forge recipe for " + request.recipeId()));
        if (jobs.open().stream().anyMatch(j -> j.slot() == request.slot())) {
            throw ForgeException.conflict("Slot " + request.slot() + " is busy; collect or delete its job first");
        }
        long realMs = realMs(recipe, ctx);

        long startedAt;
        long endsAt;
        if (request.remainingSeconds() != null) {
            if (request.remainingSeconds() < 0) {
                throw ForgeException.invalid("remainingSeconds must not be negative");
            }
            endsAt = now + request.remainingSeconds() * 1000;
            startedAt = request.startedAt() != null ? request.startedAt() : Math.min(now, endsAt - realMs);
        } else {
            startedAt = request.startedAt() != null ? request.startedAt() : now;
            endsAt = startedAt + realMs;
        }
        if (startedAt > now + FUTURE_TOLERANCE_MS) {
            throw ForgeException.invalid("startedAt is in the future");
        }

        long id;
        try {
            id = insert(request.slot(), recipe, startedAt, endsAt, ctx, now, "MANUAL");
        } catch (DataIntegrityViolationException e) {
            throw ForgeException.conflict("Slot " + request.slot() + " is busy; collect or delete its job first");
        }
        return view(jobs.find(id).orElseThrow(), data, now);
    }

    private long realMs(NeuRecipe recipe, PlayerContext ctx) {
        long base = recipe.durationSeconds() == null ? 0 : recipe.durationSeconds();
        return calibration.speed(ctx).realSeconds(base) * 1000;
    }

    private long insert(int slot, NeuRecipe recipe, long startedAt, long endsAt, PlayerContext ctx, long now,
                        String source) {
        // Same pricing as the Forge Flipper: inputs bought or crafted, whichever is cheaper.
        CostSheet sheet = new FlipContext(books.current(), markets.now(), ctx, contexts.collections(),
                ctx.nextSessionIn(), now, CraftPlanner.DEFAULT_DEPTH).sheet(recipe);
        boolean inputsPriced = sheet.output() == null ? sheet.missingPrices().size() == 1 : sheet.complete();
        Double cost = inputsPriced ? sheet.cost() : null;
        Double value = sheet.output() == null ? null : sheet.output().net();
        long base = recipe.durationSeconds() == null ? 0 : recipe.durationSeconds();
        return jobs.insert(slot, recipe.output(), startedAt, endsAt, base, cost, value, source);
    }

    /**
     * What a profile sync changed in the tracker.
     *
     * @param added          jobs recorded from the profile
     * @param moved          jobs recorded by hand, moved to the slot and start the profile shows
     * @param collected      tracked jobs marked collected because the profile no longer has them
     * @param unknownRecipes item IDs in the forge without a NEU forge recipe
     */
    public record ProfileSync(int added, int moved, int collected, List<String> unknownRecipes) {
        public static final ProfileSync NONE = new ProfileSync(0, 0, 0, List.of());
    }

    /** A job the profile shows within this of a tracked job's start is the same job. */
    static final long SAME_JOB_TOLERANCE_MS = 5 * 60_000;

    /**
     * Brings the tracker in line with the forge as the profile API shows it; the profile wins.
     *
     * <ol>
     * <li>A tracked job in the same slot with the same item and start is kept.</li>
     * <li>Otherwise a tracked job with the same item (in any slot, any start) is the same job
     *     recorded by hand: it moves to the profile's slot and start time.</li>
     * <li>A tracked job left in a slot the profile shows busy was collected and refilled in game.
     *     One missing from the profile is collected once it has finished (the profile can lag).</li>
     * <li>Jobs the tracker does not know yet are recorded.</li>
     * </ol>
     */
    public ProfileSync syncFromProfile(List<com.hyflip.server.profile.LinkedProfile.ForgeProcess> forge,
                                       long fetchedAt) {
        PlayerContext ctx = contexts.current();
        long now = clock.millis();
        NeuData data = data();
        List<Job> open = new ArrayList<>(jobs.open());
        List<String> unknown = new ArrayList<>();
        List<com.hyflip.server.profile.LinkedProfile.ForgeProcess> pending = new ArrayList<>();
        java.util.Set<Integer> profileSlots = new java.util.HashSet<>();
        Map<Long, com.hyflip.server.profile.LinkedProfile.ForgeProcess> moves = new java.util.LinkedHashMap<>();

        for (var p : forge) {
            profileSlots.add(p.slot());
            Optional<Job> same = open.stream().filter(j -> j.slot() == p.slot() && j.recipeId().equals(p.itemId())
                    && Math.abs(j.startedAt() - p.startedAt()) <= SAME_JOB_TOLERANCE_MS).findFirst();
            if (same.isPresent()) {
                open.remove(same.get());
            } else {
                pending.add(p);
            }
        }
        for (var it = pending.iterator(); it.hasNext(); ) {
            var p = it.next();
            // A job that ended before this one started is an earlier run, not the same job.
            Optional<Job> sameItem = open.stream().filter(j -> j.recipeId().equals(p.itemId())
                            && j.expectedEndAt() > p.startedAt() - SAME_JOB_TOLERANCE_MS)
                    .min(java.util.Comparator.comparingLong(j -> Math.abs(j.startedAt() - p.startedAt())));
            if (sameItem.isPresent()) {
                open.remove(sameItem.get());
                moves.put(sameItem.get().id(), p);
                it.remove();
            }
        }

        int collected = 0;
        for (Job j : open) {
            boolean slotRefilled = profileSlots.contains(j.slot());
            boolean gone = j.expectedEndAt() <= now && j.startedAt() < fetchedAt;
            if (slotRefilled || gone) {
                jobs.collect(j.id(), slotRefilled ? Math.min(fetchedAt, now) : fetchedAt);
                collected++;
            }
        }

        // Two jobs can swap slots: park the moving jobs outside the slot range first.
        moves.keySet().forEach(id -> jobs.find(id).ifPresent(j ->
                jobs.move(id, (int) -id, j.startedAt(), j.expectedEndAt(), j.source())));
        int moved = 0;
        for (var m : moves.entrySet()) {
            var p = m.getValue();
            long end = ForgeCalibrationService.forgeRecipe(data, p.itemId())
                    .map(r -> p.startedAt() + realMs(r, ctx))
                    .orElse(jobs.find(m.getKey()).map(Job::expectedEndAt).orElse(p.startedAt()));
            jobs.move(m.getKey(), p.slot(), p.startedAt(), end, "PROFILE_API");
            moved++;
        }

        int added = 0;
        for (var p : pending) {
            Optional<NeuRecipe> recipe = ForgeCalibrationService.forgeRecipe(data, p.itemId());
            if (recipe.isEmpty()) {
                unknown.add(p.itemId());
                continue;
            }
            try {
                insert(p.slot(), recipe.get(), p.startedAt(), p.startedAt() + realMs(recipe.get(), ctx), ctx, now,
                        "PROFILE_API");
                added++;
            } catch (DataIntegrityViolationException e) {
                // Another open job still holds the slot; leave it for the player to sort out.
            }
        }
        if (collected > 0) {
            settle();
        }
        return new ProfileSync(added, moved, collected, List.copyOf(unknown));
    }

    /** Marks the job collected, freeing its slot. */
    public Optional<JobView> collect(long id) {
        long now = clock.millis();
        jobs.collect(id, now);
        settle();
        return jobs.find(id).map(j -> view(j, data(), now));
    }

    public boolean delete(long id) {
        return jobs.delete(id);
    }

    /** Records what the player received for the job's output; null clears it. */
    public Optional<JobView> recordSale(long id, Double value) {
        if (value != null && (value < 0 || value.isNaN() || value.isInfinite())) {
            throw ForgeException.invalid("value must be zero or more");
        }
        if (!jobs.recordSale(id, value)) {
            return Optional.empty();
        }
        return jobs.find(id).map(j -> view(j, data(), clock.millis()));
    }

    public List<JobView> history(int limit) {
        settle();
        long now = clock.millis();
        NeuData data = data();
        return jobs.recent(limit).stream().map(j -> view(j, data, now)).toList();
    }

    /** Fills the realised value of finished jobs from the prices at their end. Returns how many. */
    public int settle() {
        long now = clock.millis();
        List<Job> due = jobs.unsettled(now);
        if (due.isEmpty()) {
            return 0;
        }
        PlayerContext ctx = contexts.current();
        NeuData data = data();
        int settled = 0;
        for (Job job : due) {
            Optional<NeuRecipe> recipe = ForgeCalibrationService.forgeRecipe(data, job.recipeId());
            double count = recipe.map(NeuRecipe::outputCount).orElse(1.0);
            Market atEnd = markets.at(job.expectedEndAt());
            Optional<Quote> quote = Pricing.sell(atEnd, job.recipeId(), count, ctx.priceSource(), ctx.fees());
            if (quote.isPresent()) {
                Quote q = quote.get();
                jobs.settle(job.id(), q.net(), q.priceAt() != null ? q.priceAt() : job.expectedEndAt());
                settled++;
            }
        }
        return settled;
    }

    private NeuData data() {
        return neu.current().orElse(ForgeCalibrationService.EMPTY_NEU);
    }

    static JobView view(Job j, NeuData data, long now) {
        long remaining = Math.max(0, (j.expectedEndAt() - now + 999) / 1000);
        Double profit = j.expectedCost() != null && j.expectedValue() != null
                ? j.expectedValue() - j.expectedCost() : null;
        Double change = j.realisedValue() != null && j.expectedValue() != null
                ? j.realisedValue() - j.expectedValue() : null;
        return new JobView(j.id(), j.slot(), j.recipeId(), ForgeFlipper.name(data, j.recipeId()), j.startedAt(),
                j.expectedEndAt(), remaining, j.expectedEndAt() <= now, j.baseSeconds(), j.expectedCost(),
                j.expectedValue(), profit, j.realisedValue(), j.realisedAt(), change, j.collectedAt(), j.source(),
                j.soldValue(), j.soldValue() != null && j.expectedCost() != null
                        ? j.soldValue() - j.expectedCost() : null);
    }
}
