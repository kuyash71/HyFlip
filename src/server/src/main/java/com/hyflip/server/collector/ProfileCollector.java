package com.hyflip.server.collector;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.profile.ProfileLinkService;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Re-reads the linked SkyBlock profile ({@code hypixel.profile-interval}). Does nothing until a
 * username and an API key are set.
 */
@Component
public class ProfileCollector implements Collector {

    private final ProfileLinkService link;
    private final Duration interval;

    public ProfileCollector(ProfileLinkService link, HyFlipProperties properties) {
        this.link = link;
        this.interval = properties.hypixel().profileInterval();
    }

    @Override
    public String name() {
        return "PROFILE";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public int collect() {
        if (!link.configured()) {
            return 0;
        }
        ProfileLinkService.SyncResult r = link.sync();
        return 1 + r.forge().added() + r.forge().moved() + r.forge().collected();
    }
}
