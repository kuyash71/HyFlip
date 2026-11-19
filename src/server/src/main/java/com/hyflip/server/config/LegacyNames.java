package com.hyflip.server.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries data over from before the app was renamed from SkyPAYNA to HyFlip (2026-11): the database file
 * {@code skypayna.db} becomes {@code hyflip.db}, and a {@code config.yaml} whose settings sit under
 * {@code skypayna:} gets them under {@code hyflip:}. Runs before Spring starts, so the renamed files are the ones
 * it reads. Never overwrites a file of the new name.
 */
public final class LegacyNames {

    private static final Logger log = LoggerFactory.getLogger(LegacyNames.class);
    private static final String OLD = "skypayna";
    private static final Pattern OLD_ROOT = Pattern.compile("(?m)^" + OLD + ":");

    private LegacyNames() {
    }

    public static void migrate(String[] args) {
        try {
            migrateDatabase(dataDir(args));
            migrateConfig(Path.of(System.getenv().getOrDefault("HYFLIP_CONFIG", "./config.yaml")));
        } catch (IOException | RuntimeException e) {
            // Leave the old files alone; the app still starts, with fresh data.
            log.warn("Could not carry over data from the old app name: {}", e.toString());
        }
    }

    static Path dataDir(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--hyflip.data-dir=")) {
                return Path.of(arg.substring("--hyflip.data-dir=".length()));
            }
        }
        return Path.of("./data");
    }

    static void migrateDatabase(Path dataDir) throws IOException {
        Path db = dataDir.resolve("hyflip.db");
        Path old = dataDir.resolve(OLD + ".db");
        if (Files.exists(db) || !Files.exists(old)) {
            return;
        }
        // The write-ahead log holds recent writes: it moves with the database, under the matching name.
        for (String suffix : List.of("-wal", "-shm")) {
            Path part = dataDir.resolve(OLD + ".db" + suffix);
            if (Files.exists(part)) {
                Files.move(part, dataDir.resolve("hyflip.db" + suffix), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(old, db);
        log.info("Renamed {} to {}", old.getFileName(), db.getFileName());
    }

    static void migrateConfig(Path config) throws IOException {
        if (!Files.isRegularFile(config)) {
            return;
        }
        String text = Files.readString(config);
        Matcher m = OLD_ROOT.matcher(text);
        if (!m.find() || text.matches("(?s).*(?m:^hyflip:).*")) {
            return;
        }
        Files.writeString(config, m.replaceFirst("hyflip:"));
        log.info("Moved the settings in {} from '{}:' to 'hyflip:'", config.getFileName(), OLD);
    }
}
