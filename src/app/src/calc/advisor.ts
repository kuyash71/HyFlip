/**
 * Flip Calc: splits a budget across forge, Bazaar, craft and NPC flips.
 *
 * Two measures drive every decision:
 *  1. profit per unit (after tax, scaled by how often flips with the same alerts kept their estimate), and
 *  2. the chance and time to turn the output back into coins before the horizon.
 *
 * Model per unit n of a flip (units are sold one after another, so the n-th unit is the hardest to sell):
 *   ready      = lead time (forge duration, craft time) + one step of the faster side of the pipeline
 *   rate       = the slower of our buy-order fill rate and our sell rate (units a second)
 *   sold(n)    = P(at least n sales happen in the time left) = P(Poisson(rate · (horizon − ready)) ≥ n)
 *   utility(n) = r·p·sold(n) + (1 − sold(n))·(r·p·δ − h·c)
 * where p is the unit profit, c the unit cost, r the realisation factor of the flip's alerts, δ the credit
 * for a sale after the horizon and h the price-risk haircut on unsold stock (both set by the risk level).
 *
 * Capital: forge jobs lock their cost for the whole job. Other flips recycle coins while the player is
 * online: with a cycle of `cycleSeconds` and `online` seconds online, each coin can be used online/cycle
 * times (Little's law), so a unit ties up c·cycle/online coins, but never less than one unit's price.
 *
 * Solver: units are grouped into lots (per flip, diminishing returns). Forge mixes are enumerated exactly
 * over the strongest forge recipes; for each mix the rest of the budget is filled greedily by utility per
 * coin. A local search then drops and swaps lots until nothing improves, and a final pass tops up partial
 * lots with the leftover coins.
 */

export type Method = "forge" | "bazaar" | "craft" | "npc";
export type Risk = "safe" | "balanced" | "bold";

export interface Candidate {
  id: string;
  method: Method;
  name: string;
  /** Coins to start one unit: one forge job, one craft, one item bought. */
  unitCost: number;
  /** Expected profit per unit after tax at today's prices. */
  unitProfit: number;
  /** Seconds before the output exists (forge duration, craft time). */
  leadSeconds: number;
  /** Units a second our buy orders fill; Infinity for instant buys and NPC shops. */
  buyRate: number;
  /** Units a second we can sell (our share of the market). */
  sellRate: number;
  /** Hard cap: NPC limit left today, liquidity copy limit. */
  maxUnits: number;
  /** One buy-and-sell cycle for one unit, in seconds; lets coins be reused while online. */
  cycleSeconds: number;
  /** Daily price volatility: standard deviation ÷ price. */
  volatility: number;
  alerts: string[];
  /** Share of the estimate flips like this keep, from the journal; the default table when absent. */
  realisation?: number;
  /** Why the profile cannot do it (HOTM, collection), if so. */
  locked?: string;
}

export interface AdvisorInput {
  budget: number;
  /** Coins should be back within this many seconds. */
  horizonSeconds: number;
  /** The player stays online this long to refill orders. */
  onlineSeconds: number;
  methods: Method[];
  freeSlots: number;
  risk: Risk;
  /**
   * Most one item may be worth in the plan (units × unit cost), as a share of the budget: spreads the plan over
   * more items. Counted at full price, not recycled coins, so it limits how many of one item are bought. 1 = no limit.
   */
  maxShare?: number;
}

/** Whether a spread limit is set. */
function spreading(input: AdvisorInput): boolean {
  return input.maxShare !== undefined && input.maxShare > 0 && input.maxShare < 1;
}

/** Most one item may be worth in the plan, in coins. */
export function flipLimit(input: AdvisorInput): number {
  const share = input.maxShare ?? 1;
  return input.budget * (share > 0 && share < 1 ? share : 1);
}

export interface Allocation {
  candidate: Candidate;
  units: number;
  /** Coins tied up at the peak. */
  capital: number;
  /** Profit expected back as coins by the horizon. */
  expectedProfit: number;
  /** Profit if every unit sells at today's price. */
  fullProfit: number;
  utility: number;
  /** Chance that every unit is sold by the horizon. */
  sellAllChance: number;
  /** About when the last unit sells, in seconds from now. */
  cashOutSeconds: number;
}

export interface Excluded {
  candidate: Candidate;
  reason: string;
}

export interface Plan {
  allocations: Allocation[];
  capital: number;
  left: number;
  expectedProfit: number;
  fullProfit: number;
  utility: number;
  slotsUsed: number;
  excluded: Excluded[];
}

// ───────────────────────────── Parameters ─────────────────────────────

/** Share of the estimate realised by flips with each alert (journal and backtest, 2026-11-18). */
export const REALISATION: Record<string, number> = {
  NONE: 0.92,
  RECENT_CHANGE: 0.4,
  MANIPULATION: 0.3,
  FEW_SALES: 0.7,
  THIN_BOOK: 0.8,
  WIDE_SPREAD: 0.85,
};

interface RiskProfile {
  /** Credit for profit that arrives after the horizon. */
  laterCredit: number;
  /** Haircut on the cost of unsold stock at 5% daily volatility. */
  haircut: number;
  exclude: string[];
}

export const RISK: Record<Risk, RiskProfile> = {
  safe: { laterCredit: 0.25, haircut: 0.08, exclude: ["MANIPULATION", "RECENT_CHANGE", "FEW_SALES"] },
  balanced: { laterCredit: 0.5, haircut: 0.04, exclude: ["MANIPULATION"] },
  bold: { laterCredit: 0.75, haircut: 0.015, exclude: [] },
};

const MAX_LOTS = 32;
const FORGE_POOL = 10;
const MAX_FORGE_MIXES = 25_000;

// ───────────────────────────── Probability ─────────────────────────────

const LANCZOS = [
  0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313, -176.61502916214059,
  12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
];

export function lnGamma(x: number): number {
  if (x < 0.5) return Math.log(Math.PI / Math.sin(Math.PI * x)) - lnGamma(1 - x);
  x -= 1;
  let a = LANCZOS[0]!;
  const t = x + 7.5;
  for (let i = 1; i < 9; i++) a += LANCZOS[i]! / (x + i);
  return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
}

function normalCdf(z: number): number {
  // Abramowitz–Stegun 7.1.26 on erf; error below 1.5e-7.
  const s = z < 0 ? -1 : 1;
  const x = Math.abs(z) / Math.SQRT2;
  const t = 1 / (1 + 0.3275911 * x);
  const y = 1 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
  return 0.5 * (1 + s * y);
}

/** Shape above which P(a, x) uses the Wilson–Hilferty approximation. */
export const WILSON_HILFERTY_FROM = 100;

/** Regularised lower incomplete gamma P(a, x). */
export function gammaP(a: number, x: number): number {
  if (x <= 0) return 0;
  if (a > WILSON_HILFERTY_FROM) {
    // Wilson–Hilferty: within 6e-5 of the exact value from a = 100 on, and far cheaper than the series,
    // which needs hundreds of terms near a ≈ x (84% of solve time on real lists before this cut).
    const z = (Math.cbrt(x / a) - (1 - 1 / (9 * a))) / Math.sqrt(1 / (9 * a));
    return normalCdf(z);
  }
  const front = Math.exp(-x + a * Math.log(x) - lnGamma(a));
  if (x < a + 1) {
    let ap = a;
    let del = 1 / a;
    let sum = del;
    for (let i = 0; i < 20_000; i++) {
      ap += 1;
      del *= x / ap;
      sum += del;
      if (Math.abs(del) < Math.abs(sum) * 1e-12) break;
    }
    return Math.min(1, sum * front);
  }
  const tiny = 1e-300;
  let b = x + 1 - a;
  let c = 1 / tiny;
  let d = 1 / b;
  let h = d;
  for (let i = 1; i < 20_000; i++) {
    const an = -i * (i - a);
    b += 2;
    d = an * d + b;
    if (Math.abs(d) < tiny) d = tiny;
    c = b + an / c;
    if (Math.abs(c) < tiny) c = tiny;
    d = 1 / d;
    const del = d * c;
    h *= del;
    if (Math.abs(del - 1) < 1e-12) break;
  }
  return Math.max(0, 1 - front * h);
}

/** P(N ≥ n) for N ~ Poisson(mean). */
export function poissonAtLeast(n: number, mean: number): number {
  if (n <= 0) return 1;
  if (mean <= 0) return 0;
  return gammaP(n, mean);
}

// ───────────────────────────── Unit model ─────────────────────────────

export function realisation(alerts: string[]): number {
  return alerts.reduce((r, a) => Math.min(r, REALISATION[a] ?? REALISATION.NONE!), REALISATION.NONE!);
}

interface Model {
  c: Candidate;
  r: number;
  /** Pipeline rate and the time before selling can finish. */
  rate: number;
  ready: number;
  /** Capital per unit after recycling (fraction of unitCost). */
  share: number;
  /** Units that sell while the player is online, so their coins can buy later units. */
  recycled: number;
  laterCredit: number;
  haircut: number;
  horizon: number;
}

function model(c: Candidate, input: AdvisorInput): Model {
  const risk = RISK[input.risk];
  const rate = Math.min(c.buyRate, c.sellRate);
  const fast = Math.max(c.buyRate, c.sellRate);
  const ready = c.leadSeconds + (Number.isFinite(fast) && fast > 0 ? 1 / fast : 0);
  const share = c.method === "forge" || input.onlineSeconds <= 0 ? 1 : Math.min(1, Math.max(c.cycleSeconds, 1) / input.onlineSeconds);
  const vol = Math.min(4, Math.max(0.5, c.volatility / 0.05));
  const recycled = c.method === "forge" ? 0 : rate * Math.max(0, input.onlineSeconds - ready);
  return { c, r: c.realisation ?? realisation(c.alerts), rate, ready, share, recycled, laterCredit: risk.laterCredit, haircut: risk.haircut * vol, horizon: input.horizonSeconds };
}

/** Chance the n-th unit is sold by the horizon. */
function soldChance(m: Model, n: number): number {
  const window = m.horizon - m.ready;
  if (window <= 0) return 0;
  return poissonAtLeast(n, m.rate * window);
}

/**
 * Worth of the n-th unit. A unit that misses the horizon still has value, but less the longer it would
 * wait: the late-sale credit fades over one horizon length and the price risk grows with √(days held).
 */
function unitUtility(m: Model, n: number): number {
  const s = soldChance(m, n);
  const p = m.r * m.c.unitProfit;
  const window = Math.max(0, m.horizon - m.ready);
  const extra = m.rate > 0 ? Math.max(0, n - m.rate * window) / m.rate + Math.max(0, m.ready - m.horizon) : Infinity;
  const credit = m.laterCredit * Math.exp(-extra / Math.max(m.horizon, 3600));
  const haircut = m.haircut * Math.sqrt(1 + Math.min(extra, 1e9) / 86_400);
  return p * s + (1 - s) * (p * credit - haircut * m.c.unitCost);
}

/**
 * Capital for the first `units` units: recycled coins, but never below one unit's price. Recycling needs
 * buyers: units beyond what sells while the player is online wait as stock and tie up their full price.
 */
function capitalFor(m: Model, units: number): number {
  if (units <= 0) return 0;
  return m.c.unitCost * Math.max(1, units * m.share, units - m.recycled);
}

/**
 * Sum of f(n) for n in [a, b]. Small ranges are summed exactly; larger ones by Simpson's rule on up to 24
 * pieces, since the sell chance drops like an S-curve and one Simpson step over it is far off.
 */
function sumOver(a: number, b: number, f: (n: number) => number): number {
  const count = b - a + 1;
  if (count <= 0) return 0;
  if (count <= 24) {
    let s = 0;
    for (let n = a; n <= b; n++) s += f(n);
    return s;
  }
  const pieces = 24;
  let s = 0;
  for (let k = 0; k < pieces; k++) {
    const lo = a + Math.floor((count * k) / pieces);
    const hi = a + Math.floor((count * (k + 1)) / pieces) - 1;
    const n = hi - lo + 1;
    s += (n * (f(lo) + 4 * f((lo + hi) / 2) + f(hi))) / 6;
  }
  return s;
}

/** Expected units sold out of `units` by the horizon: E[min(N, units)], exact for Poisson. */
function expectedSold(m: Model, units: number): number {
  const window = m.horizon - m.ready;
  if (window <= 0 || units <= 0) return 0;
  const mean = m.rate * window;
  return mean * (1 - poissonAtLeast(units - 1, mean)) + units * poissonAtLeast(units, mean);
}

// ───────────────────────────── Lots ─────────────────────────────

export interface Lot {
  cand: number;
  index: number;
  from: number;
  to: number;
  capital: number;
  utility: number;
  forge: boolean;
}

function buildLots(m: Model, ci: number, input: AdvisorInput): Lot[] {
  const c = m.c;
  const forge = c.method === "forge";
  let cap = Math.min(c.maxUnits, forge ? input.freeSlots : Infinity);
  // Units the budget could ever pay for.
  cap = Math.min(cap, input.budget < c.unitCost ? 0 : Math.floor(input.budget / (c.unitCost * m.share)));
  // Stock beyond what recycles is paid in full, so the budget also caps that part.
  cap = Math.min(cap, Math.floor(input.budget / c.unitCost + m.recycled));
  // Spread: units of one item, at full price, stay within its share of the budget.
  if (spreading(input)) cap = Math.min(cap, Math.floor(flipLimit(input) / c.unitCost + 1e-9));
  if (!(cap >= 1) || unitUtility(m, 1) <= 0) return [];
  // Last unit worth having: utility falls with n, so binary search the sign change.
  let lo = 1;
  let hi = Math.floor(Math.min(cap, 1e7));
  if (unitUtility(m, hi) <= 0) {
    while (hi - lo > 1) {
      const mid = Math.floor((lo + hi) / 2);
      if (unitUtility(m, mid) > 0) lo = mid;
      else hi = mid;
    }
    hi = lo;
  }
  const units = hi;
  const lots: Lot[] = [];
  // The first lot holds the units one unit's price can carry once coins recycle.
  const first = forge ? 1 : Math.min(units, Math.max(1, Math.floor(1 / m.share)));
  const rest = units - first;
  const size = forge ? 1 : Math.max(1, Math.ceil(rest / (MAX_LOTS - 1)));
  let from = 1;
  let to = first;
  let index = 0;
  while (from <= units) {
    const capital = capitalFor(m, to) - capitalFor(m, from - 1);
    lots.push({ cand: ci, index, from, to, capital, utility: sumOver(from, to, (n) => unitUtility(m, n)), forge });
    index++;
    from = to + 1;
    to = Math.min(units, from + size - 1);
  }
  return lots;
}

// ───────────────────────────── Solver ─────────────────────────────

interface State {
  taken: number[];
  capital: number;
  utility: number;
  slots: number;
}

function cloneState(s: State): State {
  return { taken: [...s.taken], capital: s.capital, utility: s.utility, slots: s.slots };
}

/** Lots in the order a greedy-by-ratio solver takes them, keeping each flip's lots in sequence. */
function greedyOrder(lots: Lot[][]): Lot[] {
  const out: Lot[] = [];
  const next = lots.map(() => 0);
  const ratio = (l: Lot) => l.utility / Math.max(l.capital, 1e-9);
  // Small lists: a scan per pick is fast enough and keeps ties deterministic.
  for (;;) {
    let best = -1;
    let bestRatio = -Infinity;
    for (let i = 0; i < lots.length; i++) {
      const l = lots[i]![next[i]!];
      if (!l || l.utility <= 0) continue;
      const r = ratio(l);
      if (r > bestRatio) {
        bestRatio = r;
        best = i;
      }
    }
    if (best < 0) return out;
    out.push(lots[best]![next[best]!]!);
    next[best]!++;
  }
}

/** Takes lots in `order` that fit the coins and slots left, in sequence per flip. */
function fill(state: State, order: Lot[], budget: number, slots: number): State {
  const s = cloneState(state);
  const blocked = new Set<number>();
  for (const l of order) {
    if (blocked.has(l.cand) || l.index !== s.taken[l.cand]) continue;
    if (s.capital + l.capital > budget + 1e-6 || (l.forge && s.slots >= slots)) {
      blocked.add(l.cand);
      continue;
    }
    s.taken[l.cand]!++;
    s.capital += l.capital;
    s.utility += l.utility;
    if (l.forge) s.slots++;
  }
  return s;
}

function prefix(lots: Lot[], count: number): { capital: number; utility: number } {
  let capital = 0;
  let utility = 0;
  for (let i = 0; i < count; i++) {
    capital += lots[i]!.capital;
    utility += lots[i]!.utility;
  }
  return { capital, utility };
}

/** Every forge mix (with repeats) over the pool that fits the slots and budget. */
function forgeMixes(pool: number[], lots: Lot[][], slots: number, budget: number): number[][] {
  const out: number[][] = [];
  const counts = pool.map(() => 0);
  const walk = (k: number, used: number, capital: number) => {
    if (out.length >= MAX_FORGE_MIXES) return;
    if (k === pool.length) {
      out.push([...counts]);
      return;
    }
    const ls = lots[pool[k]!]!;
    let cap = capital;
    for (let n = 0; n <= ls.length && used + n <= slots; n++) {
      if (n > 0) cap += ls[n - 1]!.capital;
      if (cap > budget + 1e-6) break;
      counts[k] = n;
      walk(k + 1, used + n, cap);
    }
    counts[k] = 0;
  };
  walk(0, 0, 0);
  return out;
}

export interface SolveOptions {
  /** Skip the local search (used by tests to measure what it adds). */
  noLocalSearch?: boolean;
  /** Skip the partial top-up so results stay on lot boundaries (used by the exact comparison). */
  noTopUp?: boolean;
}

export interface Prepared {
  models: Model[];
  lots: Lot[][];
  excluded: Excluded[];
}

export function prepare(candidates: Candidate[], input: AdvisorInput): Prepared {
  const excluded: Excluded[] = [];
  const models: Model[] = [];
  const risk = RISK[input.risk];
  for (const c of candidates) {
    const reason = !input.methods.includes(c.method)
      ? null
      : c.locked
        ? `Locked: ${c.locked}`
        : c.alerts.some((a) => risk.exclude.includes(a))
          ? `${input.risk.charAt(0).toUpperCase()}${input.risk.slice(1)} risk skips ${c.alerts.filter((a) => risk.exclude.includes(a)).join(", ")}`
          : c.unitProfit <= 0
            ? "No profit at today's prices"
            : c.unitCost > input.budget
              ? "One unit costs more than the budget"
              : spreading(input) && c.unitCost > flipLimit(input)
                ? "One unit is worth more than the spread allows per item"
                : c.method === "forge" && input.freeSlots <= 0
                  ? "No free forge slot"
                  : undefined;
    if (reason === null) continue;
    if (reason) {
      excluded.push({ candidate: c, reason });
      continue;
    }
    const m = model(c, input);
    if (unitUtility(m, 1) <= 0) {
      const window = input.horizonSeconds - m.ready;
      excluded.push({
        candidate: c,
        reason: window <= 0 ? "Not ready before the coins are needed" : "Too unlikely to sell in time for this risk level",
      });
      continue;
    }
    models.push(m);
  }
  const lots = models.map((m, i) => buildLots(m, i, input));
  return { models, lots, excluded };
}

export function solveLots(prep: Prepared, input: AdvisorInput, opts: SolveOptions = {}): State {
  const { lots } = prep;
  const n = lots.length;
  const empty: State = { taken: lots.map(() => 0), capital: 0, utility: 0, slots: 0 };
  const forgeIdx = lots.map((ls, i) => (ls[0]?.forge ? i : -1)).filter((i) => i >= 0);
  const otherLots = lots.map((ls) => (ls[0]?.forge ? [] : ls));
  const order = greedyOrder(otherLots);
  const allOrder = greedyOrder(lots);

  // Forge pool: the best recipes by utility and by utility per coin, so cheap and strong ones both enter.
  const byUtility = [...forgeIdx].sort((a, b) => lots[b]![0]!.utility - lots[a]![0]!.utility);
  const byRatio = [...forgeIdx].sort(
    (a, b) => lots[b]![0]!.utility / lots[b]![0]!.capital - lots[a]![0]!.utility / lots[a]![0]!.capital,
  );
  const pool = [...new Set([...byUtility.slice(0, FORGE_POOL / 2), ...byRatio.slice(0, FORGE_POOL / 2)])];

  let best = fill(empty, allOrder, input.budget, input.freeSlots);
  for (const mix of forgeMixes(pool, lots, input.freeSlots, input.budget)) {
    const s = cloneState(empty);
    mix.forEach((count, k) => {
      const ci = pool[k]!;
      const p = prefix(lots[ci]!, count);
      s.taken[ci] = count;
      s.capital += p.capital;
      s.utility += p.utility;
      s.slots += count;
    });
    const done = fill(s, order, input.budget, input.freeSlots);
    if (done.utility > best.utility + 1e-9) best = done;
  }

  if (!opts.noLocalSearch) best = improve(best, lots, allOrder, input, n);
  return best;
}

/** Drop or swap lots and refill while that raises utility. */
function improve(start: State, lots: Lot[][], order: Lot[], input: AdvisorInput, n: number): State {
  let best = start;
  const drop = (s: State, ci: number, count: number): State => {
    const t = cloneState(s);
    for (let k = 0; k < count && t.taken[ci]! > 0; k++) {
      const l = lots[ci]![t.taken[ci]! - 1]!;
      t.taken[ci]!--;
      t.capital -= l.capital;
      t.utility -= l.utility;
      if (l.forge) t.slots--;
    }
    return t;
  };
  const force = (s: State, ci: number): State | null => {
    const l = lots[ci]![s.taken[ci]!];
    if (!l || s.capital + l.capital > input.budget + 1e-6 || (l.forge && s.slots >= input.freeSlots)) return null;
    const t = cloneState(s);
    t.taken[ci]!++;
    t.capital += l.capital;
    t.utility += l.utility;
    if (l.forge) t.slots++;
    return t;
  };
  /** Make room for the next lot of `cj` by dropping other flips' last lots, lowest `rule` first, then add it. */
  const makeRoom = (s: State, cj: number, rule: (l: Lot) => number): State | null => {
    const want = lots[cj]![s.taken[cj]!];
    if (!want || want.capital > input.budget + 1e-6) return null;
    let t = cloneState(s);
    for (let guard = 0; guard < 200; guard++) {
      const added = force(t, cj);
      if (added) return added;
      const slotBound = want.forge && t.slots >= input.freeSlots;
      let worst = -1;
      let worstRatio = Infinity;
      for (let i = 0; i < n; i++) {
        if (i === cj || t.taken[i]! === 0) continue;
        const l = lots[i]![t.taken[i]! - 1]!;
        if (slotBound && !l.forge) continue;
        const r = rule(l);
        if (r < worstRatio) {
          worstRatio = r;
          worst = i;
        }
      }
      if (worst < 0) return null;
      t = drop(t, worst, 1);
    }
    return null;
  };
  for (let round = 0; round < 30; round++) {
    let improved = false;
    const held = [...Array(n).keys()].filter((i) => best.taken[i]! > 0);
    const wanted = [...Array(n).keys()].filter((i) => lots[i]![best.taken[i]!]);
    // Knapsack move: force a lot in (a big item greedy skipped), clearing the weakest lots for it.
    // Three ways to pick what goes: weakest utility per coin, least utility, or the least utility that frees the most coins.
    const rules: ((l: Lot) => number)[] = [
      (l) => l.utility / Math.max(l.capital, 1e-9),
      (l) => l.utility,
      (l) => l.utility - l.capital * 1e-9,
    ];
    for (const cj of wanted) {
      for (const rule of rules) {
        const room = makeRoom(best, cj, rule);
        if (!room) continue;
        const t = fill(room, order, input.budget, input.freeSlots);
        if (t.utility > best.utility + 1e-6) {
          best = t;
          improved = true;
        }
      }
    }
    for (const ci of held) {
      for (const amount of [1, best.taken[ci]!]) {
        const dropped = drop(best, ci, amount);
        // Refill as is, then with each other flip forced in first (a swap).
        const tries: State[] = [fill(dropped, order, input.budget, input.freeSlots)];
        for (const cj of wanted) {
          if (cj === ci) continue;
          const forced = force(dropped, cj);
          if (forced) tries.push(fill(forced, order, input.budget, input.freeSlots));
        }
        for (const t of tries) {
          if (t.utility > best.utility + 1e-6) {
            best = t;
            improved = true;
          }
        }
      }
    }
    if (!improved) break;
  }
  return best;
}

/** Turns a lot state into a plan, topping up partial lots with what is left. */
export function solve(candidates: Candidate[], input: AdvisorInput, opts: SolveOptions = {}): Plan {
  const prep = prepare(candidates, input);
  const state = solveLots(prep, input, opts);
  const units = prep.models.map((_, i) => {
    const ls = prep.lots[i]!;
    const t = state.taken[i]!;
    return t === 0 ? 0 : ls[t - 1]!.to;
  });
  let capital = state.capital;

  if (!opts.noTopUp) {
    // Spend the rest on partial lots, best utility per coin first, one unit at a time in chunks.
    for (let pass = 0; pass < 4; pass++) {
      let changed = false;
      const order = prep.models
        .map((m, i) => ({ m, i }))
        .filter(({ m, i }) => m.c.method !== "forge" && units[i]! > 0)
        .sort((a, b) => unitUtility(b.m, units[b.i]! + 1) / b.m.c.unitCost - unitUtility(a.m, units[a.i]! + 1) / a.m.c.unitCost);
      for (const { m, i } of order) {
        const cap = m.c.maxUnits;
        let add = 0;
        let lo = 0;
        let hi = Math.max(0, Math.min(cap - units[i]!, 1e7));
        // Largest add that fits the coins and keeps the last unit worth having.
        while (lo < hi) {
          const mid = Math.ceil((lo + hi) / 2);
          const extra = capitalFor(m, units[i]! + mid) - capitalFor(m, units[i]!);
          const fits = capital + extra <= input.budget + 1e-6
            && (!spreading(input) || (units[i]! + mid) * m.c.unitCost <= flipLimit(input) + 1e-6);
          if (fits && unitUtility(m, units[i]! + mid) > 0) lo = mid;
          else hi = mid - 1;
        }
        add = lo;
        if (add > 0) {
          capital += capitalFor(m, units[i]! + add) - capitalFor(m, units[i]!);
          units[i]! += add;
          changed = true;
        }
      }
      if (!changed) break;
    }
  }

  const allocations: Allocation[] = [];
  let slotsUsed = 0;
  prep.models.forEach((m, i) => {
    const u = units[i]!;
    if (u <= 0) return;
    const sold = expectedSold(m, u);
    allocations.push({
      candidate: m.c,
      units: u,
      capital: capitalFor(m, u),
      expectedProfit: m.r * m.c.unitProfit * sold,
      fullProfit: m.c.unitProfit * u,
      utility: sumOver(1, u, (n) => unitUtility(m, n)),
      sellAllChance: soldChance(m, u),
      cashOutSeconds: m.ready + (m.rate > 0 ? u / m.rate : Infinity),
    });
    if (m.c.method === "forge") slotsUsed += u;
  });
  allocations.sort((a, b) => b.expectedProfit - a.expectedProfit);
  const total = (f: (a: Allocation) => number) => allocations.reduce((s, a) => s + f(a), 0);
  return {
    allocations,
    capital: total((a) => a.capital),
    left: input.budget - total((a) => a.capital),
    expectedProfit: total((a) => a.expectedProfit),
    fullProfit: total((a) => a.fullProfit),
    utility: total((a) => a.utility),
    slotsUsed,
    excluded: prep.excluded,
  };
}

/** Exact optimum over lots by brute force, for tests on small cases. */
export function bruteForce(prep: Prepared, input: AdvisorInput): number {
  const { lots } = prep;
  let best = 0;
  const walk = (i: number, capital: number, slots: number, utility: number) => {
    if (i === lots.length) {
      best = Math.max(best, utility);
      return;
    }
    const ls = lots[i]!;
    let cap = capital;
    let sl = slots;
    let ut = utility;
    walk(i + 1, cap, sl, ut);
    for (const l of ls) {
      cap += l.capital;
      ut += l.utility;
      if (l.forge) sl++;
      if (cap > input.budget + 1e-6 || sl > input.freeSlots) break;
      walk(i + 1, cap, sl, ut);
    }
  };
  walk(0, 0, 0, 0);
  return best;
}
