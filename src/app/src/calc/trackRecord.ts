/** Share of the estimate flips with given alerts keep: the journal's results where there are enough. */
import type { TradeStats, TradeSummary } from "../api/client";
import { REALISATION } from "./advisor";

/** Fewer trades with a result than this and the default table is used. */
export const MIN_RESULTS = 5;

function measured(stats: TradeStats | undefined): number | null {
  if (!stats || stats.withResult < MIN_RESULTS || stats.accuracy === null) return null;
  return Math.max(0, Math.min(1.1, stats.accuracy));
}

/** The lowest share among the alerts; flips without alerts use the no-alert group. */
export function trackRecord(summary: TradeSummary | null): (alerts: string[]) => number {
  const none = measured(summary?.noAlerts) ?? REALISATION.NONE!;
  return (alerts) =>
    alerts.reduce((r, a) => {
      const m = measured(summary?.byAlert[a as keyof TradeSummary["byAlert"]]);
      return Math.min(r, m ?? REALISATION[a] ?? REALISATION.NONE!);
    }, none);
}
