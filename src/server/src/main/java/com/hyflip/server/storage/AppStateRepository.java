package com.hyflip.server.storage;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code app_state}: small key-value store for internal bookkeeping (watermarks, versions). */
@Repository
public class AppStateRepository {

    private final JdbcClient jdbc;

    public AppStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        return jdbc.sql("SELECT value FROM app_state WHERE key = :key").param("key", key)
                .query(String.class).optional();
    }

    public long getLong(String key, long fallback) {
        return get(key).map(Long::parseLong).orElse(fallback);
    }

    public void delete(String key) {
        jdbc.sql("DELETE FROM app_state WHERE key = :key").param("key", key).update();
    }

    public void put(String key, String value) {
        jdbc.sql("""
                INSERT INTO app_state (key, value) VALUES (:key, :value)
                ON CONFLICT (key) DO UPDATE SET value = excluded.value
                """).param("key", key).param("value", value).update();
    }
}
