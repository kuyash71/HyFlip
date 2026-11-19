package com.hyflip.server.storage;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code resource_snapshot}: latest raw JSON of each Hypixel resource (election, collections). */
@Repository
public class ResourceRepository {

    private final JdbcClient jdbc;

    public ResourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String name, String body, long fetchedAt) {
        jdbc.sql("""
                INSERT INTO resource_snapshot (name, fetched_at, body) VALUES (:name, :at, :body)
                ON CONFLICT (name) DO UPDATE SET fetched_at = excluded.fetched_at, body = excluded.body
                """)
                .param("name", name).param("at", fetchedAt).param("body", body).update();
    }

    public Optional<String> body(String name) {
        return jdbc.sql("SELECT body FROM resource_snapshot WHERE name = :name")
                .param("name", name).query(String.class).optional();
    }
}
