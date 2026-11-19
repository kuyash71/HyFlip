package com.hyflip.server.source.coflnet;

import com.hyflip.server.config.HyFlipProperties;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Coflnet public API client. Requests carry only an item ID and the app's User-Agent. */
@Component
public class CoflnetClient implements CoflnetApi {

    private static final Pattern TAG = Pattern.compile("[A-Z0-9_:\\-]+");
    private static final ParameterizedTypeReference<List<SoldAuction>> SOLD = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;

    public CoflnetClient(RestClient externalRestClient, HyFlipProperties properties) {
        this.http = externalRestClient.mutate().baseUrl(properties.coflnet().baseUrl()).build();
    }

    /**
     * Coflnet answers an item it does not know with {@code 400 {"slug":"item_not_found",...}} (and some paths
     * with 404): that item simply has no sales there, which is not an error.
     */
    static boolean unknownItem(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        return status == 404 || status == 400 && e.getResponseBodyAsString().contains("item_not_found");
    }

    @Override
    public List<SoldAuction> sold(String tag) {
        if (!TAG.matcher(tag).matches()) {
            throw new IllegalArgumentException("Invalid item tag: " + tag);
        }
        String path = "/api/auctions/tag/" + tag + "/sold";
        try {
            List<SoldAuction> body = http.get().uri(path).retrieve().body(SOLD);
            return body == null ? List.of() : body;
        } catch (RestClientResponseException e) {
            if (unknownItem(e)) {
                return List.of();
            }
            throw new CoflnetException(e.getStatusCode().value(), path + " returned " + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new CoflnetException(0, path + " failed: " + e.getMessage());
        }
    }
}
