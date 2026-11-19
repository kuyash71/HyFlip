package com.hyflip.server.source.hypixel;

/** Hypixel Public API v2 endpoints used by the collectors; only {@link #profiles} needs a key. */
public interface HypixelApi {

    BazaarResponse bazaar();

    AuctionsEndedResponse auctionsEnded();

    AuctionsPage auctions(int page);

    ItemsResponse items();

    /** Raw JSON of a SkyBlock resource such as {@code election} or {@code collections}. */
    String resource(String name);

    /** Raw JSON of {@code /v2/skyblock/profiles} for a player UUID, with the personal API key. */
    default String profiles(String uuid, String apiKey) {
        throw new UnsupportedOperationException("profiles");
    }
}
