-- Coflnet returns at most one page (1000) of recent sales. For a busy item that page covers less than the
-- sales window, so sales per day must be counted over the time the stored sales actually cover.
ALTER TABLE coflnet_backfill ADD COLUMN oldest_at INTEGER; -- end time of the oldest sale in the last fetch
