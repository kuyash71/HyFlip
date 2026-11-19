/** Journal: the trades the player recorded, how the estimates held, and a backtest of each flipper. */
import { useEffect, useState } from "react";
import type { BacktestResult, FlipType, Trade, TradeStats, TradeSummary } from "../api/client";
import {
  ALERT_LABEL, clockAt, coins, ErrorLine, exact, parseCoins, pct, Slot, Stat, useApi, usePolling, useStored, useToast,
} from "../ui/common";
import { PixelIcon } from "../ui/pixel";

const TYPES: { id: FlipType; label: string }[] = [
  { id: "forge", label: "Forge" },
  { id: "craft", label: "Craft" },
  { id: "npc", label: "NPC" },
  { id: "bazaar", label: "Bazaar" },
];

export function JournalScreen({ onSummary }: { onSummary?: (s: TradeSummary) => void }) {
  const api = useApi();
  const toast = useToast();
  const trades = usePolling(() => api.trades(undefined, 200), 0, [api]);
  const summary = usePolling(() => api.tradeSummary(), 0, [api]);
  const [filter, setFilter] = useState<"all" | "open" | "done">("all");
  const reload = () => {
    trades.reload();
    summary.reload();
  };
  useEffect(() => {
    if (summary.data) onSummary?.(summary.data);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [summary.data]);
  const act = async (action: () => Promise<unknown>, done?: string) => {
    try {
      await action();
      if (done) toast(done);
      reload();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error");
    }
  };
  const s = summary.data;
  const list = (trades.data ?? []).filter((t) => filter === "all" || (filter === "open" ? t.actualRevenue === null : t.actualRevenue !== null));

  return (
    <div className="page">
      {(trades.error || summary.error) && <ErrorLine text={trades.error ?? summary.error ?? ""} onRetry={reload} />}
      {s && <SummaryTiles stats={s.all} />}
      {s && s.all.withResult === 0 && (
        <p className="muted">
          No results yet. Add flips with “Add to journal” in a flipper, then type what you paid and received here.
          Forge Manager jobs with a sold value count too. Until then the Track record column uses default values.
        </p>
      )}
      {s && s.all.withResult > 0 && (
        <details className="card">
          <summary><b>By flipper and tag</b> <span className="muted small">which estimates hold, which do not</span></summary>
          <table className="flips compact">
            <thead>
              <tr>
                <th>Group</th>
                <th className="num">With result</th>
                <th className="num">Expected</th>
                <th className="num">Actual</th>
                <th className="num"><span data-tip="Actual ÷ expected profit. This feeds the Track record column.">Held</span></th>
                <th className="num">Profitable</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(s.byType).map(([k, v]) => <StatsRow key={k} label={k.charAt(0) + k.slice(1).toLowerCase()} stats={v} />)}
              <StatsRow label="No tags" stats={s.noAlerts} />
              {Object.entries(s.byAlert).map(([k, v]) => <StatsRow key={k} label={`With “${ALERT_LABEL[k] ?? k}”`} stats={v} />)}
            </tbody>
          </table>
        </details>
      )}

      <div className="section-head">
        <h3>Trades</h3>
        <span className="seg small-seg">
          {(["all", "open", "done"] as const).map((f) => (
            <button key={f} className={filter === f ? "on" : ""} onClick={() => setFilter(f)}>
              {f === "all" ? "All" : f === "open" ? "Waiting for a result" : "With a result"}
            </button>
          ))}
        </span>
      </div>
      {trades.data && trades.data.length === 0 && <p className="empty muted">No trades recorded yet.</p>}
      {list.length > 0 && (
        <table className="flips">
          <thead>
            <tr>
              <th /><th>Flip</th><th>Started</th>
              <th className="num">Expected</th>
              <th className="num"><span data-tip="Coins you really paid. Empty = the estimate.">Paid</span></th>
              <th className="num"><span data-tip="Coins you really got, after tax. Type it when you sold.">Received</span></th>
              <th className="num">Profit</th>
              <th className="num"><span data-tip="Actual profit − expected profit.">vs estimate</span></th>
              <th />
            </tr>
          </thead>
          <tbody>
            {list.map((t) => (
              <TradeRow key={t.id} trade={t}
                onSave={(cost, revenue) => void act(() => api.tradeResult(t.id, {
                  actualCost: cost, actualRevenue: revenue, finished: revenue !== null, note: t.note,
                }), "Saved")}
                onDelete={() => {
                  if (window.confirm(`Delete ${t.name} from the journal?`)) void act(() => api.deleteTrade(t.id), "Deleted");
                }} />
            ))}
          </tbody>
        </table>
      )}

      <Backtest />
    </div>
  );
}

function SummaryTiles({ stats }: { stats: TradeStats }) {
  return (
    <div className="stats wide">
      <Stat label="Trades with a result" value={`${stats.withResult} / ${stats.trades}`} />
      <Stat label="Actual profit" value={coins(stats.actualProfit)} tone={stats.actualProfit >= 0 ? "gain" : "loss"}
        tip={`Expected ${exact(stats.expectedProfit)} for the same trades.`} />
      <Stat label="Of the estimate made" value={pct(stats.accuracy)} tip="Actual ÷ expected profit, over trades with a result." />
      <Stat label="Made a profit" value={pct(stats.profitable)} />
    </div>
  );
}

function StatsRow({ label, stats }: { label: string; stats: TradeStats | undefined }) {
  if (!stats || stats.withResult === 0) return null;
  return (
    <tr>
      <td>{label}</td>
      <td className="num">{stats.withResult} / {stats.trades}</td>
      <td className="num">{coins(stats.expectedProfit)}</td>
      <td className={`num ${stats.actualProfit >= 0 ? "gain" : "loss"}`}>{coins(stats.actualProfit)}</td>
      <td className="num">{pct(stats.accuracy)}</td>
      <td className="num">{pct(stats.profitable)}</td>
    </tr>
  );
}

function TradeRow({ trade: t, onSave, onDelete }: {
  trade: Trade;
  onSave: (cost: number | null, revenue: number | null) => void;
  onDelete: () => void;
}) {
  const shown = (v: number | null) => (v === null ? "" : String(Math.round(v)));
  const [cost, setCost] = useState(shown(t.actualCost));
  const [revenue, setRevenue] = useState(shown(t.actualRevenue));
  useEffect(() => {
    setCost(shown(t.actualCost));
    setRevenue(shown(t.actualRevenue));
  }, [t.actualCost, t.actualRevenue]);
  const parse = (s: string) => (s.trim() === "" ? null : parseCoins(s));
  const c = parse(cost);
  const r = parse(revenue);
  const invalid = (s: string, v: number | null) => s.trim() !== "" && v === null;
  const save = () => {
    if (invalid(cost, c) || invalid(revenue, r)) return;
    if (cost !== shown(t.actualCost) || revenue !== shown(t.actualRevenue)) onSave(c, r);
  };
  const field = (value: string, set: (s: string) => void, v: number | null, label: string, placeholder: string) => (
    <input value={value} size={7} placeholder={placeholder} aria-label={`${label} for ${t.name}`}
      className={`num-input ${invalid(value, v) ? "bad" : ""}`} onChange={(e) => set(e.target.value)} onBlur={save}
      onKeyDown={(e) => { if (e.key === "Enter") save(); }} />
  );
  const icon = t.type === "FORGE" ? "forge" : t.type === "CRAFT" ? "craft" : t.type === "NPC" ? "npc" : "bazaar";
  return (
    <tr>
      <td><Slot item={t.opportunityId} size={36} name={t.name} /></td>
      <td>
        <span className="item-name">{t.name}</span>{" "}
        <span className="muted small"><PixelIcon name={icon} size={14} /> {t.runs !== 1 ? `× ${t.runs}` : ""}</span>
        {t.alerts && t.alerts.length > 0 && <div className="muted small">{t.alerts.map((a) => ALERT_LABEL[a] ?? a).join(", ")}</div>}
      </td>
      <td className="muted">{clockAt(t.startedAt)}</td>
      <td className="num">{coins(t.expectedProfit)}</td>
      <td className="num">{t.id < 0 ? coins(t.actualCost ?? t.expectedCost) : field(cost, setCost, c, "Paid", coins(t.expectedCost))}</td>
      <td className="num">{t.id < 0 ? (t.actualRevenue === null ? "—" : coins(t.actualRevenue)) : field(revenue, setRevenue, r, "Received", coins(t.expectedValue))}</td>
      <td className={`num ${(t.actualProfit ?? 0) >= 0 ? "gain" : "loss"}`}>{t.actualProfit === null ? "—" : coins(t.actualProfit)}</td>
      <td className={`num ${(t.difference ?? 0) >= 0 ? "gain" : "loss"}`}>{t.difference === null ? "—" : coins(t.difference)}</td>
      <td>{t.id > 0 && <button className="ghost small" data-tip="Delete this trade" aria-label={`Delete ${t.name}`} onClick={onDelete}>✕</button>}</td>
    </tr>
  );
}

/** Replays a flipper over the stored history and compares predicted with realised profit. */
function Backtest() {
  const api = useApi();
  const [type, setType] = useStored<FlipType>("backtest.type", "craft");
  const [days, setDays] = useStored("backtest.days", 1);
  const [horizon, setHorizon] = useStored("backtest.horizon", "1h");
  const [result, setResult] = useState<BacktestResult | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async () => {
    setRunning(true);
    setError(null);
    try {
      const to = Date.now() - (type === "forge" ? 0 : 3_600_000);
      const from = to - days * 86_400_000;
      setResult(await api.backtest(type, { from, to, step: `${Math.max(1, days)}h`, horizon, top: 20 }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRunning(false);
    }
  };
  const o = result?.overall;
  return (
    <section className="card backtest">
      <div className="section-head">
        <h3>Backtest</h3>
        <span className="muted small">How the flipper's past picks would have turned out</span>
      </div>
      <p className="muted small">
        Ranks the flips as the flipper would have at past moments, then sells each at the prices from later
        (forge: after the job). Buy orders are assumed to fill at once, so order flips look better than they are.
      </p>
      <div className="filters">
        <label className="field">Flipper
          <select value={type} onChange={(e) => setType(e.target.value as FlipType)}>
            {TYPES.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
          </select>
        </label>
        <label className="field">Over the last
          <select value={days} onChange={(e) => setDays(Number(e.target.value))}>
            <option value={1}>day</option>
            <option value={3}>3 days</option>
            <option value={7}>7 days</option>
          </select>
        </label>
        {type !== "forge" && (
          <label className="field">Sold after
            <select value={horizon} onChange={(e) => setHorizon(e.target.value)}>
              <option value="1h">1 h</option>
              <option value="6h">6 h</option>
              <option value="24h">24 h</option>
            </select>
          </label>
        )}
        <button className="primary" onClick={() => void run()} disabled={running}>{running ? "Running…" : "Run"}</button>
      </div>
      {error && <ErrorLine text={error} />}
      {result && o && (
        <>
          <p className="muted small">
            {result.moments} moments, top {result.top} each{result.skipped > 0 ? ` · ${result.skipped} skipped (no history or not sold yet)` : ""}
          </p>
          {o.flips === 0 ? (
            <p className="muted">No flip could be valued; the stored history may be too short.</p>
          ) : (
            <>
              <div className="stats wide">
                <Stat label="Of the predicted profit realised" value={pct(o.capture)} />
                <Stat label={`Of ${o.flips} flips made a profit`} value={pct(o.profitable)} />
                <Stat label="Ranking held (Spearman)" value={o.rankCorrelation === null ? "—" : o.rankCorrelation.toFixed(2)}
                  tip="1 = the best predicted flips were also the best real ones; 0 = no relation." />
              </div>
              {Object.keys(result.byAlert).length > 0 && (
                <table className="flips compact">
                  <thead>
                    <tr><th>Flips</th><th className="num">Count</th><th className="num">Realised</th><th className="num">Profitable</th></tr>
                  </thead>
                  <tbody>
                    <tr><td>Without tags</td><td className="num">{result.noAlerts.flips}</td><td className="num">{pct(result.noAlerts.capture)}</td><td className="num">{pct(result.noAlerts.profitable)}</td></tr>
                    {Object.entries(result.byAlert).map(([k, v]) => (
                      <tr key={k}>
                        <td>With “{ALERT_LABEL[k] ?? k}”</td>
                        <td className="num">{v.flips}</td>
                        <td className="num">{pct(v.capture)}</td>
                        <td className="num">{pct(v.profitable)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {result.shortfall.length > 0 && (
                <>
                  <h3>Furthest below the prediction</h3>
                  <table className="flips compact">
                    <thead>
                      <tr><th>Flip</th><th className="num">Times</th><th className="num">Predicted</th><th className="num">Realised</th></tr>
                    </thead>
                    <tbody>
                      {result.shortfall.map((l) => (
                        <tr key={l.id}>
                          <td>{l.name}</td>
                          <td className="num">{l.flips}</td>
                          <td className="num">{coins(l.meanPredicted)}</td>
                          <td className={`num ${l.meanRealised >= 0 ? "gain" : "loss"}`}>{coins(l.meanRealised)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </>
              )}
            </>
          )}
        </>
      )}
    </section>
  );
}
