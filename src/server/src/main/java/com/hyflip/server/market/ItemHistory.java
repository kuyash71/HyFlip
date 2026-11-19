package com.hyflip.server.market;

import com.hyflip.server.engine.Market;
import com.hyflip.server.flipper.RecipeBooks;
import com.hyflip.server.storage.HistoryRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Current prices and bucketed price history of one item, for the item view and its charts. */
@Service
public class ItemHistory {

    /** Charts get at most about this many points. */
    static final int MAX_POINTS = 400;

    private final HistoryRepository history;
    private final MarketService markets;
    private final RecipeBooks books;
    private final Clock clock;

    @Autowired
    public ItemHistory(HistoryRepository history, MarketService markets, RecipeBooks books) {
        this(history, markets, books, Clock.systemUTC());
    }

    ItemHistory(HistoryRepository history, MarketService markets, RecipeBooks books, Clock clock) {
        this.history = history;
        this.markets = markets;
        this.books = books;
        this.clock = clock;
    }

    /**
     * @param bazaar      current Bazaar state (live best orders when fresh), null if not a product
     * @param auction     current Auction House values, null if unknown
     * @param npcSellPrice what an NPC pays, null if NPCs do not buy it
     * @param average7d   7-day Bazaar averages, null until computed
     */
    public record Info(String id, String name, Market.BazaarQuote bazaar, Market.AuctionQuote auction,
                       Double npcSellPrice, Market.BazaarAverage average7d) {
    }

    /** Median unit price of clean sales in a bucket, and how many sales it rests on. */
    public record SalePoint(long t, double median, int sales) {
    }

    public record History(String id, String name, long from, long to, long bucketSeconds,
                          List<HistoryRepository.BazaarPoint> bazaar, List<SalePoint> sales,
                          List<HistoryRepository.BinPoint> lowestBin) {
    }

    public Info info(String id) {
        Market m = markets.now();
        var npc = m.npcSellPrice(id);
        return new Info(id, books.current().name(id), m.bazaar(id).orElse(null), m.auction(id).orElse(null),
                npc.isPresent() ? npc.getAsDouble() : null, m.bazaarAverage(id).orElse(null));
    }

    /**
     * @param bucket null picks one that gives at most {@link #MAX_POINTS} points (at least a minute)
     */
    public History history(String id, Long from, Long to, Duration bucket) {
        long end = to != null ? to : clock.millis();
        long start = from != null ? from : end - Duration.ofDays(7).toMillis();
        if (start > end) {
            throw new IllegalArgumentException("from must not be after to");
        }
        long b = bucket != null ? Math.max(60_000, bucket.toMillis())
                : Math.max(60_000, roundToMinute((end - start) / MAX_POINTS));
        List<SalePoint> sales = new ArrayList<>();
        Map<Long, List<HistoryRepository.SaleRow>> byBucket = new LinkedHashMap<>();
        history.sales(id, start, end, b).forEach(r -> byBucket.computeIfAbsent(r.t(), k -> new ArrayList<>()).add(r));
        byBucket.forEach((t, rows) -> sales.add(new SalePoint(t, weightedMedian(rows),
                rows.stream().mapToInt(HistoryRepository.SaleRow::sales).sum())));
        return new History(id, books.current().name(id), start, end, b / 1000, history.bazaar(id, start, end, b),
                sales, history.lowestBin(id, start, end, b));
    }

    private static long roundToMinute(long ms) {
        return (ms / 60_000) * 60_000;
    }

    /** Median of unit prices, each row counting as many sales as it summarises. */
    static double weightedMedian(List<HistoryRepository.SaleRow> rows) {
        List<HistoryRepository.SaleRow> sorted = new ArrayList<>(rows);
        sorted.sort(java.util.Comparator.comparingDouble(HistoryRepository.SaleRow::unitPrice));
        int total = sorted.stream().mapToInt(HistoryRepository.SaleRow::sales).sum();
        int seen = 0;
        for (HistoryRepository.SaleRow r : sorted) {
            seen += r.sales();
            if (seen * 2 >= total) {
                return r.unitPrice();
            }
        }
        return sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1).unitPrice();
    }
}
