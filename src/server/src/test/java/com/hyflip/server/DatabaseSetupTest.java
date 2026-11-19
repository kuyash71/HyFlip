package com.hyflip.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class DatabaseSetupTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hyflip.data-dir", () -> dataDir.resolve("nested").toString());
    }

    @Autowired
    JdbcClient jdbc;

    @Test
    void createsDataDirectoryAndAppliesMigrations() {
        assertThat(dataDir.resolve("nested/hyflip.db")).exists();
        Integer tables = jdbc.sql("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'bazaar_snapshot'")
                .query(Integer.class).single();
        assertThat(tables).isEqualTo(1);
    }

    @Test
    void connectionPragmasAreApplied() {
        assertThat(jdbc.sql("PRAGMA journal_mode").query(String.class).single()).isEqualToIgnoringCase("wal");
        assertThat(jdbc.sql("PRAGMA foreign_keys").query(Integer.class).single()).isEqualTo(1);
    }
}
