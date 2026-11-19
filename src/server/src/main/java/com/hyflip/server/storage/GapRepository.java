package com.hyflip.server.storage;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code collection_gap}: periods with no data because the app was closed. */
@Repository
public class GapRepository {

    private final JdbcClient jdbc;

    public GapRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Gap(String collector, long gapStart, long gapEnd, boolean backfilled) {
    }

    public void record(String collector, long gapStart, long gapEnd, long now) {
        jdbc.sql("""
                INSERT INTO collection_gap (collector, gap_start, gap_end, detected_at)
                VALUES (:c, :start, :end, :now)
                """)
                .param("c", collector).param("start", gapStart).param("end", gapEnd).param("now", now).update();
    }

    /** End of the latest gap recorded for the collector, empty if none. */
    public java.util.Optional<Long> lastEnd(String collector) {
        return jdbc.sql("SELECT max(gap_end) FROM collection_gap WHERE collector = :c")
                .param("c", collector).query(Long.class).optional();
    }

    public List<Gap> recent(int limit) {
        return jdbc.sql("""
                SELECT collector, gap_start, gap_end, backfilled FROM collection_gap
                ORDER BY gap_end DESC LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, n) -> new Gap(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4) == 1))
                .list();
    }
}
