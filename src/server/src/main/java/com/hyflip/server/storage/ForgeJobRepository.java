package com.hyflip.server.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code forge_job}: jobs the player started, for the Forge Tracker and expected vs realised. */
@Repository
public class ForgeJobRepository {

    private static final String COLUMNS = """
            id, slot, recipe_id, started_at, expected_end_at, base_seconds, expected_cost, expected_value,
            realised_value, realised_at, collected_at, source, sold_value""";

    private final JdbcClient jdbc;

    public ForgeJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param expectedCost  cost of the inputs at start
     * @param expectedValue net output value (after fees) at start
     * @param realisedValue net output value from prices at the end, null until settled
     * @param realisedAt    time of the prices used for {@code realisedValue}
     * @param collectedAt   when the player collected the output, null while it occupies the slot
     * @param source        MANUAL or PROFILE_API
     * @param soldValue     coins the player says they received for the output, null until entered
     */
    public record Job(long id, int slot, String recipeId, long startedAt, long expectedEndAt, Long baseSeconds,
                      Double expectedCost, Double expectedValue, Double realisedValue, Long realisedAt,
                      Long collectedAt, String source, Double soldValue) {
    }

    public long insert(int slot, String recipeId, long startedAt, long expectedEndAt, long baseSeconds,
                       Double expectedCost, Double expectedValue, String source) {
        return jdbc.sql("""
                INSERT INTO forge_job (slot, recipe_id, started_at, expected_end_at, base_seconds,
                                       expected_cost, expected_value, source)
                VALUES (:slot, :recipe, :start, :end, :base, :cost, :value, :source)
                RETURNING id
                """)
                .param("slot", slot).param("recipe", recipeId).param("start", startedAt).param("end", expectedEndAt)
                .param("base", baseSeconds).param("cost", expectedCost).param("value", expectedValue)
                .param("source", source)
                .query(Long.class).single();
    }

    public Optional<Job> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM forge_job WHERE id = :id")
                .param("id", id).query(ForgeJobRepository::map).optional();
    }

    /** Jobs not yet collected, by slot. */
    /** Highest slot any job was ever in, 0 if none: unlocked slots never go away, so this is a lower bound. */
    public int highestSlot() {
        return jdbc.sql("SELECT COALESCE(MAX(slot), 0) FROM forge_job").query(Integer.class).single();
    }

    public List<Job> open() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM forge_job WHERE collected_at IS NULL ORDER BY slot, started_at")
                .query(ForgeJobRepository::map).list();
    }

    /** Most recent jobs first. */
    public List<Job> recent(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM forge_job ORDER BY started_at DESC, id DESC LIMIT :limit")
                .param("limit", limit).query(ForgeJobRepository::map).list();
    }

    /** Finished jobs without a realised value. */
    public List<Job> unsettled(long now) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM forge_job WHERE realised_value IS NULL AND expected_end_at <= :now")
                .param("now", now).query(ForgeJobRepository::map).list();
    }

    public void settle(long id, double realisedValue, long realisedAt) {
        jdbc.sql("UPDATE forge_job SET realised_value = :v, realised_at = :at WHERE id = :id")
                .param("v", realisedValue).param("at", realisedAt).param("id", id).update();
    }

    public boolean collect(long id, long at) {
        return jdbc.sql("UPDATE forge_job SET collected_at = :at WHERE id = :id AND collected_at IS NULL")
                .param("at", at).param("id", id).update() > 0;
    }

    /** Stores (or clears, with null) what the player received for the output. */
    public boolean recordSale(long id, Double value) {
        return jdbc.sql("UPDATE forge_job SET sold_value = :v WHERE id = :id")
                .param("v", value).param("id", id).update() > 0;
    }

    /** Puts an open job in another slot with the given times, for example as the profile API reports it. */
    public void move(long id, int slot, long startedAt, long expectedEndAt, String source) {
        jdbc.sql("""
                UPDATE forge_job SET slot = :slot, started_at = :start, expected_end_at = :end, source = :source
                WHERE id = :id""")
                .param("slot", slot).param("start", startedAt).param("end", expectedEndAt).param("source", source)
                .param("id", id).update();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM forge_job WHERE id = :id").param("id", id).update() > 0;
    }

    private static Job map(ResultSet rs, int n) throws SQLException {
        return new Job(rs.getLong("id"), rs.getInt("slot"), rs.getString("recipe_id"), rs.getLong("started_at"),
                rs.getLong("expected_end_at"), Rows.nullableLong(rs, "base_seconds"),
                Rows.nullableDouble(rs, "expected_cost"), Rows.nullableDouble(rs, "expected_value"),
                Rows.nullableDouble(rs, "realised_value"), Rows.nullableLong(rs, "realised_at"),
                Rows.nullableLong(rs, "collected_at"), rs.getString("source"), Rows.nullableDouble(rs, "sold_value"));
    }
}
