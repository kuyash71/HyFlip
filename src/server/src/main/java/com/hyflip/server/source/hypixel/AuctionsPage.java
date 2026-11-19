package com.hyflip.server.source.hypixel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One page of {@code GET /v2/skyblock/auctions} (active auctions). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuctionsPage(boolean success, int page, int totalPages, int totalAuctions, long lastUpdated,
                           List<ActiveAuction> auctions) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActiveAuction(String uuid,
                                boolean bin,
                                @JsonProperty("starting_bid") long startingBid,
                                long end,
                                @JsonProperty("item_bytes") String itemBytes) {
    }
}
