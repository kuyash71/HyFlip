-- Auction prices from clean copies only. See docs/database.md and docs/calculation-engine.md.

-- 1 if the sold item had no value-adding upgrades (stars, recombobulator, enchantments, ...),
-- 0 if it had some, NULL for rows stored before this was recorded (treated as not clean).
ALTER TABLE auction_sale ADD COLUMN clean INTEGER;

-- From now on auction_lowest_bin holds the lowest BIN among clean listings, and listings counts
-- clean listings only. Older rows are replaced by the next scan.

-- Items whose sale history was last fetched from Coflnet, so backfill does not repeat itself.
CREATE TABLE coflnet_backfill (
    item_id    INTEGER PRIMARY KEY REFERENCES item (id),
    fetched_at INTEGER NOT NULL,
    sales      INTEGER NOT NULL      -- sales returned by the last fetch
) WITHOUT ROWID;

-- Coins the player says they actually received for a forge job's output, null until entered.
ALTER TABLE forge_job ADD COLUMN sold_value REAL;
