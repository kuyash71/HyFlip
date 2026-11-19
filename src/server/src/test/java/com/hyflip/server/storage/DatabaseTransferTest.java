package com.hyflip.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyflip.server.support.DatabaseTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class DatabaseTransferTest extends DatabaseTest {

    @Autowired DatabaseTransfer transfer;
    @Autowired ItemRepository items;
    @Autowired BazaarRepository bazaar;
    @Autowired AppStateRepository state;
    @Autowired JdbcClient jdbc;

    @Test
    void exportsHistoryWithoutPersonalDataAndImportsItBack() throws Exception {
        long id = items.ensureItem("TRANSFER_TEST_ITEM");
        bazaar.insert(List.of(snapshot(1_000, id, 10), snapshot(2_000, id, 11), snapshot(3_000, id, 12)));
        state.put("hypixel.api-key", "secret");

        DatabaseTransfer.ExportResult export = transfer.export(1_500L, null);
        assertThat(Path.of(export.path())).exists();
        assertThat(export.rows().get("bazaar_snapshot")).isGreaterThanOrEqualTo(2);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + export.path());
             ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM app_state")) {
            assertThat(rs.getInt(1)).isZero();
        }
        assertThat(transfer.exportFile(export.name())).contains(Path.of(export.path()));
        assertThat(transfer.exportFile("../hyflip.db")).isEmpty();

        // Lose a row, then import the export: only the missing row comes back, marked IMPORT.
        jdbc.sql("DELETE FROM bazaar_snapshot WHERE item_id = :id AND taken_at = 3000").param("id", id).update();
        DatabaseTransfer.ImportResult result = transfer.importFile(Path.of(export.path()));
        assertThat(result.rows().get("bazaar_snapshot")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT source FROM bazaar_snapshot WHERE item_id = :id AND taken_at = 3000")
                .param("id", id).query(String.class).single()).isEqualTo("IMPORT");
        assertThat(jdbc.sql("SELECT count(*) FROM bazaar_snapshot WHERE item_id = :id AND taken_at = 1000")
                .param("id", id).query(Long.class).single()).isEqualTo(1);
        assertThat(state.get("hypixel.api-key")).contains("secret");
        state.delete("hypixel.api-key");
    }

    @Test
    void refusesFilesThatAreNotHyFlipDatabasesOrNewer() throws Exception {
        Path junk = Files.writeString(dataDir.resolve("junk.db"), "not sqlite");
        assertThatThrownBy(() -> transfer.importFile(junk)).isInstanceOf(DatabaseTransfer.TransferException.class);
        assertThatThrownBy(() -> transfer.importFile(dataDir.resolve("missing.db")))
                .hasMessageContaining("No file");

        DatabaseTransfer.ExportResult export = transfer.export(null, null);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + export.path())) {
            c.createStatement().executeUpdate("UPDATE flyway_schema_history SET version = '999'"
                    + " WHERE installed_rank = (SELECT max(installed_rank) FROM flyway_schema_history)");
        }
        assertThatThrownBy(() -> transfer.importFile(Path.of(export.path()))).hasMessageContaining("newer HyFlip");
    }

    private static BazaarRepository.Snapshot snapshot(long at, long id, double price) {
        return new BazaarRepository.Snapshot(at, id, price, price - 1, 10, 10, 70, 70, 5, 5, "HYPIXEL");
    }
}
