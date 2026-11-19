package com.hyflip.server.journal;

import com.hyflip.server.storage.Rows;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code trade}: rows of the trade journal. */
@Repository
public class TradeRepository {

    private static final String COLUMNS = """
            id, flip_type, opportunity_id, name, runs, started_at, expected_cost, expected_value, alerts,
            actual_cost, actual_revenue, finished_at, note""";

    private final JdbcClient jdbc;

    public TradeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param alerts JSON array of alert type names */
    public record Row(long id, String flipType, String opportunityId, String name, double runs, long startedAt,
                      double expectedCost, double expectedValue, String alerts, Double actualCost,
                      Double actualRevenue, Long finishedAt, String note) {
    }

    public long insert(String flipType, String opportunityId, String name, double runs, long startedAt,
                       double expectedCost, double expectedValue, String alerts, String note) {
        return jdbc.sql("""
                INSERT INTO trade (flip_type, opportunity_id, name, runs, started_at, expected_cost, expected_value,
                                   alerts, note)
                VALUES (:type, :opp, :name, :runs, :start, :cost, :value, :alerts, :note)
                RETURNING id""")
                .param("type", flipType).param("opp", opportunityId).param("name", name).param("runs", runs)
                .param("start", startedAt).param("cost", expectedCost).param("value", expectedValue)
                .param("alerts", alerts).param("note", note)
                .query(Long.class).single();
    }

    public Optional<Row> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trade WHERE id = :id").param("id", id)
                .query(TradeRepository::map).optional();
    }

    /** Most recent first; {@code type} null for all. */
    public List<Row> recent(String type, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trade WHERE (:type IS NULL OR flip_type = :type)"
                        + " ORDER BY started_at DESC, id DESC LIMIT :limit")
                .param("type", type).param("limit", limit).query(TradeRepository::map).list();
    }

    public List<Row> all() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trade").query(TradeRepository::map).list();
    }

    public boolean result(long id, Double actualCost, Double actualRevenue, Long finishedAt, String note) {
        return jdbc.sql("""
                UPDATE trade SET actual_cost = :cost, actual_revenue = :revenue, finished_at = :finished, note = :note
                WHERE id = :id""")
                .param("cost", actualCost).param("revenue", actualRevenue).param("finished", finishedAt)
                .param("note", note).param("id", id).update() > 0;
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM trade WHERE id = :id").param("id", id).update() > 0;
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("flip_type"), rs.getString("opportunity_id"),
                rs.getString("name"), rs.getDouble("runs"), rs.getLong("started_at"), rs.getDouble("expected_cost"),
                rs.getDouble("expected_value"), rs.getString("alerts"), Rows.nullableDouble(rs, "actual_cost"),
                Rows.nullableDouble(rs, "actual_revenue"), Rows.nullableLong(rs, "finished_at"), rs.getString("note"));
    }
}
