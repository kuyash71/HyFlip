package com.hyflip.server.source.mojang;

import com.hyflip.server.config.HyFlipProperties;
import com.hyflip.server.source.hypixel.HypixelApiException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** {@code GET /users/profiles/minecraft/{name}}; only the username is sent. */
@Component
public class MojangClient implements MojangApi {

    private final RestClient http;

    public MojangClient(RestClient externalRestClient, HyFlipProperties properties) {
        this.http = externalRestClient.mutate().baseUrl(properties.hypixel().mojangUrl()).build();
    }

    record Profile(String id, String name) {
    }

    @Override
    public Optional<String> uuid(String username) {
        if (!username.matches("[A-Za-z0-9_]{1,16}")) {
            return Optional.empty();
        }
        try {
            Profile p = http.get().uri("/users/profiles/minecraft/{name}", username).retrieve().body(Profile.class);
            return Optional.ofNullable(p).map(Profile::id).map(id -> id.replace("-", "").toLowerCase(Locale.ROOT));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientResponseException e) {
            // Mojang answers 204 or 404 for unknown names depending on the endpoint version.
            if (e.getStatusCode().value() == 204) {
                return Optional.empty();
            }
            throw new HypixelApiException(e.getStatusCode().value(), "Mojang returned " + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new HypixelApiException(0, "Mojang request failed: " + e.getMessage());
        }
    }
}
