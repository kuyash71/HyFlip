package com.hyflip.server.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyflip.server.support.DatabaseTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class RetentionServiceTest extends DatabaseTest {

    static final long HOUR = Duration.ofHours(1).toMillis();
    static final long DAY = Duration.ofDays(1).toMillis();

    @Autowired RetentionService retention;
    @Autowired BazaarRepository bazaar;
    @Autowired AuctionRepository auctions;
    @Autowired ItemRepository items;
    @Autowired JdbcClient jdbc;

    @Test
    void downsamplesOldDataKeepsLatestRowAndRunsIdempotently() {
        long now = 100 * DAY;
        long old = now - 10 * DAY; // older than the default 7 days, hour-aligned
        long a = items.ensureItem("RET_A");
        long b = items.ensureItem("RET_B");
        bazaar.insert(List.of(
                snap(old, a, 10), snap(old + 60_000, a, 20), snap(now - HOUR, a, 30), // A: two old, one recent
                snap(old, b, 5)));                                                      // B: one old, never changed
        auctions.insertSales(List.of(new AuctionRepository.Sale("x", old, a, 300, true, 3, "HYPIXEL")));

        retention.run(now);

        List<Long> aTimes = jdbc.sql("SELECT taken_at FROM bazaar_snapshot WHERE item_id = :i ORDER BY taken_at")
                .param("i", a).query(Long.class).list();
        assertThat(aTimes).containsExactly(now - HOUR);
        assertThat(jdbc.sql("SELECT count(*) FROM bazaar_snapshot WHERE item_id = :i").param("i", b)
                .query(Long.class).single()).as("latest row of an unchanged item is kept").isEqualTo(1);

        double avg = jdbc.sql("SELECT buy_price_avg FROM bazaar_hourly WHERE item_id = :i AND hour_start = :h")
                .param("i", a).param("h", old).query(Double.class).single();
        assertThat(avg).isEqualTo(15.0);
        double unit = jdbc.sql("SELECT unit_price_avg FROM auction_hourly WHERE item_id = :i")
                .param("i", a).query(Double.class).single();
        assertThat(unit).isEqualTo(100.0);
        assertThat(jdbc.sql("SELECT count(*) FROM auction_sale WHERE auction_id = 'x'").query(Long.class).single()).isZero();

        retention.run(now);
        assertThat(jdbc.sql("SELECT buy_price_avg FROM bazaar_hourly WHERE item_id = :i AND hour_start = :h")
                .param("i", b).param("h", old).query(Double.class).single())
                .as("kept row is not summarised twice").isEqualTo(5.0);
        assertThat(jdbc.sql("SELECT samples FROM bazaar_hourly WHERE item_id = :i AND hour_start = :h")
                .param("i", a).param("h", old).query(Long.class).single()).isEqualTo(2);
    }

    private static BazaarRepository.Snapshot snap(long at, long item, double buy) {
        return new BazaarRepository.Snapshot(at, item, buy, buy - 1, 1, 1, 1, 1, 1, 1, "HYPIXEL");
    }
}
