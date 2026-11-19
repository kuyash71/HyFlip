package com.hyflip.server.collector;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.mapping.ItemCatalogSync;
import com.hyflip.server.source.hypixel.HypixelApi;
import com.hyflip.server.storage.ResourceRepository;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Refreshes SkyBlock resources: item names and NPC sell prices into {@code item}; the raw election
 * (mayor, minister) and collections JSON into {@code resource_snapshot}.
 */
@Component
public class ResourcesCollector implements Collector {

    static final String[] RAW_RESOURCES = {"election", "collections"};

    private final HypixelApi hypixel;
    private final ItemCatalogSync catalog;
    private final ResourceRepository resources;
    private final Duration interval;

    public ResourcesCollector(HypixelApi hypixel, ItemCatalogSync catalog, ResourceRepository resources,
                              HyFlipProperties properties) {
        this.hypixel = hypixel;
        this.catalog = catalog;
        this.resources = resources;
        this.interval = properties.collection().resourcesInterval();
    }

    @Override
    public String name() {
        return "RESOURCES";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public int collect() {
        int written = catalog.registerHypixelItems(hypixel.items());
        for (String name : RAW_RESOURCES) {
            resources.save(name, hypixel.resource(name), System.currentTimeMillis());
            written++;
        }
        return written;
    }
}
