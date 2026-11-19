package com.hyflip.server.profile;

import com.hyflip.server.storage.AppStateRepository;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The last profile read by profile linking and the last error, kept in {@code app_state} so they
 * survive a restart.
 */
@Component
public class LinkedProfileStore {

    static final String KEY_PROFILE = "profile.linked";
    static final String KEY_ERROR = "profile.error";
    static final String KEY_ATTEMPT = "profile.attempt-at";

    private final AppStateRepository state;
    private final ObjectMapper json;
    private final AtomicReference<Optional<LinkedProfile>> cached = new AtomicReference<>();

    public LinkedProfileStore(AppStateRepository state, ObjectMapper json) {
        this.state = state;
        this.json = json;
    }

    public Optional<LinkedProfile> profile() {
        Optional<LinkedProfile> p = cached.get();
        if (p == null) {
            p = state.get(KEY_PROFILE).flatMap(body -> {
                try {
                    return Optional.of(json.readValue(body, LinkedProfile.class));
                } catch (JacksonException e) {
                    return Optional.empty();
                }
            });
            cached.set(p);
        }
        return p;
    }

    public Optional<String> lastError() {
        return state.get(KEY_ERROR);
    }

    public Optional<Long> lastAttemptAt() {
        return state.get(KEY_ATTEMPT).map(Long::parseLong);
    }

    public void saved(LinkedProfile profile) {
        state.put(KEY_PROFILE, json.writeValueAsString(profile));
        state.delete(KEY_ERROR);
        state.put(KEY_ATTEMPT, Long.toString(profile.fetchedAt()));
        cached.set(Optional.of(profile));
    }

    public void failed(String error, long at) {
        state.put(KEY_ERROR, error);
        state.put(KEY_ATTEMPT, Long.toString(at));
    }

    public void clear() {
        state.delete(KEY_PROFILE);
        state.delete(KEY_ERROR);
        state.delete(KEY_ATTEMPT);
        cached.set(Optional.empty());
    }
}
