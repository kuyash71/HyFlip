package com.hyflip.server.storage;

import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code item}, {@code item_alias} and {@code unmapped_id}. */
@Repository
public class ItemRepository {

    /** Tables whose rows reference {@code item.id}; used when two items are merged. */
    private static final List<String> ITEM_TABLES = List.of(
            "bazaar_snapshot", "bazaar_hourly", "auction_sale", "auction_lowest_bin", "auction_hourly");

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public ItemRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public record NewItem(String canonicalId, String displayName) {
    }

    public record Alias(String source, String sourceId, long itemId, String canonicalId) {
    }

    public record Unmapped(String source, String sourceId, long lastSeen, long seen) {
    }

    /** Returns the ID of the item, inserting it if missing. */
    public long ensureItem(String canonicalId) {
        jdbc.sql("INSERT OR IGNORE INTO item (canonical_id, updated_at) VALUES (:id, :now)")
                .param("id", canonicalId).param("now", System.currentTimeMillis()).update();
        return jdbc.sql("SELECT id FROM item WHERE canonical_id = :id")
                .param("id", canonicalId).query(Long.class).single();
    }

    /** Inserts missing items; sets the display name only where none is stored yet. */
    public int ensureItems(Collection<NewItem> newItems) {
        long now = System.currentTimeMillis();
        int[][] counts = template.batchUpdate("""
                INSERT INTO item (canonical_id, display_name, updated_at) VALUES (?, ?, ?)
                ON CONFLICT (canonical_id) DO UPDATE SET display_name = excluded.display_name
                WHERE item.display_name IS NULL
                """, newItems, 500, (ps, item) -> {
            ps.setString(1, item.canonicalId());
            ps.setString(2, item.displayName());
            ps.setLong(3, now);
        });
        return sum(counts);
    }

    public void updateMetadata(long itemId, String displayName, Double npcSellPrice) {
        jdbc.sql("""
                UPDATE item SET display_name = COALESCE(:name, display_name),
                                npc_sell_price = :npc, updated_at = :now
                WHERE id = :id
                """)
                .param("name", displayName).param("npc", npcSellPrice)
                .param("now", System.currentTimeMillis()).param("id", itemId).update();
    }

    public void upsertAlias(String source, String sourceId, long itemId) {
        jdbc.sql("""
                INSERT INTO item_alias (source, source_id, item_id) VALUES (:source, :sourceId, :itemId)
                ON CONFLICT (source, source_id) DO UPDATE SET item_id = excluded.item_id
                """)
                .param("source", source).param("sourceId", sourceId).param("itemId", itemId).update();
    }

    public List<Alias> aliases(String source) {
        return jdbc.sql("""
                SELECT a.source, a.source_id, a.item_id, i.canonical_id
                FROM item_alias a JOIN item i ON i.id = a.item_id WHERE a.source = :source
                """)
                .param("source", source)
                .query((rs, n) -> new Alias(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)))
                .list();
    }

    /** Moves all history of {@code from} to {@code to}; rows that would collide are dropped. */
    public void moveItem(long from, long to) {
        for (String table : ITEM_TABLES) {
            template.update("UPDATE OR IGNORE " + table + " SET item_id = ? WHERE item_id = ?", to, from);
            template.update("DELETE FROM " + table + " WHERE item_id = ?", from);
        }
    }

    public void deleteIfOrphan(long itemId) {
        jdbc.sql("""
                DELETE FROM item WHERE id = :id
                AND NOT EXISTS (SELECT 1 FROM item_alias WHERE item_id = :id)
                """).param("id", itemId).update();
    }

    public void recordUnmapped(String source, String sourceId) {
        long now = System.currentTimeMillis();
        jdbc.sql("""
                INSERT INTO unmapped_id (source, source_id, first_seen, last_seen, seen)
                VALUES (:source, :sourceId, :now, :now, 1)
                ON CONFLICT (source, source_id) DO UPDATE SET last_seen = :now, seen = seen + 1
                """)
                .param("source", source).param("sourceId", sourceId).param("now", now).update();
    }

    /** Removes unmapped entries whose alias now resolves to a known item. */
    public void deleteUnmappedKnown(Collection<String> knownCanonicalIds) {
        template.execute("CREATE TEMP TABLE IF NOT EXISTS known_id (id TEXT PRIMARY KEY)");
        template.update("DELETE FROM known_id");
        template.batchUpdate("INSERT OR IGNORE INTO known_id (id) VALUES (?)", knownCanonicalIds, 1000,
                (ps, id) -> ps.setString(1, id));
        template.update("""
                DELETE FROM unmapped_id WHERE EXISTS (
                    SELECT 1 FROM item_alias a JOIN item i ON i.id = a.item_id
                    JOIN known_id k ON k.id = i.canonical_id
                    WHERE a.source = unmapped_id.source AND a.source_id = unmapped_id.source_id)
                """);
        template.update("DELETE FROM known_id");
    }

    public long unmappedCount() {
        return jdbc.sql("SELECT count(*) FROM unmapped_id").query(Long.class).single();
    }

    public List<Unmapped> unmapped(int limit) {
        return jdbc.sql("""
                SELECT source, source_id, last_seen, seen FROM unmapped_id
                ORDER BY seen DESC, source_id LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, n) -> new Unmapped(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)))
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM item").query(Long.class).single();
    }

    static int sum(int[][] counts) {
        int total = 0;
        for (int[] batch : counts) {
            for (int c : batch) {
                total += Math.max(c, 0);
            }
        }
        return total;
    }
}
