package com.hyflip.server.api;

import com.hyflip.server.flipper.FlipList;
import com.hyflip.server.flipper.forge.ForgeCalibrationService;
import com.hyflip.server.profile.LinkedProfile;
import com.hyflip.server.profile.LinkedProfileStore;
import com.hyflip.server.profile.PlayerContext;
import com.hyflip.server.profile.PlayerContextService;
import com.hyflip.server.profile.ProfileLinkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Effective personal context, and profile linking. */
@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final PlayerContextService contexts;
    private final ForgeCalibrationService calibration;
    private final ProfileLinkService link;
    private final LinkedProfileStore store;

    public ProfileController(PlayerContextService contexts, ForgeCalibrationService calibration,
                             ProfileLinkService link, LinkedProfileStore store) {
        this.contexts = contexts;
        this.calibration = calibration;
        this.link = link;
        this.store = store;
    }

    /**
     * @param linked        the linked profile in use, null if none (or it was read for another
     *                      username or profile)
     * @param configured    true when a username and an API key are set
     * @param lastError     why the last sync failed, null after a successful one
     * @param lastAttemptAt time of the last sync attempt
     */
    public record Link(LinkedProfile linked, boolean configured, String lastError, Long lastAttemptAt) {
    }

    /** @param collectionsKnown false until the RESOURCES collector stored the collections resource */
    public record Profile(PlayerContext context, FlipList.ForgeSpeedInfo forgeSpeed, boolean collectionsKnown,
                          Link link) {
    }

    @GetMapping
    public Profile profile() {
        PlayerContext ctx = contexts.current();
        return new Profile(ctx, calibration.info(ctx), !contexts.collections().isEmpty(), link());
    }

    /** Reads the SkyBlock profile now. */
    @PostMapping("/link")
    public ProfileLinkService.SyncResult sync() {
        return link.sync();
    }

    @DeleteMapping("/link")
    public ResponseEntity<Void> unlink() {
        link.unlink();
        return ResponseEntity.noContent().build();
    }

    private Link link() {
        return new Link(contexts.linkedProfile().orElse(null), link.configured(), store.lastError().orElse(null),
                store.lastAttemptAt().orElse(null));
    }
}
