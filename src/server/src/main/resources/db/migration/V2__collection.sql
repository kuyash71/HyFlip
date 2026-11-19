-- M1 data layer: resources, gaps, unmapped IDs, auction summaries, bookkeeping.

-- Latest raw JSON of each Hypixel resource (election, collections).
CREATE TABLE resource_snapshot (
    name       TEXT    PRIMARY KEY,
    fetched_at INTEGER NOT NULL,
    body       TEXT    NOT NULL
);

-- Source IDs whose canonical form is not a known NEU item; for review, nothing is dropped.
CREATE TABLE unmapped_id (
    source     TEXT    NOT NULL,
    source_id  TEXT    NOT NULL,
    first_seen INTEGER NOT NULL,
    last_seen  INTEGER NOT NULL,
    seen       INTEGER NOT NULL,
    PRIMARY KEY (source, source_id)
);

-- Periods without data because the app was closed.
CREATE TABLE collection_gap (
    id          INTEGER PRIMARY KEY,
    collector   TEXT    NOT NULL,
    gap_start   INTEGER NOT NULL,
    gap_end     INTEGER NOT NULL,
    detected_at INTEGER NOT NULL,
    backfilled  INTEGER NOT NULL DEFAULT 0
);

-- Hourly ended-auction summaries kept after full-resolution sales expire. Prices are per unit.
CREATE TABLE auction_hourly (
    hour_start     INTEGER NOT NULL,
    item_id        INTEGER NOT NULL REFERENCES item (id),
    sales          INTEGER NOT NULL,
    units          INTEGER NOT NULL,
    unit_price_avg REAL    NOT NULL,
    unit_price_min REAL    NOT NULL,
    unit_price_max REAL    NOT NULL,
    PRIMARY KEY (item_id, hour_start)
) WITHOUT ROWID;

-- Internal key-value bookkeeping (retention watermarks, ...).
CREATE TABLE app_state (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Time-range scans for retention.
CREATE INDEX bazaar_snapshot_time ON bazaar_snapshot (taken_at);
CREATE INDEX auction_sale_time ON auction_sale (ended_at);
CREATE INDEX auction_lowest_bin_time ON auction_lowest_bin (taken_at);
