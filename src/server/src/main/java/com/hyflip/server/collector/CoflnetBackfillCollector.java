package com.hyflip.server.collector;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.mapping.ItemCondition;
import com.hyflip.server.mapping.ItemRegistry;
import com.hyflip.server.mapping.ItemSource;
import com.hyflip.server.source.coflnet.CoflnetApi;
import com.hyflip.server.source.coflnet.CoflnetException;
import com.hyflip.server.source.coflnet.SoldAuction;
import com.hyflip.server.storage.AuctionRepository;
import com.hyflip.server.storage.AuctionRepository.Sale;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fills Auction House sale history from Coflnet: Hypixel's {@code auctions_ended} only covers the
 * last minute, so a fresh install (or one that was closed for a while) has too few sales to price
 * crafted outputs. Each run fetches the recent sales of a few tracked items, never-fetched items
 * first, and refetches an item after {@code coflnet.refetch-after}. Requests are spaced by
 * {@code coflnet.request-interval}; a 429 ends the run.
 *
 * <p>Pets, enchanted books and runes are skipped: Coflnet names them differently from HyFlip's
 * canonical IDs.
 */
@Component
public class CoflnetBackfillCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(CoflnetBackfillCollector.class);
    /** Plain SkyBlock IDs: the same in Coflnet and HyFlip. */
    private static final Pattern PLAIN_ID = Pattern.compile("[A-Z0-9_]+");
    /** More failed requests than this in a run where nothing was fetched fail the run. */
    static final int MAX_ERRORS = 3;

    private final CoflnetApi coflnet;
    private final AuctionsCollector auctionsCollector;
    private final ItemRegistry registry;
    private final AuctionRepository auctions;
    private final HyFlipProperties.Coflnet config;
    private final Clock clock;
    private final Sleeper sleeper;

    /** Pauses between requests; replaced in tests. */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    @Autowired
    public CoflnetBackfillCollector(CoflnetApi coflnet, AuctionsCollector auctionsCollector, ItemRegistry registry,
                                    AuctionRepository auctions, HyFlipProperties properties) {
        this(coflnet, auctionsCollector, registry, auctions, properties.coflnet(), Clock.systemUTC(),
                d -> Thread.sleep(d.toMillis()));
    }

    CoflnetBackfillCollector(CoflnetApi coflnet, AuctionsCollector auctionsCollector, ItemRegistry registry,
                             AuctionRepository auctions, HyFlipProperties.Coflnet config, Clock clock,
                             Sleeper sleeper) {
        this.coflnet = coflnet;
        this.auctionsCollector = auctionsCollector;
        this.registry = registry;
        this.auctions = auctions;
        this.config = config;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public String name() {
        return "COFLNET";
    }

    @Override
    public Duration interval() {
        return config.interval();
    }

    private record Candidate(String tag, long itemId, long lastFetched) {
    }

    @Override
    public int collect() throws InterruptedException {
        if (!config.enabled()) {
            return 0;
        }
        long now = clock.millis();
        List<Candidate> due = due(now);
        int rows = 0;
        int errors = 0;
        int fetched = 0;
        CoflnetException last = null;
        for (int i = 0; i < due.size(); i++) {
            Candidate c = due.get(i);
            if (i > 0) {
                sleeper.sleep(config.requestInterval());
            }
            List<SoldAuction> sold;
            try {
                sold = coflnet.sold(c.tag());
            } catch (CoflnetException e) {
                if (e.rateLimited()) {
                    log.info("Coflnet rate limit reached after {} items; continuing next run", i);
                    if (i == 0) {
                        throw e;
                    }
                    break;
                }
                if (e.status() >= 400 && e.status() < 500) {
                    // Coflnet rejects this tag: record the attempt, so it waits a day instead of blocking the queue
                    // (never-fetched items go first). Network and server errors are retried next run.
                    auctions.markBackfilled(c.itemId(), now, 0, null);
                }
                log.debug("Coflnet backfill of {} failed: {}", c.tag(), e.getMessage());
                last = e;
                if (++errors > MAX_ERRORS && fetched == 0) {
                    // Nothing works (offline, Coflnet down): fail the run so the scheduler backs off.
                    throw e;
                }
                continue;
            }
            fetched++;
            List<Sale> sales = sales(sold, c);
            rows += auctions.insertSales(sales);
            Long oldest = sales.stream().map(Sale::endedAt).min(Long::compare).orElse(null);
            auctions.markBackfilled(c.itemId(), now, sold.size(), oldest);
        }
        if (errors > 0) {
            log.info("Coflnet backfill: {} items fetched, {} skipped for a day ({})", fetched, errors,
                    last == null ? "" : last.getMessage());
        }
        return rows;
    }

    /** Tracked plain items never fetched or fetched longer ago than the refetch interval, oldest first. */
    private List<Candidate> due(long now) {
        Map<Long, AuctionRepository.Backfill> fetched = auctions.backfills();
        List<Candidate> due = new ArrayList<>();
        for (String tag : auctionsCollector.trackedItems()) {
            if (!PLAIN_ID.matcher(tag).matches()) {
                continue;
            }
            long itemId = registry.resolve(ItemSource.AUCTION, tag, tag);
            AuctionRepository.Backfill last = fetched.get(itemId);
            long lastAt = last == null ? 0L : last.fetchedAt();
            if (now - lastAt > refetchAfter(last, config.refetchAfter()).toMillis()) {
                due.add(new Candidate(tag, itemId, lastAt));
            }
        }
        due.sort(Comparator.comparingLong(Candidate::lastFetched).thenComparing(Candidate::tag));
        return due.subList(0, Math.min(config.itemsPerRun(), due.size()));
    }

    /** Shortest wait before a busy item is fetched again. */
    static final Duration MIN_REFETCH = Duration.ofMinutes(30);

    /**
     * When to fetch an item again: after {@code normal}, or for an item whose last fetch was a full page, before
     * that page's span has passed (80% of it), so the next page overlaps it and no sales are missed between.
     */
    static Duration refetchAfter(AuctionRepository.Backfill last, Duration normal) {
        if (last == null || last.sales() < CoflnetApi.PAGE_SIZE || last.oldestAt() == null) {
            return normal;
        }
        Duration span = Duration.ofMillis(Math.max(0, last.fetchedAt() - last.oldestAt()));
        Duration soon = span.multipliedBy(4).dividedBy(5);
        return soon.compareTo(normal) >= 0 ? normal : soon.compareTo(MIN_REFETCH) < 0 ? MIN_REFETCH : soon;
    }

    static List<Sale> sales(List<SoldAuction> sold, Candidate c) {
        List<Sale> out = new ArrayList<>(sold.size());
        for (SoldAuction a : sold) {
            if (a.uuid() == null || a.highestBidAmount() <= 0 || !c.tag().equals(a.tag())) {
                continue;
            }
            long endedAt;
            try {
                endedAt = LocalDateTime.parse(a.end()).toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (DateTimeParseException | NullPointerException e) {
                continue;
            }
            boolean clean = ItemCondition.isCleanFlattened(a.tag(), a.enchantments(),
                    a.flattenedNbt() == null ? Map.of() : a.flattenedNbt());
            out.add(new Sale(a.uuid().replace("-", ""), endedAt, c.itemId(), a.highestBidAmount(), a.bin(),
                    Math.max(1, a.count()), "COFLNET", clean));
        }
        return out;
    }
}
