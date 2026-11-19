package com.hyflip.server.storage;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Price history of one item in time buckets, for charts: full-resolution rows where they are kept,
 * hourly summaries before that.
 */
@Repository
public class HistoryRepository {

    private final JdbcClient jdbc;

    public HistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Bucket start, average insta-buy and insta-sell price in the bucket. */
    public record BazaarPoint(long t, double buyPrice, double sellPrice) {
    }

    /** Bucket start, unit price samples of clean sales (or hourly averages) in the bucket. */
    public record SaleRow(long t, double unitPrice, int sales) {
    }

    public record BinPoint(long t, double price) {
    }

    public List<BazaarPoint> bazaar(String id, long from, long to, long bucketMs) {
        List<BazaarPoint> out = new ArrayList<>(jdbc.sql("""
                SELECT (h.hour_start / :b) * :b AS t,
                       sum(h.buy_price_avg * h.samples) / sum(h.samples), sum(h.sell_price_avg * h.samples) / sum(h.samples)
                FROM bazaar_hourly h JOIN item i ON i.id = h.item_id
                WHERE i.canonical_id = :id AND h.hour_start BETWEEN :from AND :to
                  AND h.hour_start < coalesce((SELECT min(taken_at) FROM bazaar_snapshot s WHERE s.item_id = i.id), :to + 1)
                GROUP BY t ORDER BY t""")
                .param("id", id).param("from", from).param("to", to).param("b", bucketMs)
                .query((rs, n) -> new BazaarPoint(rs.getLong(1), rs.getDouble(2), rs.getDouble(3))).list());
        out.addAll(jdbc.sql("""
                SELECT (s.taken_at / :b) * :b AS t, avg(s.buy_price), avg(s.sell_price)
                FROM bazaar_snapshot s JOIN item i ON i.id = s.item_id
                WHERE i.canonical_id = :id AND s.taken_at BETWEEN :from AND :to
                GROUP BY t ORDER BY t""")
                .param("id", id).param("from", from).param("to", to).param("b", bucketMs)
                .query((rs, n) -> new BazaarPoint(rs.getLong(1), rs.getDouble(2), rs.getDouble(3))).list());
        return out;
    }

    /** Clean sale unit prices with their bucket, then hourly summaries for older periods. */
    public List<SaleRow> sales(String id, long from, long to, long bucketMs) {
        List<SaleRow> out = new ArrayList<>(jdbc.sql("""
                SELECT (h.hour_start / :b) * :b AS t, h.unit_price_avg, h.sales
                FROM auction_hourly h JOIN item i ON i.id = h.item_id
                WHERE i.canonical_id = :id AND h.hour_start BETWEEN :from AND :to
                  AND h.hour_start < coalesce((SELECT min(ended_at) FROM auction_sale s WHERE s.item_id = i.id), :to + 1)
                ORDER BY h.hour_start""")
                .param("id", id).param("from", from).param("to", to).param("b", bucketMs)
                .query((rs, n) -> new SaleRow(rs.getLong(1), rs.getDouble(2), rs.getInt(3))).list());
        out.addAll(jdbc.sql("""
                SELECT (s.ended_at / :b) * :b AS t, s.price / s.item_count
                FROM auction_sale s JOIN item i ON i.id = s.item_id
                WHERE i.canonical_id = :id AND s.clean = 1 AND s.ended_at BETWEEN :from AND :to
                ORDER BY s.ended_at""")
                .param("id", id).param("from", from).param("to", to).param("b", bucketMs)
                .query((rs, n) -> new SaleRow(rs.getLong(1), rs.getDouble(2), 1)).list());
        return out;
    }

    public List<BinPoint> lowestBin(String id, long from, long to, long bucketMs) {
        return jdbc.sql("""
                SELECT (b.taken_at / :b) * :b AS t, min(b.price)
                FROM auction_lowest_bin b JOIN item i ON i.id = b.item_id
                WHERE i.canonical_id = :id AND b.taken_at BETWEEN :from AND :to
                GROUP BY t ORDER BY t""")
                .param("id", id).param("from", from).param("to", to).param("b", bucketMs)
                .query((rs, n) -> new BinPoint(rs.getLong(1), rs.getDouble(2))).list();
    }
}
