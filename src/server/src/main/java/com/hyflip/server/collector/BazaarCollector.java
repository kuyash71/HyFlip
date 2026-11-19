package com.hyflip.server.collector;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.mapping.ItemIds;
import com.hyflip.server.mapping.ItemRegistry;
import com.hyflip.server.mapping.ItemSource;
import com.hyflip.server.market.BazaarBook;
import com.hyflip.server.source.hypixel.BazaarResponse;
import com.hyflip.server.source.hypixel.HypixelApi;
import com.hyflip.server.source.neu.NeuData;
import com.hyflip.server.source.neu.NeuRepository;
import com.hyflip.server.storage.BazaarRepository;
import com.hyflip.server.storage.BazaarRepository.Snapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Stores Bazaar {@code quick_status} per product. To keep the database small, a product is stored
 * only when one of its fields changed since its last stored snapshot; the latest row of a product
 * is therefore its current state even if it is old. The best price level of each order book side is
 * kept in memory only ({@link BazaarBook}).
 */
@Component
public class BazaarCollector implements Collector {

    private final HypixelApi hypixel;
    private final ItemRegistry registry;
    private final NeuRepository neu;
    private final BazaarRepository bazaar;
    private final BazaarBook book;
    private final Duration interval;
    private final Map<Long, Snapshot> lastStored = new ConcurrentHashMap<>();
    private volatile long lastUpdated;

    public BazaarCollector(HypixelApi hypixel, ItemRegistry registry, NeuRepository neu, BazaarRepository bazaar,
                           BazaarBook book, HyFlipProperties properties) {
        this.hypixel = hypixel;
        this.registry = registry;
        this.neu = neu;
        this.bazaar = bazaar;
        this.book = book;
        this.interval = properties.collection().bazaarInterval();
    }

    @Override
    public String name() {
        return "BAZAAR";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public boolean recordsHistory() {
        return true;
    }

    @Override
    public int collect() {
        BazaarResponse response = hypixel.bazaar();
        if (response.lastUpdated() == lastUpdated) {
            return 0; // Hypixel served the same cached snapshot
        }
        Map<String, String> stocks = neu.current().map(NeuData::bazaarStocks).orElse(Map.of());
        List<Snapshot> changed = new ArrayList<>();
        Map<String, BazaarBook.Top> tops = new HashMap<>();
        for (BazaarResponse.Product product : response.products().values()) {
            BazaarResponse.QuickStatus q = product.quickStatus();
            if (q == null || product.productId() == null) {
                continue;
            }
            String canonical = ItemIds.fromBazaar(product.productId(), stocks);
            long itemId = registry.resolve(ItemSource.BAZAAR, product.productId(), canonical);
            tops.put(canonical, BazaarBook.Top.of(product.sellSummary(), product.buySummary(), response.lastUpdated()));
            Snapshot row = new Snapshot(response.lastUpdated(), itemId, q.buyPrice(), q.sellPrice(),
                    q.buyVolume(), q.sellVolume(), q.buyMovingWeek(), q.sellMovingWeek(), q.buyOrders(),
                    q.sellOrders(), "HYPIXEL");
            if (!row.sameMarket(lastStored.get(itemId))) {
                changed.add(row);
            }
        }
        book.replace(tops);
        int written = bazaar.insert(changed);
        changed.forEach(r -> lastStored.put(r.itemId(), r));
        lastUpdated = response.lastUpdated();
        return written;
    }

    /** Forgets remembered rows, for example after items were re-mapped. */
    public void reset() {
        lastStored.clear();
    }
}
