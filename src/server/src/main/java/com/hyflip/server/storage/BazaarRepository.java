package com.hyflip.server.storage;

import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code bazaar_snapshot}. */
@Repository
public class BazaarRepository {

    private final JdbcTemplate template;
    private final JdbcClient jdbc;

    public BazaarRepository(JdbcTemplate template, JdbcClient jdbc) {
        this.template = template;
        this.jdbc = jdbc;
    }

    public record Snapshot(long takenAt, long itemId, double buyPrice, double sellPrice,
                           long buyVolume, long sellVolume, long buyMovingWeek, long sellMovingWeek,
                           long buyOrders, long sellOrders, String source) {

        /** True if every market field equals {@code other}'s (time and source ignored). */
        public boolean sameMarket(Snapshot other) {
            return other != null && buyPrice == other.buyPrice && sellPrice == other.sellPrice
                    && buyVolume == other.buyVolume && sellVolume == other.sellVolume
                    && buyMovingWeek == other.buyMovingWeek && sellMovingWeek == other.sellMovingWeek
                    && buyOrders == other.buyOrders && sellOrders == other.sellOrders;
        }
    }

    public int insert(Collection<Snapshot> rows) {
        return ItemRepository.sum(template.batchUpdate("""
                INSERT OR IGNORE INTO bazaar_snapshot
                (taken_at, item_id, buy_price, sell_price, buy_volume, sell_volume,
                 buy_moving_week, sell_moving_week, buy_orders, sell_orders, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows, 1000, (ps, r) -> {
            ps.setLong(1, r.takenAt());
            ps.setLong(2, r.itemId());
            ps.setDouble(3, r.buyPrice());
            ps.setDouble(4, r.sellPrice());
            ps.setLong(5, r.buyVolume());
            ps.setLong(6, r.sellVolume());
            ps.setLong(7, r.buyMovingWeek());
            ps.setLong(8, r.sellMovingWeek());
            ps.setLong(9, r.buyOrders());
            ps.setLong(10, r.sellOrders());
            ps.setString(11, r.source());
        }));
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM bazaar_snapshot").query(Long.class).single();
    }
}
