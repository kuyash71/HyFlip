package com.hyflip.server.profile;

import com.hyflip.server.flipper.forge.ForgeTracker;
import com.hyflip.server.settings.Settings;
import com.hyflip.server.settings.SettingsService;
import com.hyflip.server.source.hypixel.HypixelApi;
import com.hyflip.server.source.hypixel.HypixelApiException;
import com.hyflip.server.source.mojang.MojangApi;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Profile linking: username → UUID (Mojang), then the SkyBlock profile (Hypixel, personal key).
 * The result fills HOTM level, Quick Forge, collection tiers and the Forge Tracker.
 */
@Service
public class ProfileLinkService {

    private final SettingsService settings;
    private final MojangApi mojang;
    private final HypixelApi hypixel;
    private final LinkedProfileStore store;
    private final ForgeTracker tracker;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public ProfileLinkService(SettingsService settings, MojangApi mojang, HypixelApi hypixel, LinkedProfileStore store,
                              ForgeTracker tracker, ObjectMapper json) {
        this(settings, mojang, hypixel, store, tracker, json, Clock.systemUTC());
    }

    ProfileLinkService(SettingsService settings, MojangApi mojang, HypixelApi hypixel, LinkedProfileStore store,
                       ForgeTracker tracker, ObjectMapper json, Clock clock) {
        this.settings = settings;
        this.mojang = mojang;
        this.hypixel = hypixel;
        this.store = store;
        this.tracker = tracker;
        this.json = json;
        this.clock = clock;
    }

    public record SyncResult(LinkedProfile profile, ForgeTracker.ProfileSync forge) {
    }

    /** True when a username and an API key are set, so a sync can be tried. */
    public boolean configured() {
        return !settings.current().player().username().isBlank() && settings.apiKey().present();
    }

    /**
     * Reads the profile now and stores it. Failures are stored too, for the status panel.
     *
     * @throws ProfileLinkException with the reason
     */
    public SyncResult sync() {
        try {
            SyncResult r = fetch();
            store.saved(r.profile());
            return r;
        } catch (ProfileLinkException e) {
            store.failed(e.getMessage(), clock.millis());
            throw e;
        }
    }

    /** Forgets the linked profile; manual settings apply again. */
    public void unlink() {
        store.clear();
    }

    private SyncResult fetch() {
        Settings s = settings.current();
        String username = s.player().username();
        if (username.isBlank()) {
            throw new ProfileLinkException(ProfileLinkException.Kind.NOT_CONFIGURED, "Set your Minecraft username first");
        }
        SettingsService.ApiKey key = settings.apiKey();
        if (!key.present()) {
            throw new ProfileLinkException(ProfileLinkException.Kind.NOT_CONFIGURED,
                    "Enter your Hypixel API key (developer.hypixel.net) to link your profile");
        }
        String uuid = uuid(username);
        String member = s.player().coopMember().isBlank() ? uuid : uuid(s.player().coopMember());
        String body;
        try {
            body = hypixel.profiles(uuid, key.value());
        } catch (HypixelApiException e) {
            throw switch (e.status()) {
                case 403 -> new ProfileLinkException(ProfileLinkException.Kind.KEY_REJECTED,
                        "Hypixel rejected the API key; personal keys expire after 3 days, create a new one");
                case 429 -> new ProfileLinkException(ProfileLinkException.Kind.RATE_LIMITED,
                        "Hypixel rate limit reached; try again in a few minutes");
                default -> new ProfileLinkException(ProfileLinkException.Kind.UNAVAILABLE,
                        "Could not read the profile: " + e.getMessage());
            };
        }
        LinkedProfile profile;
        try {
            profile = SkyblockProfiles.parse(json.readTree(body), username, member, s.player().profile(), clock.millis());
        } catch (SkyblockProfiles.ProfileException e) {
            throw new ProfileLinkException(ProfileLinkException.Kind.NOT_FOUND, e.getMessage());
        } catch (JacksonException e) {
            throw new ProfileLinkException(ProfileLinkException.Kind.UNAVAILABLE, "Unreadable profile response");
        }
        ForgeTracker.ProfileSync forge = profile.forgeKnown()
                ? tracker.syncFromProfile(profile.forge(), profile.fetchedAt())
                : ForgeTracker.ProfileSync.NONE;
        return new SyncResult(profile, forge);
    }

    private String uuid(String name) {
        Optional<String> id;
        try {
            id = mojang.uuid(name);
        } catch (HypixelApiException e) {
            throw new ProfileLinkException(ProfileLinkException.Kind.UNAVAILABLE, e.getMessage());
        }
        return id.orElseThrow(() -> new ProfileLinkException(ProfileLinkException.Kind.NOT_FOUND,
                "No Minecraft player named " + name));
    }
}
