package com.hyflip.server.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code collector_run}: one row per collector run. */
@Repository
public class CollectorRunRepository {

    private final JdbcClient jdbc;

    public CollectorRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Run(String collector, long startedAt, Long finishedAt, String status, Integer rowsWritten,
                      String error) {
    }

    public long start(String collector, long now) {
        // RETURNING keeps the ID on the same statement; last_insert_rowid() is per pooled connection.
        return jdbc.sql("""
                INSERT INTO collector_run (collector, started_at, status) VALUES (:c, :now, 'RUNNING')
                RETURNING id
                """)
                .param("c", collector).param("now", now).query(Long.class).single();
    }

    public void finish(long id, long now, String status, Integer rows, String error) {
        jdbc.sql("""
                UPDATE collector_run SET finished_at = :now, status = :status, rows_written = :rows, error = :error
                WHERE id = :id
                """)
                .param("now", now).param("status", status).param("rows", rows).param("error", error)
                .param("id", id).update();
    }

    /** Runs left RUNNING by a crash or kill are marked FAILED on start. */
    public int abandonRunning(long now) {
        return jdbc.sql("""
                UPDATE collector_run SET status = 'FAILED', finished_at = :now, error = 'interrupted'
                WHERE status = 'RUNNING'
                """).param("now", now).update();
    }

    public Optional<Long> lastSuccessAt(String collector) {
        return jdbc.sql("SELECT max(finished_at) FROM collector_run WHERE collector = :c AND status = 'OK'")
                .param("c", collector).query(Long.class).optional();
    }

    /** A run without its error text, for the uptime strip. */
    public record Point(String collector, long startedAt, Long finishedAt, String status) {
    }

    /** Every run started at or after {@code from}, oldest first. */
    public List<Point> since(long from) {
        return jdbc.sql("""
                SELECT collector, started_at, finished_at, status FROM collector_run
                WHERE started_at >= :from ORDER BY started_at
                """)
                .param("from", from)
                .query((rs, n) -> new Point(rs.getString(1), rs.getLong(2), nullableLong(rs, 3), rs.getString(4)))
                .list();
    }

    /** Latest run of every collector. */
    public List<Run> latest() {
        return jdbc.sql("""
                SELECT r.collector, r.started_at, r.finished_at, r.status, r.rows_written, r.error
                FROM collector_run r
                JOIN (SELECT collector, max(id) AS id FROM collector_run GROUP BY collector) l ON l.id = r.id
                ORDER BY r.collector
                """)
                .query((rs, n) -> new Run(rs.getString(1), rs.getLong(2), nullableLong(rs, 3), rs.getString(4),
                        nullableInt(rs, 5), rs.getString(6)))
                .list();
    }

    // sqlite-jdbc rejects getObject(column, Long.class) on NULL, so read primitives and check wasNull().
    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, int column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
