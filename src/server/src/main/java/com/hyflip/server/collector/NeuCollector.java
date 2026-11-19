package com.hyflip.server.collector;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.mapping.ItemCatalogSync;
import com.hyflip.server.source.neu.NeuRepository;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Keeps the local NEU copy current. The copy on disk is loaded synchronously at start-up, before
 * other collectors run, so Bazaar shards map correctly from the first poll.
 */
@Component
public class NeuCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(NeuCollector.class);

    private final NeuRepository neu;
    private final ItemCatalogSync catalog;
    private final BazaarCollector bazaar;
    private final HyFlipProperties.Neu config;
    private final ApplicationEventPublisher events;

    public NeuCollector(NeuRepository neu, ItemCatalogSync catalog, BazaarCollector bazaar,
                        HyFlipProperties properties, ApplicationEventPublisher events) {
        this.neu = neu;
        this.catalog = catalog;
        this.bazaar = bazaar;
        this.config = properties.neu();
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void loadLocal() {
        try {
            if (neu.loadLocal()) {
                neu.current().ifPresent(catalog::registerNeu);
            }
        } catch (Exception e) {
            log.warn("Could not load local NEU copy: {}", e.getMessage());
        }
    }

    @Override
    public String name() {
        return "NEU";
    }

    @Override
    public Duration interval() {
        return config.refreshInterval();
    }

    @Override
    public int collect() throws Exception {
        if (!config.enabled() || !neu.sync()) {
            return 0;
        }
        int written = neu.current().map(catalog::registerNeu).orElse(0);
        bazaar.reset();
        neu.current().ifPresent(d -> events.publishEvent(new NeuUpdatedEvent(d.commit())));
        return written;
    }
}
