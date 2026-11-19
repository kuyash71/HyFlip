package com.hyflip.server.source.coflnet;

import java.util.List;

/** The parts of Coflnet's public SkyBlock API that HyFlip reads. */
public interface CoflnetApi {

    /**
     * Recently sold auctions of one item ({@code GET /api/auctions/tag/{tag}/sold}), newest first;
     * Coflnet returns up to about a week. Empty if Coflnet does not know the tag.
     *
     * @throws CoflnetException on HTTP errors; {@link CoflnetException#rateLimited()} on 429
     */
    List<SoldAuction> sold(String tag);
}
