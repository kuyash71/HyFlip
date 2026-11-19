package com.hyflip.server.source.hypixel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** {@code GET /v2/skyblock/auctions_ended}: auctions that ended in about the last 60 seconds. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuctionsEndedResponse(boolean success, long lastUpdated, List<EndedAuction> auctions) {

    /** Buyer and seller are deliberately not mapped; HyFlip does not store player IDs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EndedAuction(@JsonProperty("auction_id") String auctionId,
                               long timestamp,
                               long price,
                               boolean bin,
                               @JsonProperty("item_bytes") String itemBytes) {
    }
}
