package com.hyflip.server.source.hypixel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /v2/skyblock/bazaar}. Of the order books only the best price level of each side is
 * used (Bazaar Flipper); they are not stored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BazaarResponse(boolean success, long lastUpdated, Map<String, Product> products) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Product(@JsonProperty("product_id") String productId,
                          @JsonProperty("quick_status") QuickStatus quickStatus,
                          @JsonProperty("sell_summary") List<Order> sellSummary,
                          @JsonProperty("buy_summary") List<Order> buySummary) {

        public Product(String productId, QuickStatus quickStatus) {
            this(productId, quickStatus, List.of(), List.of());
        }
    }

    /**
     * One price level of an order book. {@code sell_summary} lists buy orders (highest first),
     * {@code buy_summary} lists sell offers (lowest first).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Order(double amount, double pricePerUnit, int orders) {
    }

    /**
     * Verified against live data (2026-11-17): {@code sellPrice} tracks the highest buy orders
     * (what an insta-sell gets) and {@code buyPrice} tracks the lowest sell offers (what an
     * insta-buy costs). Both are weighted averages of the top of the book, and 0 when that side is
     * empty.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuickStatus(String productId,
                              double sellPrice, long sellVolume, long sellMovingWeek, long sellOrders,
                              double buyPrice, long buyVolume, long buyMovingWeek, long buyOrders) {
    }
}
