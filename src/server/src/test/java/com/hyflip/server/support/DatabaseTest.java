package com.hyflip.server.support;

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that need the Spring context and a real SQLite database in a temporary directory.
 * Spring caches the context, so subclasses with the same configuration share one database: tests
 * must use their own item IDs or clean the tables they assert on.
 */
@SpringBootTest
@Import(FakeHypixelApi.Config.class)
public abstract class DatabaseTest {

    @TempDir
    protected static Path dataDir;

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hyflip.data-dir", dataDir::toString);
    }
}
