package com.hyflip.server.collector;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.storage.RetentionService;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Runs the retention job on the collector schedule so its runs show up in the status. */
@Component
public class RetentionCollector implements Collector {

    private final RetentionService retention;
    private final Duration interval;

    public RetentionCollector(RetentionService retention, HyFlipProperties properties) {
        this.retention = retention;
        this.interval = properties.collection().retentionInterval();
    }

    @Override
    public String name() {
        return "RETENTION";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public int collect() {
        return retention.run(System.currentTimeMillis());
    }
}
