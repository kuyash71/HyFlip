package com.hyflip.server.storage;

import com.hyflip.server.engine.Market.BazaarQuote;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Point-in-time price lookups by canonical item ID. Each lookup is an index seek on the
 * {@code (item_id, taken_at)} primary key, so querying a few hundred items is cheap.
 */
@Repository
public class PriceRepository {

    private final JdbcClient jdbc;

    public PriceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param price    lowest BIN per unit among clean listings
     * @param listings clean BIN listings seen in that scan
     */
    public record LowestBin(double price, long takenAt, int listings) {
    }

    /** @param unitPrice sale price ÷ item count */
    public record Sale(double unitPrice, int itemCount, long endedAt) {
    }

    /** Latest Bazaar snapshot of the item at or before {@code at}. */
    public Optional<BazaarQuote> bazaar(String canonicalId, long at) {
        return jdbc.sql("""
                SELECT s.buy_price, s.sell_price, s.buy_moving_week, s.sell_moving_week, s.taken_at, s.buy_orders,
                       s.sell_orders
                FROM item i JOIN bazaar_snapshot s ON s.item_id = i.id
                WHERE i.canonical_id = :id
                  AND s.taken_at = (SELECT max(taken_at) FROM bazaar_snapshot x WHERE x.item_id = i.id AND x.taken_at <= :at)
                """)
                .param("id", canonicalId).param("at", at)
                .query((rs, n) -> new BazaarQuote(rs.getDouble(1), rs.getDouble(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getInt(6), rs.getInt(7)))
                .optional();
    }

    /**
     * Latest Bazaar snapshot of every item at or before {@code at}, by canonical ID: one statement
     * with two index seeks per item, much cheaper than one prepared query per item. CROSS JOIN keeps
     * SQLite driving from {@code item}; otherwise it scans every snapshot row, which grows with history.
     */
    public java.util.Map<String, BazaarQuote> bazaarAll(long at) {
        java.util.Map<String, BazaarQuote> out = new java.util.HashMap<>();
        jdbc.sql("""
                SELECT i.canonical_id, s.buy_price, s.sell_price, s.buy_moving_week, s.sell_moving_week, s.taken_at,
                       s.buy_orders, s.sell_orders
                FROM item i CROSS JOIN bazaar_snapshot s ON s.item_id = i.id
                 AND s.taken_at = (SELECT max(taken_at) FROM bazaar_snapshot x WHERE x.item_id = i.id AND x.taken_at <= :at)
                """)
                .param("at", at)
                .query((rs, n) -> out.putIfAbsent(rs.getString(1), new BazaarQuote(rs.getDouble(2), rs.getDouble(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getInt(7), rs.getInt(8))))
                .list();
        return out;
    }

    /** Latest lowest BIN of every item at or before {@code at}, by canonical ID. */
    public java.util.Map<String, LowestBin> lowestBinAll(long at) {
        java.util.Map<String, LowestBin> out = new java.util.HashMap<>();
        jdbc.sql("""
                SELECT i.canonical_id, b.price, b.taken_at, b.listings
                FROM item i CROSS JOIN auction_lowest_bin b ON b.item_id = i.id
                 AND b.taken_at = (SELECT max(taken_at) FROM auction_lowest_bin x WHERE x.item_id = i.id AND x.taken_at <= :at)
                """)
                .param("at", at)
                .query((rs, n) -> out.putIfAbsent(rs.getString(1), new LowestBin(rs.getDouble(2), rs.getLong(3),
                        rs.getInt(4))))
                .list();
        return out;
    }

    /** What NPCs pay, by canonical ID, for every item they buy. */
    public java.util.Map<String, Double> npcSellPrices() {
        java.util.Map<String, Double> out = new java.util.HashMap<>();
        jdbc.sql("SELECT canonical_id, npc_sell_price FROM item WHERE npc_sell_price IS NOT NULL")
                .query((rs, n) -> out.putIfAbsent(rs.getString(1), rs.getDouble(2))).list();
        return out;
    }

    /** Latest lowest BIN of the item at or before {@code at}. */
    public Optional<LowestBin> lowestBin(String canonicalId, long at) {
        return jdbc.sql("""
                SELECT b.price, b.taken_at, b.listings
                FROM item i JOIN auction_lowest_bin b ON b.item_id = i.id
                WHERE i.canonical_id = :id
                  AND b.taken_at = (SELECT max(taken_at) FROM auction_lowest_bin x WHERE x.item_id = i.id AND x.taken_at <= :at)
                """)
                .param("id", canonicalId).param("at", at)
                .query((rs, n) -> new LowestBin(rs.getDouble(1), rs.getLong(2), rs.getInt(3)))
                .optional();
    }

    /** Time of the latest auction scan at or before {@code at}, empty if none was stored. */
    public Optional<Long> lastBinScanAt(long at) {
        return jdbc.sql("SELECT max(taken_at) FROM auction_lowest_bin WHERE taken_at <= :at")
                .param("at", at).query(Long.class).optional();
    }

    /**
     * For items whose last Coflnet fetch was a full page ({@code page} sales or more): the end of their oldest
     * stored Coflnet sale in {@code [from, to]}, by canonical ID. Before it their history was cut off, so sales per
     * day count from there, not from the start of the window.
     */
    public java.util.Map<String, Long> cutOffSince(long from, long to, int page) {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        jdbc.sql("""
                SELECT i.canonical_id, min(s.ended_at)
                FROM coflnet_backfill b
                JOIN item i ON i.id = b.item_id
                JOIN auction_sale s ON s.item_id = b.item_id AND s.source = 'COFLNET'
                WHERE b.sales >= :page AND s.ended_at BETWEEN :from AND :to
                GROUP BY i.canonical_id
                """)
                .param("page", page).param("from", from).param("to", to)
                .query((rs, n) -> out.put(rs.getString(1), rs.getLong(2)))
                .list();
        return out;
    }

    /** Ended auctions of clean copies of the item in {@code [from, to]}. */
    public List<Sale> sales(String canonicalId, long from, long to) {
        return jdbc.sql("""
                SELECT s.price / max(s.item_count, 1), s.item_count, s.ended_at
                FROM item i JOIN auction_sale s ON s.item_id = i.id
                WHERE i.canonical_id = :id AND s.ended_at BETWEEN :from AND :to AND s.clean = 1
                """)
                .param("id", canonicalId).param("from", from).param("to", to)
                .query((rs, n) -> new Sale(rs.getDouble(1), rs.getInt(2), rs.getLong(3)))
                .list();
    }

    /** Time of the newest stored Bazaar snapshot, empty if none: where stored history ends. */
    public Optional<Long> lastBazaarAt() {
        return jdbc.sql("SELECT max(taken_at) FROM bazaar_snapshot").query(Long.class).optional();
    }

    /** Time of the oldest stored Bazaar snapshot, empty if none. */
    public Optional<Long> firstBazaarAt() {
        return jdbc.sql("SELECT min(taken_at) FROM bazaar_snapshot").query(Long.class).optional();
    }

    /** End time of the oldest stored ended auction, empty if none: how far sale history reaches back. */
    public Optional<Long> firstSaleAt() {
        return jdbc.sql("SELECT min(ended_at) FROM auction_sale").query(Long.class).optional();
    }

    public Optional<Double> npcSellPrice(String canonicalId) {
        return jdbc.sql("SELECT npc_sell_price FROM item WHERE canonical_id = :id AND npc_sell_price IS NOT NULL")
                .param("id", canonicalId).query(Double.class).optional();
    }

    /** Canonical IDs of Bazaar products that NPCs also buy (candidates for Bazaar → NPC flips). */
    public List<String> npcSellableBazaarItems() {
        return jdbc.sql("""
                SELECT i.canonical_id FROM item i
                WHERE i.npc_sell_price > 0
                  AND EXISTS (SELECT 1 FROM bazaar_snapshot s WHERE s.item_id = i.id)
                ORDER BY i.canonical_id
                """).query(String.class).list();
    }

    /** Average prices of one item in one hour; null where that side had no price. */
    public record HourAverage(String itemId, long hour, Double buyPrice, Double sellPrice) {
    }

    /**
     * Per-item average Bazaar prices of each hour (epoch ms / 3 600 000) in {@code [from, to]}: the
     * building block of {@link #bazaarAverages}, so finished hours can be kept instead of re-read.
     */
    public List<HourAverage> bazaarHourAverages(long from, long to) {
        return jdbc.sql("""
                SELECT i.canonical_id, h.hr, h.bp, h.sp
                FROM (SELECT item_id, taken_at / 3600000 AS hr,
                             avg(NULLIF(buy_price, 0)) AS bp, avg(NULLIF(sell_price, 0)) AS sp
                      FROM bazaar_snapshot WHERE taken_at >= :from AND taken_at <= :to
                      GROUP BY item_id, hr) h
                JOIN item i ON i.id = h.item_id
                """).param("from", from).param("to", to)
                .query((rs, n) -> new HourAverage(rs.getString(1), rs.getLong(2), Rows.nullableDouble(rs, "bp"),
                        Rows.nullableDouble(rs, "sp")))
                .list();
    }

    /**
     * Hour-weighted average Bazaar prices per item in {@code [from, to]}: rows are stored only when
     * they change, so each hour counts once however often the price moved in it.
     */
    public java.util.Map<String, com.hyflip.server.engine.Market.BazaarAverage> bazaarAverages(long from, long to) {
        java.util.Map<String, com.hyflip.server.engine.Market.BazaarAverage> out = new java.util.HashMap<>();
        jdbc.sql("""
                SELECT i.canonical_id, avg(h.bp) AS buy, avg(h.sp) AS sell, min(h.hr) AS first_hour
                FROM (SELECT item_id, taken_at / 3600000 AS hr,
                             avg(NULLIF(buy_price, 0)) AS bp, avg(NULLIF(sell_price, 0)) AS sp
                      FROM bazaar_snapshot WHERE taken_at >= :from AND taken_at <= :to
                      GROUP BY item_id, hr) h
                JOIN item i ON i.id = h.item_id
                GROUP BY h.item_id
                """).param("from", from).param("to", to).query(rs -> {
            double days = (to - rs.getLong("first_hour") * 3_600_000L) / 86_400_000.0;
            out.put(rs.getString(1), new com.hyflip.server.engine.Market.BazaarAverage(rs.getDouble("buy"),
                    rs.getDouble("sell"), Math.max(0, days)));
        });
        return out;
    }

    /** Canonical IDs of every item with a stored Bazaar snapshot. */
    public List<String> bazaarItems() {
        return jdbc.sql("""
                SELECT i.canonical_id FROM item i
                WHERE EXISTS (SELECT 1 FROM bazaar_snapshot s WHERE s.item_id = i.id)
                ORDER BY i.canonical_id
                """).query(String.class).list();
    }
}
