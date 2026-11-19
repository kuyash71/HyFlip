package com.hyflip.server.storage;

import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code auction_sale} and {@code auction_lowest_bin}. */
@Repository
public class AuctionRepository {

    private final JdbcTemplate template;
    private final JdbcClient jdbc;

    public AuctionRepository(JdbcTemplate template, JdbcClient jdbc) {
        this.template = template;
        this.jdbc = jdbc;
    }

    /**
     * @param price total price of the auction (all {@code itemCount} items)
     * @param clean true if the item had no value-adding upgrades, null if unknown (undecodable)
     */
    public record Sale(String auctionId, long endedAt, Long itemId, double price, boolean bin, int itemCount,
                       String source, Boolean clean) {

        /** A sale of a clean item. */
        public Sale(String auctionId, long endedAt, Long itemId, double price, boolean bin, int itemCount,
                    String source) {
            this(auctionId, endedAt, itemId, price, bin, itemCount, source, true);
        }
    }

    /** @param price lowest BIN price per unit */
    public record LowestBin(long takenAt, long itemId, double price, int listings) {
    }

    public int insertSales(Collection<Sale> sales) {
        return ItemRepository.sum(template.batchUpdate("""
                INSERT OR IGNORE INTO auction_sale (auction_id, ended_at, item_id, price, bin, item_count, source, clean)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, sales, 500, (ps, s) -> {
            ps.setString(1, s.auctionId());
            ps.setLong(2, s.endedAt());
            if (s.itemId() == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setLong(3, s.itemId());
            }
            ps.setDouble(4, s.price());
            ps.setInt(5, s.bin() ? 1 : 0);
            ps.setInt(6, s.itemCount());
            ps.setString(7, s.source());
            if (s.clean() == null) {
                ps.setNull(8, java.sql.Types.INTEGER);
            } else {
                ps.setInt(8, s.clean() ? 1 : 0);
            }
        }));
    }

    public int insertLowestBins(Collection<LowestBin> rows) {
        return ItemRepository.sum(template.batchUpdate("""
                INSERT OR IGNORE INTO auction_lowest_bin (taken_at, item_id, price, listings) VALUES (?, ?, ?, ?)
                """, rows, 500, (ps, r) -> {
            ps.setLong(1, r.takenAt());
            ps.setLong(2, r.itemId());
            ps.setDouble(3, r.price());
            ps.setInt(4, r.listings());
        }));
    }

    /** When each item's sale history was last fetched from Coflnet, by item row ID. */
    public java.util.Map<Long, Long> backfilledAt() {
        java.util.Map<Long, Long> out = new java.util.HashMap<>();
        jdbc.sql("SELECT item_id, fetched_at FROM coflnet_backfill")
                .query((rs, n) -> out.put(rs.getLong(1), rs.getLong(2)))
                .list();
        return out;
    }

    public void markBackfilled(long itemId, long at, int sales) {
        jdbc.sql("INSERT OR REPLACE INTO coflnet_backfill (item_id, fetched_at, sales) VALUES (:id, :at, :sales)")
                .param("id", itemId).param("at", at).param("sales", sales).update();
    }

    public long salesCount() {
        return jdbc.sql("SELECT count(*) FROM auction_sale").query(Long.class).single();
    }
}
