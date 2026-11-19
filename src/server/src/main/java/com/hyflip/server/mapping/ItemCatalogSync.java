package com.hyflip.server.mapping;

import com.hyflip.server.source.hypixel.ItemsResponse;
import com.hyflip.server.source.neu.NeuData;
import com.hyflip.server.storage.ItemRepository;
import com.hyflip.server.storage.ItemRepository.Alias;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the {@code item} table in line with the NEU catalogue and the Hypixel items resource.
 *
 * <p>When NEU loads after the Bazaar was already collected (first start, or NEU added new
 * {@code bazaarstocks} entries), some Bazaar aliases point at fallback IDs. {@link #registerNeu}
 * re-points them and moves their history to the correct item.
 */
@Service
public class ItemCatalogSync {

    private static final Logger log = LoggerFactory.getLogger(ItemCatalogSync.class);

    private final ItemRepository items;
    private final ItemRegistry registry;
    private final TransactionTemplate tx;

    public ItemCatalogSync(ItemRepository items, ItemRegistry registry, TransactionTemplate tx) {
        this.items = items;
        this.registry = registry;
        this.tx = tx;
    }

    /** Registers every NEU item, fixes Bazaar aliases and clears resolved unmapped entries. */
    public int registerNeu(NeuData neu) {
        registry.setKnownIds(neu.items()::keySet);
        int written = tx.execute(status -> {
            int n = items.ensureItems(neu.items().values().stream()
                    .map(i -> new ItemRepository.NewItem(i.internalName(), i.displayName()))
                    .toList());
            n += reconcileBazaar(neu.bazaarStocks());
            items.deleteUnmappedKnown(neu.items().keySet());
            return n;
        });
        registry.clearCache();
        return written;
    }

    /** Updates display names and NPC sell prices from the Hypixel items resource. */
    public int registerHypixelItems(ItemsResponse response) {
        return tx.execute(status -> {
            int n = 0;
            for (ItemsResponse.Item item : response.items()) {
                if (item.id() == null) {
                    continue;
                }
                long id = registry.resolve(ItemSource.HYPIXEL_ITEM, item.id(), ItemIds.fromHypixelItemId(item.id()));
                items.updateMetadata(id, item.name(), item.npcSellPrice());
                n++;
            }
            return n;
        });
    }

    private int reconcileBazaar(Map<String, String> stocks) {
        int moved = 0;
        for (Alias alias : items.aliases(ItemSource.BAZAAR.name())) {
            String expected = ItemIds.fromBazaar(alias.sourceId(), stocks);
            if (!expected.equals(alias.canonicalId())) {
                long target = items.ensureItem(expected);
                items.moveItem(alias.itemId(), target);
                items.upsertAlias(alias.source(), alias.sourceId(), target);
                items.deleteIfOrphan(alias.itemId());
                log.info("Re-mapped Bazaar {} from {} to {}", alias.sourceId(), alias.canonicalId(), expected);
                moved++;
            }
        }
        return moved;
    }
}
