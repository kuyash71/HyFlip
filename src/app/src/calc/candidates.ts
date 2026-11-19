/** Turns flipper rows from the server into Flip Calc candidates. */
import type { FlipExtras, FlipType, Opportunity } from "../api/client";
import type { Candidate } from "./advisor";

const DAY = 86_400;
/** Seconds to buy the inputs and craft once, by hand. */
export const CRAFT_SECONDS = 30;
/** Seconds to buy from an NPC and sell once. */
export const NPC_CYCLE_SECONDS = 5;
/** Daily price swing assumed when no history is stored yet. */
export const DEFAULT_VOLATILITY = 0.05;

export interface CandidateContext {
  /** Share of the Bazaar volume the player can take (settings, bazaar.marketShare). */
  bazaarShare: number;
  /** Track record of flips with these alerts (journal, else the default table). */
  kept: (alerts: string[]) => number;
  /**
   * A linked profile answers the requirements, so an unconfirmed unlock is left out. Without one nearly every
   * forge recipe is unconfirmed (HOTM unknown), so those stay in.
   */
  strictUnlocks?: boolean;
}

/**
 * Units a second the player can sell one run's output at: their share of the Bazaar volume, the whole
 * Auction House volume of the item (BIN listings sell one by one), or no limit at an NPC.
 */
export function sellRate(o: Opportunity, ctx: CandidateContext): number {
  const perRun = Math.max(1, o.outputCount);
  if (o.sellVenue === "NPC") return Infinity;
  const volume = o.liquidity.dailyVolume;
  if (volume === null || volume <= 0) return 0;
  const share = o.sellVenue === "AUCTION" ? 1 : ctx.bazaarShare;
  return (volume * share) / perRun / DAY;
}

export function toCandidate(type: FlipType, o: Opportunity, x: FlipExtras | undefined, ctx: CandidateContext): Candidate {
  const alerts = [...new Set(o.alerts.map((a) => a.type))];
  const base = {
    id: `${type}:${o.id}`,
    method: type,
    name: o.name,
    unitCost: o.cost,
    unitProfit: o.netProfit,
    volatility: x?.volatility ?? DEFAULT_VOLATILITY,
    alerts,
    realisation: ctx.kept(alerts),
    // Unconfirmed unlocks (requirements the profile cannot answer) are left out too: a plan must be craftable.
    locked: o.unlock === "LOCKED"
      ? x?.requirements.join(", ") || "recipe not unlocked"
      : o.unlock === "UNKNOWN" && ctx.strictUnlocks
        ? `${x?.requirements.join(", ") || "requirements"} (cannot be checked; type collection tiers Hypixel does not share in Settings)`
        : undefined,
  };
  const sell = sellRate(o, ctx);
  // A unit's coins are back only once it sells: at most as often as the market takes one.
  const sellGap = sell > 0 && Number.isFinite(sell) ? 1 / sell : sell === 0 ? DAY : 0;
  switch (type) {
    case "forge":
      return {
        ...base,
        leadSeconds: o.durationSeconds + (o.fillSeconds ?? 0),
        buyRate: Infinity,
        sellRate: sell,
        // Slow sellers: one copy at a time, as the liquidity bars advise.
        maxUnits: o.liquidity.level === "LOW" || o.liquidity.level === "UNKNOWN" ? 1 : 2,
        cycleSeconds: 0,
      };
    case "craft":
      return {
        ...base,
        leadSeconds: CRAFT_SECONDS,
        buyRate: Infinity,
        sellRate: sell,
        maxUnits: o.liquidity.level === "LOW" ? 1 : Number.MAX_SAFE_INTEGER,
        cycleSeconds: Math.max(CRAFT_SECONDS + (o.fillSeconds ?? 0), sellGap),
      };
    case "npc": {
      const rate = o.runsPerDay !== null && o.runsPerDay > 0 && o.sellVenue !== "NPC" ? o.runsPerDay / DAY : sell;
      return {
        ...base,
        leadSeconds: 0,
        buyRate: Infinity,
        sellRate: rate,
        maxUnits: Math.max(0, Math.floor(o.runsPerDay ?? 0)),
        cycleSeconds: Math.max(NPC_CYCLE_SECONDS, rate > 0 && Number.isFinite(rate) ? 1 / rate : 0),
      };
    }
    case "bazaar": {
      const rate = (o.runsPerDay ?? 0) / DAY;
      return {
        ...base,
        leadSeconds: 0,
        buyRate: rate,
        sellRate: rate,
        maxUnits: Math.max(0, Math.floor(o.runsPerDay ?? 0)),
        cycleSeconds: Math.max(o.fillSeconds ?? 0, rate > 0 ? 1 / rate : DAY),
      };
    }
  }
}

/** Splits a candidate ID back into its flipper and opportunity ID. */
export function parseCandidateId(id: string): { type: FlipType; id: string } {
  const i = id.indexOf(":");
  return { type: id.slice(0, i) as FlipType, id: id.slice(i + 1) };
}
