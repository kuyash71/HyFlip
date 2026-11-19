package com.hyflip.server.collector;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.mapping.ItemIds;
import com.hyflip.server.mapping.ItemRegistry;
import com.hyflip.server.mapping.ItemSource;
import com.hyflip.server.source.hypixel.AuctionItem;
import com.hyflip.server.source.hypixel.AuctionsEndedResponse;
import com.hyflip.server.source.hypixel.HypixelApi;
import com.hyflip.server.storage.AuctionRepository;
import com.hyflip.server.storage.AuctionRepository.Sale;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stores real sale prices from {@code auctions_ended}. The endpoint covers only about the last
 * minute, so this must run at least once a minute; missed windows cannot be recovered from
 * Hypixel.
 */
@Component
public class AuctionsEndedCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(AuctionsEndedCollector.class);

    private final HypixelApi hypixel;
    private final ItemRegistry registry;
    private final AuctionRepository auctions;
    private final Duration interval;
    private volatile long lastUpdated;

    public AuctionsEndedCollector(HypixelApi hypixel, ItemRegistry registry, AuctionRepository auctions,
                                  HyFlipProperties properties) {
        this.hypixel = hypixel;
        this.registry = registry;
        this.auctions = auctions;
        this.interval = properties.collection().auctionsEndedInterval();
    }

    @Override
    public String name() {
        return "AUCTIONS_ENDED";
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
        AuctionsEndedResponse response = hypixel.auctionsEnded();
        if (response.lastUpdated() == lastUpdated) {
            return 0;
        }
        List<Sale> sales = new ArrayList<>(response.auctions().size());
        int undecodable = 0;
        for (AuctionsEndedResponse.EndedAuction a : response.auctions()) {
            Long itemId = null;
            int count = 1;
            Boolean clean = null;
            try {
                AuctionItem item = AuctionItem.decode(a.itemBytes());
                String canonical = ItemIds.fromAuction(item);
                itemId = registry.resolve(ItemSource.AUCTION, canonical, canonical);
                count = item.count();
                clean = item.clean();
            } catch (IOException | RuntimeException e) {
                undecodable++;
            }
            sales.add(new Sale(a.auctionId(), a.timestamp(), itemId, a.price(), a.bin(), count, "HYPIXEL", clean));
        }
        if (undecodable > 0) {
            log.debug("{} ended auctions had undecodable items", undecodable);
        }
        lastUpdated = response.lastUpdated();
        return auctions.insertSales(sales);
    }
}
