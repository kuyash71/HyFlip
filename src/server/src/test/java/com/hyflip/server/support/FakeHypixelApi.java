package com.hyflip.server.support;

import com.hyflip.server.source.hypixel.AuctionsEndedResponse;
import com.hyflip.server.source.hypixel.AuctionsPage;
import com.hyflip.server.source.hypixel.BazaarResponse;
import com.hyflip.server.source.hypixel.HypixelApi;
import com.hyflip.server.source.hypixel.HypixelApiException;
import com.hyflip.server.source.hypixel.ItemsResponse;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** In-memory Hypixel API backed by fixtures; tests replace responses as needed. */
public class FakeHypixelApi implements HypixelApi {

    public volatile BazaarResponse bazaar = Fixtures.json("hypixel/bazaar.json", BazaarResponse.class);
    public volatile AuctionsEndedResponse auctionsEnded =
            Fixtures.json("hypixel/auctions_ended.json", AuctionsEndedResponse.class);
    public volatile List<AuctionsPage> auctionPages = List.of();
    public volatile ItemsResponse items = new ItemsResponse(true, 1, List.of(
            new ItemsResponse.Item("REFINED_MITHRIL", "Refined Mithril", "MATERIAL", "EPIC", 1000.0)));
    public volatile RuntimeException failure;
    /** Body returned by {@link #profiles}; null answers 404. */
    public volatile String profiles;
    public volatile String lastProfilesKey;

    @Override
    public BazaarResponse bazaar() {
        failIfSet();
        return bazaar;
    }

    @Override
    public AuctionsEndedResponse auctionsEnded() {
        failIfSet();
        return auctionsEnded;
    }

    @Override
    public AuctionsPage auctions(int page) {
        failIfSet();
        return auctionPages.get(page);
    }

    @Override
    public ItemsResponse items() {
        failIfSet();
        return items;
    }

    @Override
    public String resource(String name) {
        failIfSet();
        return "{\"success\":true,\"name\":\"" + name + "\"}";
    }

    @Override
    public String profiles(String uuid, String apiKey) {
        failIfSet();
        lastProfilesKey = apiKey;
        if (profiles == null) {
            throw new HypixelApiException(404, "no profiles");
        }
        return profiles;
    }

    private void failIfSet() {
        if (failure != null) {
            throw failure;
        }
    }

    public static HypixelApiException serverError() {
        return new HypixelApiException(503, "test outage");
    }

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        FakeHypixelApi fakeHypixelApi() {
            return new FakeHypixelApi();
        }

        /** Mojang without the network: names map to UUIDs here. */
        @Bean
        @Primary
        FakeMojangApi fakeMojangApi() {
            return new FakeMojangApi();
        }
    }

    public static class FakeMojangApi implements com.hyflip.server.source.mojang.MojangApi {
        public final java.util.Map<String, String> uuids = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public java.util.Optional<String> uuid(String username) {
            return java.util.Optional.ofNullable(uuids.get(username.toLowerCase(java.util.Locale.ROOT)));
        }
    }
}
