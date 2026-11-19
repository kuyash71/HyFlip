package com.hyflip.server.storage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Size of the database file (main file, excluding the WAL). */
@Repository
public class DatabaseInfo {

    private final JdbcClient jdbc;

    public DatabaseInfo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long sizeBytes() {
        long pages = jdbc.sql("PRAGMA page_count").query(Long.class).single();
        long pageSize = jdbc.sql("PRAGMA page_size").query(Long.class).single();
        return pages * pageSize;
    }
}
