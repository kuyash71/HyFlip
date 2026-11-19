package com.hyflip.server.storage;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code forge_calibration}: samples from the forge speed calibration wizard. */
@Repository
public class ForgeCalibrationRepository {

    private final JdbcClient jdbc;

    public ForgeCalibrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Sample(long id, String recipeId, long baseSeconds, double knownReduction, long observedSeconds,
                         long recordedAt) {
    }

    public long insert(String recipeId, long baseSeconds, double knownReduction, long observedSeconds,
                       long recordedAt) {
        return jdbc.sql("""
                INSERT INTO forge_calibration (recipe_id, base_seconds, known_reduction, observed_seconds, recorded_at)
                VALUES (:recipe, :base, :known, :observed, :at)
                RETURNING id
                """)
                .param("recipe", recipeId).param("base", baseSeconds).param("known", knownReduction)
                .param("observed", observedSeconds).param("at", recordedAt)
                .query(Long.class).single();
    }

    public List<Sample> all() {
        return jdbc.sql("""
                SELECT id, recipe_id, base_seconds, known_reduction, observed_seconds, recorded_at
                FROM forge_calibration ORDER BY recorded_at, id
                """)
                .query((rs, n) -> new Sample(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getDouble(4),
                        rs.getLong(5), rs.getLong(6)))
                .list();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM forge_calibration WHERE id = :id").param("id", id).update() > 0;
    }

    public int deleteAll() {
        return jdbc.sql("DELETE FROM forge_calibration").update();
    }
}
