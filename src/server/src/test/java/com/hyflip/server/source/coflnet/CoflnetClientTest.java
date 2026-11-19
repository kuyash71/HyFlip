package com.hyflip.server.source.coflnet;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class CoflnetClientTest {

    static HttpClientErrorException error(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), null,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void anItemCoflnetDoesNotKnowHasNoSales() {
        // What Coflnet answers for KUUDRA_INFERNAL_TIER_KEY (checked 2026-11-19)
        assertThat(CoflnetClient.unknownItem(error(HttpStatus.BAD_REQUEST,
                "{\"slug\":\"item_not_found\",\"message\":\"could not find the item with the tag `X`\",\"trace\":null}")))
                .isTrue();
        assertThat(CoflnetClient.unknownItem(error(HttpStatus.NOT_FOUND, ""))).isTrue();
        assertThat(CoflnetClient.unknownItem(error(HttpStatus.BAD_REQUEST, "{\"slug\":\"invalid_tag\"}"))).isFalse();
    }
}
