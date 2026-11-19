/** Price history of one item: Bazaar insta-buy / insta-sell, or the Auction House lowest BIN / sale median. */
import { useMemo, useState } from "react";
import type { ItemHistory } from "../api/client";
import { coins, ErrorLine, useApi, usePolling, useStored } from "../ui/common";

const RANGES = [
  { id: "1d", ms: 86_400_000, bucket: "PT10M" },
  { id: "7d", ms: 7 * 86_400_000, bucket: "PT1H" },
  { id: "30d", ms: 30 * 86_400_000, bucket: "PT6H" },
] as const;
type Range = (typeof RANGES)[number]["id"];

interface Series {
  key: string;
  label: string;
  color: string;
  points: { t: number; v: number }[];
}

export function toSeries(h: ItemHistory): Series[] {
  if (h.bazaar.length > 0) {
    return [
      { key: "buy", label: "Insta-buy", color: "var(--series-1)", points: h.bazaar.filter((p) => p.buyPrice > 0).map((p) => ({ t: p.t, v: p.buyPrice })) },
      { key: "sell", label: "Insta-sell", color: "var(--series-2)", points: h.bazaar.filter((p) => p.sellPrice > 0).map((p) => ({ t: p.t, v: p.sellPrice })) },
    ].filter((s) => s.points.length > 0);
  }
  return [
    { key: "bin", label: "Lowest BIN", color: "var(--series-1)", points: h.lowestBin.map((p) => ({ t: p.t, v: p.price })) },
    { key: "sales", label: "Sale median", color: "var(--series-2)", points: h.sales.map((p) => ({ t: p.t, v: p.median })) },
  ].filter((s) => s.points.length > 0);
}

const W = 520;
const H = 150;
const PAD = { left: 48, right: 12, top: 10, bottom: 24 };

function shortTime(t: number, span: number): string {
  return span <= 2 * 86_400_000
    ? new Date(t).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
    : new Date(t).toLocaleDateString([], { day: "numeric", month: "short" });
}

export function PriceChart({ itemId }: { itemId: string }) {
  const api = useApi();
  const [range, setRange] = useStored<Range>("chart.range", "7d");
  const r = RANGES.find((x) => x.id === range) ?? RANGES[1];
  const history = usePolling(() => api.itemHistory(itemId, { from: Date.now() - r.ms, bucket: r.bucket }), 0, [api, itemId, r.id],
    `history:${itemId}:${r.id}`);
  const [hover, setHover] = useState<number | null>(null);
  const series = useMemo(() => (history.data ? toSeries(history.data) : []), [history.data]);

  const top = (
    <div className="chart-top">
      {series.map((s) => (
        <span key={s.key} className="legend"><i style={{ background: s.color }} /> {s.label}</span>
      ))}
      <span className="seg small-seg">
        {RANGES.map((x) => (
          <button key={x.id} className={range === x.id ? "on" : ""} onClick={() => setRange(x.id)}>{x.id}</button>
        ))}
      </span>
    </div>
  );
  if (history.error) return <div className="chart">{top}<ErrorLine text={history.error} onRetry={history.reload} /></div>;
  if (!history.data) return <div className="chart">{top}<div className="chart-empty muted">Loading…</div></div>;
  const all = series.flatMap((s) => s.points);
  if (all.length === 0) return <div className="chart">{top}<div className="chart-empty muted">No prices stored for this range yet.</div></div>;

  const t0 = Math.min(history.data.from, ...all.map((p) => p.t));
  const t1 = Math.max(history.data.to, ...all.map((p) => p.t));
  let lo = Math.min(...all.map((p) => p.v));
  let hi = Math.max(...all.map((p) => p.v));
  if (lo === hi) {
    lo *= 0.95;
    hi = hi * 1.05 || 1;
  }
  const x = (t: number) => PAD.left + ((t - t0) / (t1 - t0 || 1)) * (W - PAD.left - PAD.right);
  const y = (v: number) => PAD.top + (1 - (v - lo) / (hi - lo)) * (H - PAD.top - PAD.bottom);
  const line = (pts: { t: number; v: number }[]) => pts.map((p, i) => `${i ? "L" : "M"}${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`).join("");
  const buckets = [...new Set(all.map((p) => p.t))].sort((a, b) => a - b);
  let hoverT: number | null = null;
  if (hover !== null) for (const t of buckets) if (hoverT === null || Math.abs(t - hover) < Math.abs(hoverT - hover)) hoverT = t;

  return (
    <div className="chart">
      {top}
      <div className="chart-plot">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Price history of ${history.data.name}`}
          onPointerLeave={() => setHover(null)}
          onPointerMove={(e) => {
            const rect = e.currentTarget.getBoundingClientRect();
            const px = ((e.clientX - rect.left) / rect.width) * W;
            setHover(t0 + ((px - PAD.left) / (W - PAD.left - PAD.right)) * (t1 - t0));
          }}>
          {[lo, (lo + hi) / 2, hi].map((v, i) => (
            <g key={i}>
              <line x1={PAD.left} x2={W - PAD.right} y1={y(v)} y2={y(v)} className="grid-line" />
              <text x={PAD.left - 6} y={y(v) + 4} textAnchor="end" className="axis">{coins(v)}</text>
            </g>
          ))}
          <text x={PAD.left} y={H - 6} className="axis">{shortTime(t0, t1 - t0)}</text>
          <text x={W - PAD.right} y={H - 6} textAnchor="end" className="axis">now</text>
          {series.map((s) =>
            s.points.length > 1 ? (
              <path key={s.key} d={line(s.points)} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" />
            ) : (
              <circle key={s.key} cx={x(s.points[0]!.t)} cy={y(s.points[0]!.v)} r={4} fill={s.color} className="dot" />
            ),
          )}
          {hoverT !== null && (
            <g>
              <line x1={x(hoverT)} x2={x(hoverT)} y1={PAD.top} y2={H - PAD.bottom} className="cross" />
              {series.map((s) => {
                const p = s.points.find((q) => q.t === hoverT);
                return p ? <circle key={s.key} cx={x(p.t)} cy={y(p.v)} r={4} fill={s.color} className="dot" /> : null;
              })}
            </g>
          )}
        </svg>
        {hoverT !== null && (
          <div className="chart-tip" style={{ left: `${(x(hoverT) / W) * 100}%` }}>
            <div className="muted">{new Date(hoverT).toLocaleString([], { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" })}</div>
            {series.map((s) => {
              const p = s.points.find((q) => q.t === hoverT);
              return <div key={s.key}>{s.label} {p ? coins(p.v) : "—"}</div>;
            })}
          </div>
        )}
      </div>
    </div>
  );
}
