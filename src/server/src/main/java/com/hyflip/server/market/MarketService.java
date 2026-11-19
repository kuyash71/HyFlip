package com.hyflip.server.market;

import com.hyflip.server.engine.Market;
import com.hyflip.server.engine.Market.BazaarAverage;
import com.hyflip.server.source.coflnet.CoflnetApi;
import com.hyflip.server.storage.PriceRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Creates {@link Market} views over stored snapshots. */
@Service
public class MarketService {

    /** Ended auctions in this window give the median sale price and sales per day. */
    static final Duration SALES_WINDOW = Duration.ofDays(7);
    /** Sales per day are not extrapolated from less history than this. */
    static final Duration MIN_COVERAGE = Duration.ofHours(1);

    /** A live order book older than this is ignored (the collector polls every minute). */
    static final Duration LIVE_BOOK_MAX_AGE = Duration.ofMinutes(30);
    /**
     * Live views reuse an item's 7-day sales this long: they change only when the ended-auctions
     * collector runs (every minute), and a flip list reads the sales of over a thousand items.
     */
    static final Duration SALES_REUSE = Duration.ofSeconds(60);

    /** 7-day averages are recomputed in the background at most this often. */
    static final Duration AVERAGES_MAX_AGE = Duration.ofMinutes(10);

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MarketService.class);

    private final PriceRepository prices;
    private final Map<String, CachedSales> liveSales = new ConcurrentHashMap<>();

    private record CachedSales(long at, List<PriceRepository.Sale> sales) {
    }
    private final BazaarBook book;
    private final Clock clock;
    // computedAt 0 = never: Long.MIN_VALUE would overflow "now - computedAt" and never refresh.
    private volatile Averages averages = new Averages(Map.of(), 0);
    /** Hour averages of finished hours, by hour: a refresh reads only the hours since the last one. */
    private final java.util.TreeMap<Long, List<PriceRepository.HourAverage>> hours = new java.util.TreeMap<>();
    private long hoursSince;
    /** Finished hours are re-read from scratch this often, in case history was imported or backfilled. */
    static final Duration HOURS_REBUILD = Duration.ofHours(6);
    private static final long HOUR_MS = 3_600_000L;
    private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();

    private record Averages(Map<String, BazaarAverage> byItem, long computedAt) {
    }

    @Autowired
    public MarketService(PriceRepository prices, BazaarBook book) {
        this(prices, book, Clock.systemUTC());
    }

    MarketService(PriceRepository prices, BazaarBook book, Clock clock) {
        this.prices = prices;
        this.book = book;
        this.clock = clock;
    }

    /** Recomputes the 7-day Bazaar averages now from scratch (a week of snapshots takes seconds). */
    public synchronized void refreshAverages() {
        hours.clear();
        updateAverages();
    }

    /** Forgets the kept hour averages, for example after importing history; the next use rebuilds them. */
    public synchronized void invalidateAverages() {
        hours.clear();
        averages = new Averages(averages.byItem(), 0);
    }

    /**
     * Brings the 7-day averages up to date: finished hours are kept between refreshes, so only the
     * hours since the last refresh and the current one are read. Equal to {@link
     * PriceRepository#bazaarAverages} over whole hours: each hour of the window counts once.
     */
    synchronized void updateAverages() {
        long started = System.nanoTime();
        long now = clock.millis();
        long current = Math.floorDiv(now, HOUR_MS);
        long first = Math.floorDiv(now - SALES_WINDOW.toMillis(), HOUR_MS) + 1;
        if (now - hoursSince > HOURS_REBUILD.toMillis()) {
            hours.clear();
            hoursSince = now;
        }
        hours.headMap(first).clear();
        long from = hours.isEmpty() ? first : Math.max(first, hours.lastKey() + 1);
        if (from < current) {
            Map<Long, List<PriceRepository.HourAverage>> read = new java.util.HashMap<>();
            for (PriceRepository.HourAverage h : prices.bazaarHourAverages(from * HOUR_MS, current * HOUR_MS - 1)) {
                read.computeIfAbsent(h.hour(), k -> new java.util.ArrayList<>()).add(h);
            }
            // Hours without any row stay absent and are read again next time (cheap: nothing there).
            read.forEach((hour, rows) -> hours.put(hour, List.copyOf(rows)));
        }
        List<PriceRepository.HourAverage> open = prices.bazaarHourAverages(current * HOUR_MS, now);

        Map<String, double[]> sums = new java.util.HashMap<>(); // buy sum, buy hours, sell sum, sell hours, first hour
        java.util.function.Consumer<PriceRepository.HourAverage> add = h -> {
            double[] s = sums.computeIfAbsent(h.itemId(), k -> new double[] {0, 0, 0, 0, Double.MAX_VALUE});
            if (h.buyPrice() != null) {
                s[0] += h.buyPrice();
                s[1]++;
            }
            if (h.sellPrice() != null) {
                s[2] += h.sellPrice();
                s[3]++;
            }
            s[4] = Math.min(s[4], h.hour());
        };
        hours.values().forEach(rows -> rows.forEach(add));
        open.forEach(add);
        // Hour-to-hour log changes of the insta-sell price (hours in order): last price, n, sum, sum of squares.
        Map<String, double[]> swings = new java.util.HashMap<>();
        java.util.function.Consumer<PriceRepository.HourAverage> swing = h -> {
            if (h.sellPrice() == null || h.sellPrice() <= 0) {
                return;
            }
            double[] w = swings.computeIfAbsent(h.itemId(), k -> new double[] {0, 0, 0, 0});
            if (w[0] > 0) {
                double r = Math.log(h.sellPrice() / w[0]);
                w[1]++;
                w[2] += r;
                w[3] += r * r;
            }
            w[0] = h.sellPrice();
        };
        hours.values().forEach(rows -> rows.forEach(swing));
        open.forEach(swing);
        Map<String, BazaarAverage> byItem = new java.util.HashMap<>();
        sums.forEach((id, s) -> byItem.put(id, new BazaarAverage(s[1] > 0 ? s[0] / s[1] : 0, s[3] > 0 ? s[2] / s[3] : 0,
                Math.max(0, (now - s[4] * HOUR_MS) / 86_400_000.0), dailyVolatility(swings.get(id)))));
        averages = new Averages(Map.copyOf(byItem), now);
        LOG.debug("Bazaar 7-day averages of {} items from {} kept hours in {} ms", byItem.size(), hours.size(),
                (System.nanoTime() - started) / 1_000_000);
    }

    /** Standard deviation of hourly log changes × √24, or null with fewer than 5 changes. */
    static Double dailyVolatility(double[] w) {
        if (w == null || w[1] < 5) {
            return null;
        }
        double mean = w[2] / w[1];
        double variance = Math.max(0, w[3] / w[1] - mean * mean) * w[1] / (w[1] - 1);
        return Math.sqrt(variance) * Math.sqrt(24);
    }

    private Map<String, BazaarAverage> averages() {
        Averages a = averages;
        if ((a.computedAt() == 0 || clock.millis() - a.computedAt() > AVERAGES_MAX_AGE.toMillis())
                && refreshing.compareAndSet(false, true)) {
            Thread t = new Thread(() -> {
                try {
                    updateAverages();
                } finally {
                    refreshing.set(false);
                }
            }, "bazaar-averages");
            t.setDaemon(true);
            t.start();
        }
        return a.byItem();
    }

    /** Bazaar products that NPCs also buy. */
    public List<String> npcSellableBazaarItems() {
        return prices.npcSellableBazaarItems();
    }

    /** Every Bazaar product with a stored snapshot. */
    public List<String> bazaarItems() {
        return prices.bazaarItems();
    }

    /**
     * Latest prices. Bazaar prices are the best single orders of the latest poll ({@link BazaarBook})
     * when it is fresh, else the stored {@code quick_status} averages, which can sit far from the best
     * order.
     */
    public Market now() {
        return new View(clock.millis(), true);
    }

    /** Time of the oldest stored Bazaar snapshot; before it no market prices are known. */
    public java.util.OptionalLong historyStart() {
        return prices.firstBazaarAt().map(java.util.OptionalLong::of).orElse(java.util.OptionalLong.empty());
    }

    /** Time of the newest stored Bazaar snapshot; history after it does not exist yet. */
    public java.util.OptionalLong historyEnd() {
        return prices.lastBazaarAt().map(java.util.OptionalLong::of).orElse(java.util.OptionalLong.empty());
    }

    /** {@link #now()} for null, else {@link #at(long)}. */
    public Market view(Long at) {
        return at == null ? now() : at(at);
    }

    /**
     * Stored prices as they were at {@code at}, without the live book. The view caches lookups, so
     * create one per request or calculation, not one for the whole application.
     */
    public Market at(long at) {
        return new View(at, false);
    }

    private final class View implements Market {

        private final long at;
        private final boolean live;
        private final Map<String, Optional<BazaarQuote>> bazaar = new ConcurrentHashMap<>();
        private final Map<String, Optional<AuctionQuote>> auction = new ConcurrentHashMap<>();
        // Loaded in one statement each on first use: a flip list touches thousands of items.
        private volatile Map<String, BazaarQuote> storedBazaar;
        private volatile Map<String, PriceRepository.LowestBin> storedBins;
        private volatile Map<String, Double> npcPrices;
        private volatile Double coverageDays;
        private volatile Map<String, Long> cutOff;
        private volatile Long lastScan;

        View(long at, boolean live) {
            this.at = at;
            this.live = live;
        }

        /** Days of sale history in the window; a fresh install has less than the full window. */
        private double coverageDays() {
            Double days = coverageDays;
            if (days == null) {
                long from = Math.max(at - SALES_WINDOW.toMillis(), prices.firstSaleAt().orElse(at));
                long span = Math.max(MIN_COVERAGE.toMillis(), at - from);
                days = span / (double) Duration.ofDays(1).toMillis();
                coverageDays = days;
            }
            return days;
        }

        /**
         * Days the item's sales cover. Normally {@link #coverageDays()}; when Coflnet returned a full page for the
         * item, only the time since the oldest sale of that page, since earlier sales were not returned.
         */
        private double coverageDays(String itemId) {
            Map<String, Long> all = cutOff;
            if (all == null) {
                all = prices.cutOffSince(at - SALES_WINDOW.toMillis(), at, CoflnetApi.PAGE_SIZE);
                cutOff = all;
            }
            Long since = all.get(itemId);
            double days = coverageDays();
            if (since == null) {
                return days;
            }
            long span = Math.max(MIN_COVERAGE.toMillis(), at - since);
            return Math.min(days, span / (double) Duration.ofDays(1).toMillis());
        }

        private long lastScan() {
            Long scan = lastScan;
            if (scan == null) {
                scan = prices.lastBinScanAt(at).orElse(Long.MIN_VALUE);
                lastScan = scan;
            }
            return scan;
        }

        @Override
        public Optional<BazaarAverage> bazaarAverage(String itemId) {
            return live ? Optional.ofNullable(averages().get(itemId)) : Optional.empty();
        }

        @Override
        public Optional<BazaarQuote> bazaar(String itemId) {
            return bazaar.computeIfAbsent(itemId, id -> {
                Optional<BazaarQuote> stored = Optional.ofNullable(storedBazaar().get(id));
                return live ? stored.map(q -> withBook(id, q)) : stored;
            });
        }

        /** Best orders in place of the averages; volumes and order counts stay from quick_status. */
        private BazaarQuote withBook(String itemId, BazaarQuote q) {
            return book.top(itemId)
                    .filter(t -> at - t.at() <= LIVE_BOOK_MAX_AGE.toMillis())
                    .map(t -> new BazaarQuote(t.sellOffer(), t.buyOrder(), q.buyMovingWeek(), q.sellMovingWeek(),
                            Math.max(t.at(), q.takenAt()), q.buyOrders(), q.sellOrders(), true))
                    .orElse(q);
        }

        @Override
        public Optional<AuctionQuote> auction(String itemId) {
            return auction.computeIfAbsent(itemId, this::loadAuction);
        }

        @Override
        public OptionalDouble npcSellPrice(String itemId) {
            Map<String, Double> all = npcPrices;
            if (all == null) {
                synchronized (this) {
                    if (npcPrices == null) {
                        npcPrices = prices.npcSellPrices();
                    }
                    all = npcPrices;
                }
            }
            Double p = all.get(itemId);
            return p == null ? OptionalDouble.empty() : OptionalDouble.of(p);
        }

        private List<PriceRepository.Sale> sales(String itemId) {
            if (!live) {
                return prices.sales(itemId, at - SALES_WINDOW.toMillis(), at);
            }
            CachedSales cached = liveSales.get(itemId);
            if (cached != null && at >= cached.at() && at - cached.at() < SALES_REUSE.toMillis()) {
                return cached.sales();
            }
            List<PriceRepository.Sale> sales = List.copyOf(prices.sales(itemId, at - SALES_WINDOW.toMillis(), at));
            liveSales.put(itemId, new CachedSales(at, sales));
            return sales;
        }

        private Map<String, BazaarQuote> storedBazaar() {
            Map<String, BazaarQuote> all = storedBazaar;
            if (all == null) {
                synchronized (this) {
                    if (storedBazaar == null) {
                        storedBazaar = prices.bazaarAll(at);
                    }
                    all = storedBazaar;
                }
            }
            return all;
        }

        private Map<String, PriceRepository.LowestBin> storedBins() {
            Map<String, PriceRepository.LowestBin> all = storedBins;
            if (all == null) {
                synchronized (this) {
                    if (storedBins == null) {
                        storedBins = prices.lowestBinAll(at);
                    }
                    all = storedBins;
                }
            }
            return all;
        }

        private Optional<AuctionQuote> loadAuction(String itemId) {
            // Only a listing seen in the latest scan counts: an item missing from it had no clean
            // listing then (sold out, or only upgraded copies), and an older row is outdated.
            long scan = lastScan();
            Optional<PriceRepository.LowestBin> bin = Optional.ofNullable(storedBins().get(itemId))
                    .filter(b -> b.takenAt() >= scan);
            List<PriceRepository.Sale> sales = sales(itemId);
            if (bin.isEmpty() && sales.isEmpty()) {
                return Optional.empty();
            }
            double units = sales.stream().mapToInt(PriceRepository.Sale::itemCount).sum();
            return Optional.of(new AuctionQuote(
                    bin.map(PriceRepository.LowestBin::price).orElse(null),
                    bin.map(PriceRepository.LowestBin::takenAt).orElse(null),
                    median(sales.stream().mapToDouble(PriceRepository.Sale::unitPrice).sorted().toArray()),
                    units / coverageDays(itemId), sales.size(), bin.map(PriceRepository.LowestBin::listings).orElse(0)));
        }
    }

    static Double median(double[] sorted) {
        if (sorted.length == 0) {
            return null;
        }
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }
}
