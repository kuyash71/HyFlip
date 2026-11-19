-- Initial schema. See docs/database.md for the model and retention rules.
-- Timestamps are Unix epoch milliseconds (UTC). Prices are coins.

-- Canonical items after ID mapping. One row per item across all sources.
CREATE TABLE item (
    id            INTEGER PRIMARY KEY,
    canonical_id  TEXT    NOT NULL UNIQUE,  -- SkyPAYNA ID (Hypixel item ID where one exists)
    display_name  TEXT,
    npc_sell_price REAL,
    updated_at    INTEGER NOT NULL
);

-- Source-specific IDs that map to a canonical item (Bazaar product, NEU internal name, ...).
CREATE TABLE item_alias (
    source    TEXT    NOT NULL,             -- HYPIXEL_ITEM | BAZAAR | NEU | COFLNET
    source_id TEXT    NOT NULL,
    item_id   INTEGER NOT NULL REFERENCES item (id),
    PRIMARY KEY (source, source_id)
);

-- Bazaar quick_status per product per poll.
CREATE TABLE bazaar_snapshot (
    taken_at         INTEGER NOT NULL,      -- Hypixel lastUpdated
    item_id          INTEGER NOT NULL REFERENCES item (id),
    buy_price        REAL    NOT NULL,      -- insta-buy price (lowest sell offer)
    sell_price       REAL    NOT NULL,      -- insta-sell price (highest buy order)
    buy_volume       INTEGER NOT NULL,
    sell_volume      INTEGER NOT NULL,
    buy_moving_week  INTEGER NOT NULL,
    sell_moving_week INTEGER NOT NULL,
    buy_orders       INTEGER NOT NULL,
    sell_orders      INTEGER NOT NULL,
    source           TEXT    NOT NULL DEFAULT 'HYPIXEL', -- HYPIXEL | COFLNET (backfill)
    PRIMARY KEY (item_id, taken_at)
) WITHOUT ROWID;

-- Hourly Bazaar summaries kept after full-resolution data expires.
CREATE TABLE bazaar_hourly (
    hour_start     INTEGER NOT NULL,
    item_id        INTEGER NOT NULL REFERENCES item (id),
    buy_price_avg  REAL    NOT NULL,
    buy_price_min  REAL    NOT NULL,
    buy_price_max  REAL    NOT NULL,
    sell_price_avg REAL    NOT NULL,
    sell_price_min REAL    NOT NULL,
    sell_price_max REAL    NOT NULL,
    samples        INTEGER NOT NULL,
    PRIMARY KEY (item_id, hour_start)
) WITHOUT ROWID;

-- Ended auctions (real sale prices) from /v2/skyblock/auctions_ended.
CREATE TABLE auction_sale (
    auction_id TEXT    PRIMARY KEY,
    ended_at   INTEGER NOT NULL,
    item_id    INTEGER REFERENCES item (id), -- NULL until the item bytes are decoded and mapped
    price      REAL    NOT NULL,
    bin        INTEGER NOT NULL,             -- 1 if Buy It Now
    item_count INTEGER NOT NULL DEFAULT 1,
    source     TEXT    NOT NULL DEFAULT 'HYPIXEL'
);
CREATE INDEX auction_sale_item_time ON auction_sale (item_id, ended_at);

-- Lowest BIN of tracked items from active auctions.
CREATE TABLE auction_lowest_bin (
    taken_at INTEGER NOT NULL,
    item_id  INTEGER NOT NULL REFERENCES item (id),
    price    REAL    NOT NULL,
    listings INTEGER NOT NULL,
    PRIMARY KEY (item_id, taken_at)
) WITHOUT ROWID;

-- One row per collector run; used for status, error reporting and offline-gap detection.
CREATE TABLE collector_run (
    id          INTEGER PRIMARY KEY,
    collector   TEXT    NOT NULL,           -- BAZAAR | AUCTIONS | AUCTIONS_ENDED | RESOURCES | NEU | BACKFILL
    started_at  INTEGER NOT NULL,
    finished_at INTEGER,
    status      TEXT    NOT NULL,           -- RUNNING | OK | FAILED
    rows_written INTEGER,
    error       TEXT
);
CREATE INDEX collector_run_latest ON collector_run (collector, started_at);

-- Forge jobs the player started, for the Forge Tracker and expected-vs-realised profit.
CREATE TABLE forge_job (
    id               INTEGER PRIMARY KEY,
    slot             INTEGER NOT NULL,
    recipe_id        TEXT    NOT NULL,      -- NEU output internal name
    started_at       INTEGER NOT NULL,
    expected_end_at  INTEGER NOT NULL,
    expected_cost    REAL,
    expected_value   REAL,
    realised_value   REAL,                  -- filled from prices at finish
    source           TEXT    NOT NULL       -- PROFILE_API | MANUAL
);

-- Calibration samples from the forge speed wizard.
CREATE TABLE forge_calibration (
    id             INTEGER PRIMARY KEY,
    recipe_id      TEXT    NOT NULL,
    base_seconds   INTEGER NOT NULL,
    observed_seconds INTEGER NOT NULL,
    recorded_at    INTEGER NOT NULL
);
