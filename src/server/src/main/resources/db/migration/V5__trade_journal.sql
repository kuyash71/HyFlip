-- M6+: trade journal (flips the player did, with what they expected and what they got).

CREATE TABLE trade (
    id              INTEGER PRIMARY KEY,
    flip_type       TEXT    NOT NULL,   -- FORGE | CRAFT | NPC | BAZAAR
    opportunity_id  TEXT    NOT NULL,   -- ID in /flips/{type}/{id}
    name            TEXT    NOT NULL,
    runs            REAL    NOT NULL,   -- crafts, units or routes done
    started_at      INTEGER NOT NULL,
    expected_cost   REAL    NOT NULL,   -- all runs, at recording time
    expected_value  REAL    NOT NULL,   -- net of fees, all runs
    alerts          TEXT    NOT NULL,   -- JSON array of alert types on the opportunity then
    actual_cost     REAL,               -- what the player paid, null until entered
    actual_revenue  REAL,               -- coins received after fees, null until entered
    finished_at     INTEGER,
    note            TEXT
);

CREATE INDEX trade_started ON trade (started_at);
