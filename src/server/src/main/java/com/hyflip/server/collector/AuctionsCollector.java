package com.hyflip.server.collector;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.mapping.ItemIds;
import com.hyflip.server.mapping.ItemRegistry;
import com.hyflip.server.mapping.ItemSource;
import com.hyflip.server.source.hypixel.AuctionItem;
import com.hyflip.server.source.hypixel.AuctionsPage;
import com.hyflip.server.source.hypixel.HypixelApi;
import com.hyflip.server.source.neu.NeuData;
import com.hyflip.server.source.neu.NeuRecipe;
import com.hyflip.server.source.neu.NeuRepository;
import com.hyflip.server.storage.AuctionRepository;
import com.hyflip.server.storage.AuctionRepository.LowestBin;
import com.hyflip.server.storage.ItemRepository;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Scans all active auctions and records the lowest BIN (per unit) of clean copies of tracked items
 * (see {@link com.hyflip.server.mapping.ItemCondition}): upgraded copies are listed far above
 * what a freshly made item sells for.
 *
 * <p>Tracked items are the forge and crafting inputs and outputs from NEU that are not sold on the
 * Bazaar, plus {@code collection.tracked-auction-items}. A full scan downloads about 55 MB (item
 * data does not compress), which is why the default interval is 30 minutes.
 */
@Component
public class AuctionsCollector implements Collector {

    static final int MAX_PAGES = 200;

    private final HypixelApi hypixel;
    private final ItemRegistry registry;
    private final NeuRepository neu;
    private final ItemRepository items;
    private final AuctionRepository auctions;
    private final HyFlipProperties.Collection config;

    public AuctionsCollector(HypixelApi hypixel, ItemRegistry registry, NeuRepository neu, ItemRepository items,
                             AuctionRepository auctions, HyFlipProperties properties) {
        this.hypixel = hypixel;
        this.registry = registry;
        this.neu = neu;
        this.items = items;
        this.auctions = auctions;
        this.config = properties.collection();
    }

    @Override
    public String name() {
        return "AUCTIONS";
    }

    @Override
    public Duration interval() {
        return config.auctionsInterval();
    }

    @Override
    public boolean recordsHistory() {
        return true;
    }

    /** The tracked set comes from NEU recipes, so scan as soon as they arrive. */
    @Override
    public boolean runsAfterNeuUpdate() {
        return true;
    }

    @Override
    public int collect() {
        Set<String> tracked = trackedItems();
        if (tracked.isEmpty()) {
            return 0;
        }
        Map<String, double[]> lowest = new HashMap<>(); // canonical -> {price per unit, listings}
        AuctionsPage first = hypixel.auctions(0);
        int pages = Math.min(first.totalPages(), MAX_PAGES);
        scan(first, tracked, lowest);
        for (int page = 1; page < pages; page++) {
            scan(hypixel.auctions(page), tracked, lowest);
        }
        List<LowestBin> rows = new ArrayList<>(lowest.size());
        for (Map.Entry<String, double[]> e : lowest.entrySet()) {
            long itemId = registry.resolve(ItemSource.AUCTION, e.getKey(), e.getKey());
            rows.add(new LowestBin(first.lastUpdated(), itemId, e.getValue()[0], (int) e.getValue()[1]));
        }
        return auctions.insertLowestBins(rows);
    }

    private static void scan(AuctionsPage page, Set<String> tracked, Map<String, double[]> lowest) {
        for (AuctionsPage.ActiveAuction a : page.auctions()) {
            if (!a.bin()) {
                continue;
            }
            AuctionItem item;
            try {
                item = AuctionItem.decode(a.itemBytes());
            } catch (IOException | RuntimeException e) {
                continue;
            }
            String canonical = ItemIds.fromAuction(item);
            if (!tracked.contains(canonical) || !item.clean()) {
                continue;
            }
            double unit = (double) a.startingBid() / item.count();
            double[] entry = lowest.computeIfAbsent(canonical, k -> new double[] {Double.MAX_VALUE, 0});
            entry[0] = Math.min(entry[0], unit);
            entry[1]++;
        }
    }

    /** Recipe items not on the Bazaar, plus the configured watch list. */
    Set<String> trackedItems() {
        Set<String> tracked = new HashSet<>(config.trackedAuctionItems());
        neu.current().ifPresent(data -> tracked.addAll(recipeItemsOffBazaar(data)));
        return tracked;
    }

    private Set<String> recipeItemsOffBazaar(NeuData data) {
        Set<String> onBazaar = new HashSet<>();
        items.aliases(ItemSource.BAZAAR.name()).forEach(a -> onBazaar.add(a.canonicalId()));
        Set<String> result = new HashSet<>();
        for (NeuRecipe r : data.recipes()) {
            if (r.type() == NeuRecipe.Type.NPC_SHOP) {
                continue;
            }
            result.add(r.output());
            result.addAll(r.inputs().keySet());
        }
        result.removeAll(onBazaar);
        result.removeIf(id -> !data.items().containsKey(id));
        return result;
    }
}
