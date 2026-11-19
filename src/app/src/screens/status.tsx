/** Status: collector health with 24-hour uptime strips, API key, history, database and item mapping. */
import type { CollectorStatus, RunPoint, Status } from "../api/client";
import { ago, bytes, clockAt, duration, ErrorLine, useApi, useNow, usePolling, useToast } from "../ui/common";

export type Health = "OK" | "SLOW" | "FAILED" | "IDLE";

const INFO: Record<string, { name: string; what: string }> = {
  BAZAAR: { name: "Bazaar", what: "Prices and order books" },
  AUCTIONS_ENDED: { name: "Ended auctions", what: "Sales from the last minute" },
  AUCTIONS: { name: "Auction scan", what: "Lowest BIN of tracked items" },
  PROFILE: { name: "Profile", what: "Forge slots, HOTM, collections" },
  NEU: { name: "NEU recipes", what: "Recipes and item names" },
  RESOURCES: { name: "Game data", what: "Collections and the mayor election" },
  COFLNET: { name: "SkyCofl backfill", what: "Fills gaps while the app was closed" },
  RETENTION: { name: "Retention", what: "Removes old history" },
};

export const HEALTH_TEXT: Record<Health, string> = { OK: "Running", SLOW: "Late", FAILED: "Failing", IDLE: "Idle" };
const HEALTH_INFO: Record<Health, string> = {
  OK: "Running on time.",
  SLOW: "The last good run is older than usual. Data may be a little behind.",
  FAILED: "The last run failed. Flips that need this data may be stale.",
  IDLE: "Waiting: it has not run yet, or only runs when needed.",
};

/** Health of a collector from its last run and how long ago it last succeeded. */
export function health(c: CollectorStatus, now: number): Health {
  if (!c.lastRun) return "IDLE";
  // A run in progress (SkyCofl takes about 2 min) is working, not late.
  if (c.lastRun.status === "RUNNING") return "OK";
  if (c.lastRun.status === "FAILED" && c.consecutiveFailures > 0) return "FAILED";
  if (c.lastSuccessAt !== null && c.intervalSeconds > 0 && now - c.lastSuccessAt > Math.max(3 * c.intervalSeconds, 300) * 1000) return "SLOW";
  return "OK";
}

/** 48 half-hour cells over the last day: failed if every run failed, OK if any succeeded, idle if none ran. */
export function uptimeCells(runs: RunPoint[], now: number, cells = 48, spanMs = 86_400_000): Health[] {
  const size = spanMs / cells;
  const start = now - spanMs;
  const out: Health[] = Array<Health>(cells).fill("IDLE");
  for (const r of runs) {
    const i = Math.floor((r.startedAt - start) / size);
    if (i < 0 || i >= cells) continue;
    if (r.status === "OK") out[i] = "OK";
    else if (r.status === "FAILED" && out[i] !== "OK") out[i] = "FAILED";
  }
  return out;
}

export function StatusScreen() {
  const api = useApi();
  const toast = useToast();
  const now = useNow(5_000);
  const status = usePolling<Status>(() => api.status(), 15_000, [api], "status");
  const runs = usePolling(() => api.collectorRuns(24), 60_000, [api], "status:runs");
  if (status.error) return <div className="page"><ErrorLine text={`Status unavailable: ${status.error}`} onRetry={status.reload} /></div>;
  if (!status.data) return <div className="page muted">Loading…</div>;
  const s = status.data;
  const list = s.collectors.map((c) => ({ c, h: health(c, now) }));
  const failed = list.filter((x) => x.h === "FAILED");
  const slow = list.filter((x) => x.h === "SLOW");
  const name = (n: string) => INFO[n]?.name ?? n;
  const banner = failed.length > 0
    ? { cls: "down", title: "PARTIAL OUTAGE", sub: `${failed.map((x) => name(x.c.name)).join(", ")} failing · flips that need it may be stale` }
    : slow.length > 0
      ? { cls: "degraded", title: "DEGRADED PERFORMANCE", sub: `${slow.map((x) => name(x.c.name)).join(", ")} behind · data still arriving` }
      : { cls: "ok", title: "ALL SYSTEMS OPERATIONAL", sub: `${list.length} of ${list.length} collectors healthy · checked just now` };

  const runNow = async (n: string) => {
    try {
      await api.runCollector(n);
      toast(`${name(n)}: run queued`, "info");
      setTimeout(() => { status.reload(); runs.reload(); }, 2_500);
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error");
    }
  };

  const key = s.apiKey;
  const keyLeft = key.expiresAt ? (Date.parse(key.expiresAt) - now) / 1000 : null;
  const keyText = key.state === "MISSING" ? "Not set" : key.state === "EXPIRED" ? "Expired" : keyLeft !== null ? `${duration(Math.max(0, keyLeft))} left` : "Set";
  const keyShare = keyLeft !== null ? Math.max(0, Math.min(1, keyLeft / (3 * 86_400))) : key.state === "MISSING" || key.state === "EXPIRED" ? 0 : 1;
  const oldest = s.gaps.length > 0 ? Math.min(...s.gaps.map((g) => g.gapStart)) : null;
  const openGaps = s.gaps.filter((g) => !g.backfilled);
  const mapped = s.database.items > 0 ? 1 - s.unmapped.count / (s.database.items + s.unmapped.count) : 1;

  return (
    <div className="page status-page">
      <div className={`status-banner sb-${banner.cls}`} role="status">
        <span className="sb-icon" aria-hidden="true">{banner.cls === "ok" ? "✔" : "!"}</span>
        <div>
          <div className="sb-title">{banner.title}</div>
          <div className="sb-sub">{banner.sub}</div>
        </div>
        <button onClick={() => { status.reload(); runs.reload(); toast("Checked"); }}>Check now</button>
      </div>

      <div className="status-grid">
        <div className="card meter-card">
          <div className="muted small">API key</div>
          <div className={`meter-value ${key.state === "EXPIRED" || key.state === "MISSING" ? "loss" : key.state === "EXPIRING_SOON" ? "warn-text" : ""}`}>{keyText}</div>
          <div className="meter"><span style={{ width: `${Math.round(keyShare * 100)}%` }} /></div>
          <div className="muted small">Personal keys last 3 days · renew at developer.hypixel.net, then paste it in Settings</div>
        </div>
        <div className="card meter-card">
          <div className="muted small">History gaps</div>
          <div className="meter-value">{s.gaps.length === 0 ? "No gaps" : `${s.gaps.length} gap${s.gaps.length > 1 ? "s" : ""}`}</div>
          <div className="meter"><span style={{ width: `${s.gaps.length === 0 ? 100 : Math.round(((s.gaps.length - openGaps.length) / s.gaps.length) * 100)}%` }} /></div>
          <div className="muted small">
            {s.gaps.length === 0 ? "Collected without breaks" : `${s.gaps.length - openGaps.length} filled by SkyCofl${oldest ? ` · oldest ${clockAt(oldest)}` : ""}`}
          </div>
        </div>
        <div className="card meter-card">
          <div className="muted small">Database</div>
          <div className="meter-value">{bytes(s.database.sizeBytes)}</div>
          <div className="meter"><span style={{ width: `${Math.min(100, Math.round((s.database.sizeBytes / (1.5 * 1024 ** 3)) * 100))}%` }} /></div>
          <div className="muted small">
            {s.database.bazaarSnapshots.toLocaleString("en-US")} Bazaar snapshots · {s.database.auctionSales.toLocaleString("en-US")} sales
          </div>
        </div>
        <div className="card meter-card">
          <div className="muted small">Items</div>
          <div className="meter-value">{s.neu.loaded ? `${s.neu.items.toLocaleString("en-US")} items` : "NEU not loaded"}</div>
          <div className="meter"><span style={{ width: `${Math.round(mapped * 100)}%` }} /></div>
          <div className="muted small" data-tip={s.unmapped.sample.map((u) => `${u.source}: ${u.sourceId}`).join("\n") || undefined}>
            {s.unmapped.count === 0 ? "Every market ID is mapped" : `${s.unmapped.count} market IDs unmapped`}
            {s.neu.commit ? ` · NEU ${s.neu.commit.slice(0, 7)}` : ""}
          </div>
        </div>
      </div>

      <div className="section-head">
        <h3>Collectors</h3>
        <span className="uptime-legend muted small">
          Last 24 h <i className="u-OK" /> OK <i className="u-FAILED" /> Failed <i className="u-IDLE" /> No run
        </span>
      </div>
      <div className="collectors">
        {list.map(({ c, h }) => {
          const cells = uptimeCells(runs.data?.[c.name] ?? [], now);
          const ran = cells.filter((x) => x !== "IDLE");
          const okShare = ran.length > 0 ? ran.filter((x) => x === "OK").length / ran.length : null;
          const info = INFO[c.name] ?? { name: c.name, what: "" };
          const last = c.lastRun;
          const next = last && c.intervalSeconds > 0 ? (last.startedAt + c.intervalSeconds * 1000 - now) / 1000 : null;
          return (
            <div key={c.name} className={`collector c-${h}`}>
              <span className={`lamp lamp-${h}`} data-tip={HEALTH_TEXT[h]} />
              <div className="c-name">
                <div className="strong">{info.name}</div>
                <div className="muted small">{info.what}</div>
              </div>
              <div className="c-state">
                <span className={`state-chip s-${h}`} data-tip={HEALTH_INFO[h]}>{HEALTH_TEXT[h]}</span>
                <div className="muted small ellipsis" data-tip={last?.error ?? undefined}>
                  {last?.status === "FAILED" ? last.error ?? "failed" : last?.rowsWritten !== null && last?.rowsWritten !== undefined ? `${last.rowsWritten.toLocaleString("en-US")} rows` : last?.status === "RUNNING" ? "running…" : ""}
                </div>
              </div>
              <div className="c-uptime">
                <div className="uptime" aria-label={okShare === null ? "No runs in the last 24 hours" : `Last 24 hours: ${Math.round(okShare * 100)}% ok`}>
                  {cells.map((x, i) => <span key={i} className={`u-${x}`} data-tip={`${((48 - i) / 2).toFixed(1).replace(/\.0$/, "")} h ago: ${x === "IDLE" ? "no run" : HEALTH_TEXT[x]}`} />)}
                </div>
                <div className="muted small">{okShare === null ? "no runs yet" : `${Math.round(okShare * 1000) / 10}% ok`}</div>
              </div>
              <div className="c-times small">
                <div>last <b className="fg">{last ? ago(last.startedAt, now) : "never"}</b></div>
                <div className="muted">
                  {c.intervalSeconds > 0 ? `every ${duration(c.intervalSeconds)}` : "when needed"}
                  {next !== null ? ` · next ${next > 0 ? `in ${duration(next)}` : "now"}` : ""}
                </div>
              </div>
              <button className="ghost" onClick={() => void runNow(c.name)} data-tip="Run it once now, skipping the wait.">Run now</button>
            </div>
          );
        })}
      </div>

      {s.gaps.length > 0 && (
        <>
          <h3>History gaps</h3>
          <ul className="events">
            {s.gaps.slice(0, 12).map((g) => (
              <li key={`${g.collector}${g.gapStart}`}>
                <span className={`ev-dot ${g.backfilled ? "ev-ok" : "ev-warn"}`} />
                <span className="muted small ev-when">{clockAt(g.gapStart)}</span>
                <span>{name(g.collector)}: {duration((g.gapEnd - g.gapStart) / 1000)} without data{g.backfilled ? ", filled by SkyCofl" : ""}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
