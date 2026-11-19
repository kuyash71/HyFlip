package com.hyflip.server.source.coflnet;

import java.util.List;

/** The parts of Coflnet's public SkyBlock API that HyFlip reads. */
public interface CoflnetApi {

    /**
     * Most sales one {@link #sold} call returns. A full page of a busy item covers less than a week, so it
     * says nothing about the time before its oldest sale.
     */
    int PAGE_SIZE = 1000;

    /**
     * Recently sold auctions of one item ({@code GET /api/auctions/tag/{tag}/sold}), newest first;
     * Coflnet returns up to about a week, at most {@link #PAGE_SIZE} sales. Empty if Coflnet does not know the tag.
     *
     * @throws CoflnetException on HTTP errors; {@link CoflnetException#rateLimited()} on 429
     */
    List<SoldAuction> sold(String tag);
}
