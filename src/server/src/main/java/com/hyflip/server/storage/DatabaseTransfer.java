package com.hyflip.server.storage;

import com.hyflip.server.config.HyFlipProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Export and import of market history as SQLite files, so a new install does not start empty.
 *
 * <p>An export is a copy of the database ({@code VACUUM INTO}) with personal and internal tables
 * emptied: settings, the API key, the linked profile, forge jobs and calibration never leave the
 * machine. An import merges another file's history by canonical item ID ({@code INSERT OR IGNORE}),
 * marking rows {@code source = IMPORT}, and refuses files from a newer app version.
 */
@Service
public class DatabaseTransfer {

    /** Tables emptied in an export: personal data and internal bookkeeping. */
    static final List<String> PRIVATE_TABLES = List.of("app_state", "forge_job", "forge_calibration", "trade", "collector_run",
            "collection_gap", "unmapped_id", "coflnet_backfill");

    /** History tables and their time column, for date ranges. */
    static final Map<String, String> HISTORY = new LinkedHashMap<>();

    static {
        HISTORY.put("bazaar_snapshot", "taken_at");
        HISTORY.put("bazaar_hourly", "hour_start");
        HISTORY.put("auction_sale", "ended_at");
        HISTORY.put("auction_lowest_bin", "taken_at");
        HISTORY.put("auction_hourly", "hour_start");
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final DataSource dataSource;
    private final Path exportDir;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    @Autowired
    public DatabaseTransfer(DataSource dataSource, HyFlipProperties properties) {
        this(dataSource, properties.dataDir().resolve("exports"), Clock.systemUTC());
    }

    DatabaseTransfer(DataSource dataSource, Path exportDir, Clock clock) {
        this.dataSource = dataSource;
        this.exportDir = exportDir;
        this.clock = clock;
    }

    /** A rejected transfer request; the API answers 400 (or 409 while another transfer runs). */
    public static class TransferException extends RuntimeException {
        private final boolean busy;

        public TransferException(String message, boolean busy) {
            super(message);
            this.busy = busy;
        }

        public boolean busy() {
            return busy;
        }
    }

    /** @param rows rows per history table in the file */
    public record ExportResult(String name, String path, long bytes, Map<String, Long> rows) {
    }

    /** @param rows new rows per table; rows already present are skipped */
    public record ImportResult(String path, int schemaVersion, Map<String, Long> rows) {
    }

    public Path exportDir() {
        return exportDir;
    }

    /**
     * Writes the history in {@code [from, to]} (epoch ms, either may be null) to a new file in the
     * export directory.
     */
    public ExportResult export(Long from, Long to) {
        if (from != null && to != null && from > to) {
            throw new TransferException("from must not be after to", false);
        }
        String stamp = "hyflip-history-" + STAMP.format(clock.instant());
        return exclusive(() -> {
            try {
                Files.createDirectories(exportDir);
            } catch (IOException e) {
                throw new TransferException("Cannot create " + exportDir + ": " + e.getMessage(), false);
            }
            String name = stamp + ".db";
            for (int n = 2; Files.exists(exportDir.resolve(name)); n++) {
                name = stamp + "-" + n + ".db";
            }
            Path target = exportDir.resolve(name).toAbsolutePath();
            try (Connection c = dataSource.getConnection(); PreparedStatement vacuum = c.prepareStatement("VACUUM INTO ?")) {
                vacuum.setString(1, target.toString());
                vacuum.execute();
            }
            Map<String, Long> rows = new LinkedHashMap<>();
            try (Connection out = DriverManager.getConnection("jdbc:sqlite:" + target); Statement st = out.createStatement()) {
                for (String t : PRIVATE_TABLES) {
                    st.executeUpdate("DELETE FROM " + t);
                }
                for (Map.Entry<String, String> h : HISTORY.entrySet()) {
                    if (from != null) {
                        st.executeUpdate("DELETE FROM " + h.getKey() + " WHERE " + h.getValue() + " < " + from);
                    }
                    if (to != null) {
                        st.executeUpdate("DELETE FROM " + h.getKey() + " WHERE " + h.getValue() + " > " + to);
                    }
                    rows.put(h.getKey(), count(st, h.getKey()));
                }
                st.execute("VACUUM");
            }
            return new ExportResult(name, target.toString(), Files.size(target), rows);
        });
    }

    /** Export files in the export directory, newest first. */
    public List<Path> exports() {
        if (!Files.isDirectory(exportDir)) {
            return List.of();
        }
        try (var files = Files.list(exportDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".db"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** An export file by name, only from the export directory. */
    public java.util.Optional<Path> exportFile(String name) {
        if (!name.matches("[A-Za-z0-9._-]+\\.db")) {
            return java.util.Optional.empty();
        }
        Path p = exportDir.resolve(name);
        return Files.isRegularFile(p) ? java.util.Optional.of(p) : java.util.Optional.empty();
    }

    /** Merges the history of another HyFlip database file into this one. */
    public ImportResult importFile(Path file) {
        Path source = file.toAbsolutePath();
        if (!Files.isRegularFile(source)) {
            throw new TransferException("No file at " + source, false);
        }
        return exclusive(() -> {
            try (Connection c = dataSource.getConnection()) {
                int ours = schemaVersion(c, "main");
                try (PreparedStatement attach = c.prepareStatement("ATTACH DATABASE ? AS imp")) {
                    attach.setString(1, source.toString());
                    attach.execute();
                }
                try {
                    int theirs;
                    try {
                        theirs = schemaVersion(c, "imp");
                    } catch (SQLException e) {
                        throw new TransferException("Not a HyFlip database: " + source.getFileName(), false);
                    }
                    if (theirs > ours) {
                        throw new TransferException("The file is from a newer HyFlip (schema " + theirs
                                + ", this app has " + ours + "); update the app first", false);
                    }
                    Map<String, Long> rows = merge(c);
                    return new ImportResult(source.toString(), theirs, rows);
                } finally {
                    try (Statement st = c.createStatement()) {
                        st.execute("DETACH DATABASE imp");
                    }
                }
            }
        });
    }

    private Map<String, Long> merge(Connection c) throws SQLException {
        Map<String, Long> rows = new LinkedHashMap<>();
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            rows.put("item", (long) st.executeUpdate("""
                    INSERT OR IGNORE INTO main.item (canonical_id, display_name, npc_sell_price, updated_at)
                    SELECT canonical_id, display_name, npc_sell_price, updated_at FROM imp.item"""));
            for (String table : HISTORY.keySet()) {
                Set<String> cols = commonColumns(c, table);
                if (!cols.contains("item_id")) {
                    continue;
                }
                List<String> select = new ArrayList<>();
                for (String col : cols) {
                    select.add(switch (col) {
                        case "item_id" -> "m.id";
                        case "source" -> "'IMPORT'";
                        default -> "s." + col;
                    });
                }
                rows.put(table, (long) st.executeUpdate("INSERT OR IGNORE INTO main." + table + " ("
                        + String.join(", ", cols) + ") SELECT " + String.join(", ", select) + " FROM imp." + table
                        + " s JOIN imp.item ii ON ii.id = s.item_id JOIN main.item m ON m.canonical_id = ii.canonical_id"));
            }
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
        return rows;
    }

    /** Columns of the table in both databases, so files from older versions import too. */
    private static Set<String> commonColumns(Connection c, String table) throws SQLException {
        Set<String> main = columns(c, "main", table);
        Set<String> imp = columns(c, "imp", table);
        main.retainAll(imp);
        return main;
    }

    private static Set<String> columns(Connection c, String schema, String table) throws SQLException {
        Set<String> cols = new LinkedHashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA " + schema + ".table_info(" + table + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        }
        return cols;
    }

    private static int schemaVersion(Connection c, String schema) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT max(CAST(version AS INTEGER)) FROM " + schema + ".flyway_schema_history WHERE success = 1")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static long count(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private interface Work<T> {
        T run() throws SQLException, IOException;
    }

    private <T> T exclusive(Work<T> work) {
        if (!lock.tryLock()) {
            throw new TransferException("Another export or import is running", true);
        }
        try {
            return work.run();
        } catch (SQLException | IOException e) {
            throw new TransferException("Transfer failed: " + e.getMessage(), false);
        } finally {
            lock.unlock();
        }
    }
}
