package com.hyflip.server.api;

import com.hyflip.server.collector.Collector;
import com.hyflip.server.collector.CollectorScheduler;
import com.hyflip.server.profile.ApiKeyStatus;
import com.hyflip.server.source.neu.NeuData;
import com.hyflip.server.source.neu.NeuRecipe;
import com.hyflip.server.source.neu.NeuRepository;
import com.hyflip.server.storage.AuctionRepository;
import com.hyflip.server.storage.BazaarRepository;
import com.hyflip.server.storage.CollectorRunRepository;
import com.hyflip.server.storage.DatabaseInfo;
import com.hyflip.server.storage.GapRepository;
import com.hyflip.server.storage.ItemRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Health of the data layer: collectors, gaps, database, NEU, unmapped IDs and the API key. */
@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    static final int GAP_LIMIT = 20;
    static final int UNMAPPED_SAMPLE = 20;

    private final CollectorScheduler scheduler;
    private final CollectorRunRepository runs;
    private final GapRepository gaps;
    private final ItemRepository items;
    private final BazaarRepository bazaar;
    private final AuctionRepository auctions;
    private final DatabaseInfo database;
    private final NeuRepository neu;
    private final ApiKeyStatus apiKey;

    public StatusController(CollectorScheduler scheduler, CollectorRunRepository runs, GapRepository gaps,
                            ItemRepository items, BazaarRepository bazaar, AuctionRepository auctions,
                            DatabaseInfo database, NeuRepository neu, ApiKeyStatus apiKey) {
        this.scheduler = scheduler;
        this.runs = runs;
        this.gaps = gaps;
        this.items = items;
        this.bazaar = bazaar;
        this.auctions = auctions;
        this.database = database;
        this.neu = neu;
        this.apiKey = apiKey;
    }

    public record Status(List<CollectorStatus> collectors, List<GapRepository.Gap> gaps, Database database,
                         Neu neu, Unmapped unmapped, ApiKeyStatus.Status apiKey) {
    }

    /**
     * @param intervalSeconds     schedule interval
     * @param lastRun             latest run (any status), null if never run
     * @param consecutiveFailures failures since the last success in this session
     */
    public record CollectorStatus(String name, long intervalSeconds, CollectorRunRepository.Run lastRun,
                                  Long lastSuccessAt, int consecutiveFailures) {
    }

    public record Database(long sizeBytes, long items, long bazaarSnapshots, long auctionSales) {
    }

    public record Neu(boolean loaded, String commit, Long loadedAt, int items, Map<String, Long> recipes) {
    }

    public record Unmapped(long count, List<ItemRepository.Unmapped> sample) {
    }

    @GetMapping
    public Status status() {
        Map<String, CollectorRunRepository.Run> latest = runs.latest().stream()
                .collect(Collectors.toMap(CollectorRunRepository.Run::collector, Function.identity()));
        Map<String, CollectorScheduler.State> states = scheduler.states();
        List<CollectorStatus> collectors = scheduler.collectors().stream()
                .map(Collector::name)
                .sorted()
                .map(name -> {
                    Collector c = scheduler.collectors().stream().filter(x -> x.name().equals(name)).findFirst()
                            .orElseThrow();
                    CollectorScheduler.State s = states.get(name);
                    return new CollectorStatus(name, c.interval().toSeconds(), latest.get(name),
                            runs.lastSuccessAt(name).orElse(null), s == null ? 0 : s.consecutiveFailures());
                })
                .toList();
        return new Status(collectors, gaps.recent(GAP_LIMIT),
                new Database(database.sizeBytes(), items.count(), bazaar.count(), auctions.salesCount()),
                neu.current().map(StatusController::neuStatus).orElse(new Neu(false, null, null, 0, Map.of())),
                new Unmapped(items.unmappedCount(), items.unmapped(UNMAPPED_SAMPLE)),
                apiKey.status());
    }

    /**
     * Runs of every collector over the last hours, oldest first, for uptime strips.
     *
     * @param hours 1 to 168, default 24
     */
    @GetMapping("/runs")
    public Map<String, List<CollectorRunRepository.Point>> runs(@RequestParam(defaultValue = "24") int hours) {
        if (hours < 1 || hours > 168) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hours must be between 1 and 168");
        }
        Map<String, List<CollectorRunRepository.Point>> out = new java.util.TreeMap<>();
        scheduler.collectors().forEach(c -> out.put(c.name(), new java.util.ArrayList<>()));
        for (CollectorRunRepository.Point p : runs.since(System.currentTimeMillis() - hours * 3_600_000L)) {
            out.computeIfAbsent(p.collector(), k -> new java.util.ArrayList<>()).add(p);
        }
        return out;
    }

    /** Runs one collector now, skipping its wait; 404 if unknown, 409 if collection is off. */
    @PostMapping("/collectors/{name}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void runNow(@PathVariable String name) {
        if (scheduler.collectors().stream().noneMatch(c -> c.name().equals(name))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No collector " + name);
        }
        if (!scheduler.runNow(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Collection is turned off in the config");
        }
    }

    private static Neu neuStatus(NeuData d) {
        Map<String, Long> recipes = new java.util.TreeMap<>();
        for (NeuRecipe.Type t : NeuRecipe.Type.values()) {
            recipes.put(t.name(), d.recipeCount(t));
        }
        return new Neu(true, d.commit(), d.loadedAt().toEpochMilli(), d.items().size(), recipes);
    }
}
