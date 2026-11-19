package com.hyflip.server.storage;

import com.hyflip.server.config.HyFlipProperties;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies {@code retention}: minute-level data older than {@code full-resolution} is folded into
 * hourly summaries (when {@code downsample} is on) and deleted.
 *
 * <p>Snapshots are stored only when a product changes, so the latest row of each item is kept even
 * when it is old; otherwise items that rarely change would lose their current price. A watermark
 * in {@code app_state} makes sure each hour is summarised exactly once.
 */
@Service
public class RetentionService {

    static final long HOUR = Duration.ofHours(1).toMillis();
    static final String BAZAAR_WATERMARK = "retention.bazaar.watermark";
    static final String AUCTION_WATERMARK = "retention.auction.watermark";

    private final JdbcTemplate jdbc;
    private final AppStateRepository state;
    private final TransactionTemplate tx;
    private final HyFlipProperties.Retention retention;

    public RetentionService(JdbcTemplate jdbc, AppStateRepository state, TransactionTemplate tx,
                            HyFlipProperties properties) {
        this.jdbc = jdbc;
        this.state = state;
        this.tx = tx;
        this.retention = properties.retention();
    }

    /** Runs retention as of {@code now}; returns the number of rows deleted. */
    public int run(long now) {
        long cutoff = Math.floorDiv(now - retention.fullResolution().toMillis(), HOUR) * HOUR;
        Integer deleted = tx.execute(status -> bazaar(cutoff) + auctions(cutoff) + others(cutoff));
        return deleted == null ? 0 : deleted;
    }

    private int bazaar(long cutoff) {
        long from = state.getLong(BAZAAR_WATERMARK, 0);
        if (retention.downsample() && cutoff > from) {
            jdbc.update("""
                    INSERT OR REPLACE INTO bazaar_hourly
                    (hour_start, item_id, buy_price_avg, buy_price_min, buy_price_max,
                     sell_price_avg, sell_price_min, sell_price_max, samples)
                    SELECT (taken_at / ?) * ?, item_id, avg(buy_price), min(buy_price), max(buy_price),
                           avg(sell_price), min(sell_price), max(sell_price), count(*)
                    FROM bazaar_snapshot WHERE taken_at >= ? AND taken_at < ?
                    GROUP BY 1, 2
                    """, HOUR, HOUR, from, cutoff);
        }
        if (cutoff > from) {
            state.put(BAZAAR_WATERMARK, Long.toString(cutoff));
        }
        return jdbc.update("""
                DELETE FROM bazaar_snapshot WHERE taken_at < ?
                AND (item_id, taken_at) NOT IN (SELECT item_id, max(taken_at) FROM bazaar_snapshot GROUP BY item_id)
                """, cutoff);
    }

    private int auctions(long cutoff) {
        long from = state.getLong(AUCTION_WATERMARK, 0);
        if (retention.downsample() && cutoff > from) {
            jdbc.update("""
                    INSERT OR REPLACE INTO auction_hourly
                    (hour_start, item_id, sales, units, unit_price_avg, unit_price_min, unit_price_max)
                    SELECT (ended_at / ?) * ?, item_id, count(*), sum(item_count),
                           sum(price) / sum(item_count), min(price / item_count), max(price / item_count)
                    FROM auction_sale WHERE item_id IS NOT NULL AND clean = 1 AND ended_at >= ? AND ended_at < ?
                    GROUP BY 1, 2
                    """, HOUR, HOUR, from, cutoff);
        }
        if (cutoff > from) {
            state.put(AUCTION_WATERMARK, Long.toString(cutoff));
        }
        return jdbc.update("DELETE FROM auction_sale WHERE ended_at < ?", cutoff);
    }

    private int others(long cutoff) {
        int n = jdbc.update("""
                DELETE FROM auction_lowest_bin WHERE taken_at < ?
                AND (item_id, taken_at) NOT IN (SELECT item_id, max(taken_at) FROM auction_lowest_bin GROUP BY item_id)
                """, cutoff);
        // Keep the newest run of each collector: gap detection needs it.
        n += jdbc.update("""
                DELETE FROM collector_run WHERE started_at < ?
                AND id NOT IN (SELECT max(id) FROM collector_run GROUP BY collector)
                AND id NOT IN (SELECT max(id) FROM collector_run WHERE status = 'OK' GROUP BY collector)
                """, cutoff);
        return n;
    }
}
