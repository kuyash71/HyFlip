package com.hyflip.server.mapping;

import com.hyflip.server.storage.ItemRepository;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves source IDs to canonical item rows, creating them on first sight. Cached in memory;
 * safe to call from several collector threads.
 *
 * <p>IDs whose canonical form is not a known NEU item are still stored (nothing is dropped) and
 * recorded in {@code unmapped_id} for review.
 */
@Service
public class ItemRegistry {

    private static final Logger log = LoggerFactory.getLogger(ItemRegistry.class);

    private final ItemRepository items;
    private final Map<String, Long> cache = new ConcurrentHashMap<>();
    private volatile Supplier<Set<String>> knownIds = Set::of;

    public ItemRegistry(ItemRepository items) {
        this.items = items;
    }

    /** Sets the catalogue of known canonical IDs (NEU internal names) used to flag unmapped IDs. */
    public void setKnownIds(Supplier<Set<String>> knownIds) {
        this.knownIds = knownIds;
    }

    /** Returns the item row ID for a source ID, creating the item and alias if needed. */
    public long resolve(ItemSource source, String sourceId, String canonicalId) {
        return cache.computeIfAbsent(key(source, sourceId), k -> {
            long id = items.ensureItem(canonicalId);
            items.upsertAlias(source.name(), sourceId, id);
            // The Hypixel items resource is itself a catalogue (it lists ~420 items NEU lacks, such as
            // removed or admin items), so only Bazaar and auction IDs count as unmapped.
            Set<String> known = knownIds.get();
            if (source != ItemSource.HYPIXEL_ITEM && !known.isEmpty() && !known.contains(canonicalId)) {
                items.recordUnmapped(source.name(), sourceId);
                log.debug("Unmapped {} ID {} (canonical {})", source, sourceId, canonicalId);
            }
            return id;
        });
    }

    /** Forgets cached resolutions, for example after aliases were re-pointed. */
    public void clearCache() {
        cache.clear();
    }

    private static String key(ItemSource source, String sourceId) {
        return source.name() + '|' + sourceId;
    }
}
