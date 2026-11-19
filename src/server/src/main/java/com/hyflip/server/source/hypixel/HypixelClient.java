package com.hyflip.server.source.hypixel;

import com.hyflip.server.config.HyFlipProperties;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Hypixel Public API v2 client for keyless endpoints. These are served from Hypixel's cache
 * (max-age 60 s), so polling faster than once a minute gains nothing.
 */
@Component
public class HypixelClient implements HypixelApi {

    private final RestClient http;

    public HypixelClient(RestClient externalRestClient, HyFlipProperties properties) {
        this.http = externalRestClient.mutate().baseUrl(properties.hypixel().baseUrl()).build();
    }

    @Override
    public BazaarResponse bazaar() {
        BazaarResponse r = get("/v2/skyblock/bazaar", BazaarResponse.class);
        return check(r.success(), r);
    }

    @Override
    public AuctionsEndedResponse auctionsEnded() {
        AuctionsEndedResponse r = get("/v2/skyblock/auctions_ended", AuctionsEndedResponse.class);
        return check(r.success(), r);
    }

    @Override
    public AuctionsPage auctions(int page) {
        AuctionsPage r = get("/v2/skyblock/auctions?page=" + page, AuctionsPage.class);
        return check(r.success(), r);
    }

    @Override
    public ItemsResponse items() {
        ItemsResponse r = get("/v2/resources/skyblock/items", ItemsResponse.class);
        return check(r.success(), r);
    }

    @Override
    public String resource(String name) {
        if (!name.matches("[a-z_]+")) {
            throw new IllegalArgumentException("Invalid resource name: " + name);
        }
        return get("/v2/resources/skyblock/" + name, String.class);
    }

    @Override
    public String profiles(String uuid, String apiKey) {
        if (!uuid.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid UUID: " + uuid);
        }
        String path = "/v2/skyblock/profiles?uuid=" + uuid;
        String body = call(path, () -> http.get().uri(path).header("API-Key", apiKey).retrieve().body(String.class));
        if (body == null) {
            throw new HypixelApiException(0, "Empty response from /v2/skyblock/profiles");
        }
        return body;
    }

    private <T> T get(String path, Class<T> type) {
        return call(path, () -> {
            T body = http.get().uri(path).retrieve().body(type);
            if (body == null) {
                throw new HypixelApiException(0, "Empty response from " + path);
            }
            return body;
        });
    }

    private static <T> T call(String path, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw new HypixelApiException(e.getStatusCode().value(), path + " returned " + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new HypixelApiException(0, path + " failed: " + e.getMessage());
        }
    }

    private static <T> T check(boolean success, T response) {
        if (!success) {
            throw new HypixelApiException(200, "Hypixel returned success=false");
        }
        return response;
    }
}
