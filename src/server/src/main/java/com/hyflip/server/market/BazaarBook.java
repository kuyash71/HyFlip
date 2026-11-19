package com.hyflip.server.market;

import com.hyflip.server.source.hypixel.BazaarResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The best price level of each Bazaar order book side from the latest poll, by canonical item ID.
 * In memory only: empty until the first poll after start. {@code quick_status} prices are averages
 * over the top of the book and can sit far from the best single order, which matters for spreads.
 */
@Component
public class BazaarBook {

    private volatile Map<String, Top> tops = Map.of();

    /**
     * @param buyOrder        highest buy order per unit, 0 if there is none
     * @param buyOrderAmount  units wanted at that price
     * @param sellOffer       lowest sell offer per unit, 0 if there is none
     * @param sellOfferAmount units offered at that price
     * @param nextBuyOrder    second-best buy order price level, 0 if there is none
     * @param nextSellOffer   second-best sell offer price level, 0 if there is none
     * @param at              Hypixel's {@code lastUpdated} of the response (epoch ms)
     */
    public record Top(double buyOrder, double buyOrderAmount, double sellOffer, double sellOfferAmount,
                      double nextBuyOrder, double nextSellOffer, long at) {

        public Top(double buyOrder, double buyOrderAmount, double sellOffer, double sellOfferAmount, long at) {
            this(buyOrder, buyOrderAmount, sellOffer, sellOfferAmount, 0, 0, at);
        }

        /** From {@code sell_summary} (buy orders) and {@code buy_summary} (sell offers); null lists count as empty. */
        public static Top of(List<BazaarResponse.Order> buyOrders, List<BazaarResponse.Order> sellOffers, long at) {
            BazaarResponse.Order bid = level(buyOrders, 0);
            BazaarResponse.Order ask = level(sellOffers, 0);
            BazaarResponse.Order nextBid = level(buyOrders, 1);
            BazaarResponse.Order nextAsk = level(sellOffers, 1);
            return new Top(bid == null ? 0 : bid.pricePerUnit(), bid == null ? 0 : bid.amount(),
                    ask == null ? 0 : ask.pricePerUnit(), ask == null ? 0 : ask.amount(),
                    nextBid == null ? 0 : nextBid.pricePerUnit(), nextAsk == null ? 0 : nextAsk.pricePerUnit(), at);
        }

        private static BazaarResponse.Order level(List<BazaarResponse.Order> book, int i) {
            return book == null || book.size() <= i ? null : book.get(i);
        }
    }

    public void replace(Map<String, Top> latest) {
        tops = Map.copyOf(latest);
    }

    public Optional<Top> top(String itemId) {
        return Optional.ofNullable(tops.get(itemId));
    }
}
