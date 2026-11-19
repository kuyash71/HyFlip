package com.hyflip.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyNamesTest {

    @TempDir
    Path tmp;

    @Test
    void renamesTheOldDatabaseWithItsLog() throws Exception {
        Files.writeString(tmp.resolve("skypayna.db"), "db");
        Files.writeString(tmp.resolve("skypayna.db-wal"), "wal");
        LegacyNames.migrateDatabase(tmp);
        assertThat(tmp.resolve("hyflip.db")).hasContent("db");
        assertThat(tmp.resolve("hyflip.db-wal")).hasContent("wal");
        assertThat(tmp.resolve("skypayna.db")).doesNotExist();
    }

    @Test
    void neverReplacesANewDatabase() throws Exception {
        Files.writeString(tmp.resolve("skypayna.db"), "old");
        Files.writeString(tmp.resolve("hyflip.db"), "new");
        LegacyNames.migrateDatabase(tmp);
        assertThat(tmp.resolve("hyflip.db")).hasContent("new");
        assertThat(tmp.resolve("skypayna.db")).exists();
    }

    @Test
    void movesConfigSettingsToTheNewRoot() throws Exception {
        Path config = Files.writeString(tmp.resolve("config.yaml"), "# my settings\nskypayna:\n  player:\n    username: x\n");
        LegacyNames.migrateConfig(config);
        assertThat(Files.readString(config)).isEqualTo("# my settings\nhyflip:\n  player:\n    username: x\n");
        LegacyNames.migrateConfig(config);
        assertThat(Files.readString(config)).contains("hyflip:").doesNotContain("skypayna");
    }

    @Test
    void readsTheDataDirFromTheArguments() {
        assertThat(LegacyNames.dataDir(new String[] {"--server.port=1", "--hyflip.data-dir=/x/y"})).isEqualTo(Path.of("/x/y"));
        assertThat(LegacyNames.dataDir(new String[0])).isEqualTo(Path.of("./data"));
    }
}
