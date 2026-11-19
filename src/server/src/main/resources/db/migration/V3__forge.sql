-- Forge Tracker and calibration. See docs/forge.md and docs/database.md.

-- forge_job.expected_cost / expected_value: cost and net output value (after fees) at start.
-- realised_value: net output value from the last prices at or before expected_end_at.
ALTER TABLE forge_job ADD COLUMN base_seconds INTEGER;     -- NEU base duration at start
ALTER TABLE forge_job ADD COLUMN realised_at INTEGER;      -- time of the prices realised_value used
ALTER TABLE forge_job ADD COLUMN collected_at INTEGER;     -- set when the player collected the output
-- At most one uncollected job per slot.
CREATE UNIQUE INDEX forge_job_open_slot ON forge_job (slot) WHERE collected_at IS NULL;

-- Quick Forge + Molten Forge reduction in effect when the sample was measured, so samples stay
-- valid after the player's perks change.
ALTER TABLE forge_calibration ADD COLUMN known_reduction REAL NOT NULL DEFAULT 0;
