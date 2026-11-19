package com.hyflip.server.engine;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/** A {@link Market} over fixed maps, for tests and precomputed snapshots. */
public record StaticMarket(Map<String, BazaarQuote> bazaar, Map<String, AuctionQuote> auction,
                           Map<String, Double> npc) implements Market {

    public StaticMarket {
        bazaar = Map.copyOf(bazaar);
        auction = Map.copyOf(auction);
        npc = Map.copyOf(npc);
    }

    @Override
    public Optional<BazaarQuote> bazaar(String itemId) {
        return Optional.ofNullable(bazaar.get(itemId));
    }

    @Override
    public Optional<AuctionQuote> auction(String itemId) {
        return Optional.ofNullable(auction.get(itemId));
    }

    @Override
    public OptionalDouble npcSellPrice(String itemId) {
        Double price = npc.get(itemId);
        return price == null ? OptionalDouble.empty() : OptionalDouble.of(price);
    }
}
