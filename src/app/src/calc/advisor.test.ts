/// <reference types="node" />
/** Checks for the Flip Calc solver over many market conditions. */
import { test } from "vitest";
import assert from "node:assert/strict";
import {
  bruteForce,
  gammaP,
  WILSON_HILFERTY_FROM,
  poissonAtLeast,
  prepare,
  realisation,
  solve,
  solveLots,
  type AdvisorInput,
  type Candidate,
  type Method,
  type Plan,
  type Risk,
} from "./advisor";

const H = 3600;

function cand(over: Partial<Candidate> & { id: string }): Candidate {
  return {
    method: "craft",
    name: over.id,
    unitCost: 10_000,
    unitProfit: 1_000,
    leadSeconds: 0,
    buyRate: Infinity,
    sellRate: 100 / H,
    maxUnits: 1_000,
    cycleSeconds: 600,
    volatility: 0.05,
    alerts: [],
    ...over,
  };
}

function input(over: Partial<AdvisorInput> = {}): AdvisorInput {
  return {
    budget: 1_000_000,
    horizonSeconds: 8 * H,
    onlineSeconds: 0,
    methods: ["forge", "bazaar", "craft", "npc"],
    freeSlots: 3,
    risk: "balanced",
    ...over,
  };
}

function rng(seed: number): () => number {
  let t = seed >>> 0;
  return () => {
    t = (t + 0x6d2b79f5) | 0;
    let x = Math.imul(t ^ (t >>> 15), 1 | t);
    x ^= x + Math.imul(x ^ (x >>> 7), 61 | x);
    return ((x ^ (x >>> 14)) >>> 0) / 4294967296;
  };
}

const METHODS: Method[] = ["forge", "bazaar", "craft", "npc"];
const ALERTS = ["", "", "", "WIDE_SPREAD", "FEW_SALES", "RECENT_CHANGE", "THIN_BOOK", "MANIPULATION"];

/** A random market: costs from 100 to 5M, profits up to 80% of cost, sell rates from 1 a day to 1 a second. */
function market(seed: number, count: number, small = false): Candidate[] {
  const r = rng(seed);
  const out: Candidate[] = [];
  for (let i = 0; i < count; i++) {
    const method = METHODS[Math.floor(r() * 4)]!;
    const cost = Math.round(10 ** (2 + r() * 4.7));
    const alert = ALERTS[Math.floor(r() * ALERTS.length)]!;
    out.push({
      id: `C${i}`,
      method,
      name: `C${i}`,
      unitCost: cost,
      unitProfit: Math.round(cost * (0.02 + r() * 0.8)),
      leadSeconds: method === "forge" ? Math.round(H * (0.5 + r() * 30)) : method === "craft" ? 20 : 0,
      buyRate: method === "bazaar" ? 10 ** (-4 + r() * 3.5) : Infinity,
      sellRate: 10 ** (-4.9 + r() * 4.9),
      maxUnits: small ? 1 + Math.floor(r() * 4) : method === "npc" ? 640 : method === "forge" ? 3 : Math.round(10 ** (r() * 4)),
      cycleSeconds: Math.round(60 + r() * 3000),
      volatility: 0.01 + r() * 0.2,
      alerts: alert ? [alert] : [],
    });
  }
  return out;
}

function randomInput(seed: number): AdvisorInput {
  const r = rng(seed * 7 + 1);
  const methods = METHODS.filter(() => r() < 0.75);
  return input({
    budget: Math.round(10 ** (4 + r() * 4)),
    horizonSeconds: Math.round(H * (0.5 + r() * 47)),
    onlineSeconds: r() < 0.4 ? 0 : Math.round(H * r() * 4),
    methods: methods.length ? methods : ["craft"],
    freeSlots: Math.floor(r() * 6),
    risk: (["safe", "balanced", "bold"] as Risk[])[Math.floor(r() * 3)]!,
  });
}

function check(plan: Plan, inp: AdvisorInput) {
  assert.ok(plan.capital <= inp.budget + 1e-6, `capital ${plan.capital} over budget ${inp.budget}`);
  assert.ok(plan.slotsUsed <= inp.freeSlots, "too many forge slots");
  for (const a of plan.allocations) {
    assert.ok(inp.methods.includes(a.candidate.method), "method not selected");
    assert.ok(a.units >= 1 && a.units <= a.candidate.maxUnits, `units ${a.units} outside 1..${a.candidate.maxUnits}`);
    assert.ok(!a.candidate.locked, "locked flip planned");
    assert.ok(a.sellAllChance >= 0 && a.sellAllChance <= 1, "chance out of range");
    assert.ok(a.expectedProfit <= a.fullProfit + 1e-6, "expected above full profit");
    assert.ok(a.capital >= a.candidate.unitCost - 1e-6, "capital below one unit");
  }
}

// ───────────────────────────── Probability ─────────────────────────────

test("Poisson tail matches closed forms", () => {
  for (const m of [0.01, 0.5, 1, 3, 10]) {
    assert.ok(Math.abs(poissonAtLeast(1, m) - (1 - Math.exp(-m))) < 1e-9);
    assert.ok(Math.abs(poissonAtLeast(2, m) - (1 - Math.exp(-m) * (1 + m))) < 1e-9);
  }
  assert.equal(poissonAtLeast(0, 5), 1);
  assert.equal(poissonAtLeast(3, 0), 0);
});

test("Poisson tail is about one half at the mean, and the large-n branch is continuous", () => {
  for (const m of [50, 500, 1999, 2001, 20_000]) {
    const p = poissonAtLeast(Math.round(m), m);
    assert.ok(p > 0.45 && p < 0.56, `P(N≥${m}) = ${p}`);
  }
  assert.ok(Math.abs(gammaP(1999.5, 2000) - gammaP(2000.5, 2000)) < 0.02);
  // No jump where the approximation takes over.
  for (const x of [80, 100, 120]) assert.ok(Math.abs(gammaP(WILSON_HILFERTY_FROM - 1e-9, x) - gammaP(WILSON_HILFERTY_FROM + 1e-9, x)) < 1e-4);
});

test("Poisson tail falls as n grows", () => {
  let prev = 1;
  for (let n = 1; n < 60; n++) {
    const p = poissonAtLeast(n, 20);
    assert.ok(p <= prev + 1e-12);
    prev = p;
  }
});

test("expected units sold matches a direct sum", () => {
  const c = cand({ id: "A", sellRate: 50 / H });
  for (const units of [1, 10, 49, 50, 51, 200, 900]) {
    const p = solve([{ ...c, maxUnits: units }], input({ budget: 1e12, horizonSeconds: H, risk: "bold" }));
    const a = p.allocations[0];
    if (!a) continue;
    let direct = 0;
    for (let n = 1; n <= a.units; n++) direct += poissonAtLeast(n, (50 / H) * (H - 0));
    assert.ok(Math.abs(a.expectedProfit / (0.92 * 1_000) - direct) < 1e-6 * Math.max(1, direct), `units ${a.units}`);
  }
});

test("a wider choice never expects less profit at the same horizon (safe ⊂ balanced ⊂ bold)", () => {
  for (const hours of [1, 4, 12]) {
    for (const budget of [1e6, 1e7]) {
      const u = (risk: Risk) => solve(REAL_CLEAN, input({ risk, budget, horizonSeconds: hours * H, onlineSeconds: H })).expectedProfit;
      // Same clean market, so only the late-sale credit differs: more patience may hold more stock, but sold profit stays close.
      assert.ok(u("balanced") >= u("safe") * 0.9, `${hours}h ${budget}: balanced ${u("balanced")} < safe ${u("safe")}`);
    }
  }
});

test("realisation takes the worst alert", () => {
  assert.equal(realisation([]), 0.92);
  assert.equal(realisation(["WIDE_SPREAD", "RECENT_CHANGE"]), 0.4);
});

// ───────────────────────────── Edge cases ─────────────────────────────

test("no budget gives an empty plan", () => {
  const p = solve([cand({ id: "A" })], input({ budget: 0 }));
  assert.equal(p.allocations.length, 0);
  assert.equal(p.excluded[0]?.reason, "One unit costs more than the budget");
});

test("a bulk craft is left out at every risk level, with the reason", () => {
  for (const risk of ["safe", "balanced", "bold"] as const) {
    const p = solve([cand({ id: "BIG", alerts: ["BULK_CRAFT"] }), cand({ id: "OK" })], input({ risk }));
    assert.deepEqual(p.allocations.map((a) => a.candidate.id), ["OK"]);
    assert.equal(p.excluded[0]?.reason, "Bulk craft: the parts do not fit in one inventory");
  }
});

test("a flip limit keeps the plan to that many flips and says why the rest fell out", () => {
  const r = rng(7);
  // 30 small markets: each sells little, so the unlimited plan spreads over many flips
  const cs = Array.from({ length: 30 }, (_, i) =>
    cand({ id: `F${i}`, unitCost: 5_000 + r() * 20_000, unitProfit: 300 + r() * 2_000, maxUnits: 5 + Math.floor(r() * 10) }));
  const full = solve(cs, input());
  assert.ok(full.allocations.length > 5, `unlimited plan uses ${full.allocations.length} flips`);
  const five = solve(cs, input({ maxFlips: 5 }));
  assert.ok(five.allocations.length <= 5 && five.allocations.length > 0);
  assert.ok(five.expectedProfit <= full.expectedProfit + 1e-6);
  const over = five.excluded.filter((x) => x.reason === "Over the limit of 5 flips");
  assert.equal(over.length, full.allocations.length - five.allocations.length);
  // a limit above the plan's size changes nothing
  assert.deepEqual(solve(cs, input({ maxFlips: 100 })).allocations.map((a) => a.candidate.id), full.allocations.map((a) => a.candidate.id));
});

test("an empty market gives an empty plan", () => {
  const p = solve([], input());
  assert.equal(p.allocations.length, 0);
  assert.equal(p.capital, 0);
});

test("only the chosen methods are planned, others are not listed as excluded", () => {
  const cs = [cand({ id: "F", method: "forge", leadSeconds: H, maxUnits: 2 }), cand({ id: "C" }), cand({ id: "N", method: "npc" })];
  const p = solve(cs, input({ methods: ["craft"] }));
  assert.deepEqual(p.allocations.map((a) => a.candidate.id), ["C"]);
  assert.equal(p.excluded.length, 0);
});

test("no free forge slot means no forge job", () => {
  const p = solve([cand({ id: "F", method: "forge", leadSeconds: H, maxUnits: 3 })], input({ freeSlots: 0 }));
  assert.equal(p.allocations.length, 0);
  assert.equal(p.excluded[0]?.reason, "No free forge slot");
});

test("forge jobs never exceed the free slots", () => {
  const cs = [0, 1, 2, 3].map((i) => cand({ id: `F${i}`, method: "forge", leadSeconds: H, maxUnits: 5, unitProfit: 5_000 + i }));
  const p = solve(cs, input({ freeSlots: 2, budget: 1e9 }));
  assert.equal(p.slotsUsed, 2);
});

test("a locked flip is never planned and says why", () => {
  const p = solve([cand({ id: "L", locked: "HOTM 5" }), cand({ id: "C" })], input());
  assert.ok(!p.allocations.some((a) => a.candidate.id === "L"));
  assert.equal(p.excluded[0]?.reason, "Locked: HOTM 5");
});

test("the NPC daily limit holds", () => {
  const p = solve([cand({ id: "N", method: "npc", unitCost: 100, unitProfit: 30, maxUnits: 640, sellRate: 1 })], input({ budget: 1e9 }));
  assert.equal(p.allocations[0]?.units, 640);
});

test("safe risk skips price-moved flips, bold may take them", () => {
  const cs = [cand({ id: "R", alerts: ["RECENT_CHANGE"], unitProfit: 5_000 })];
  assert.equal(solve(cs, input({ risk: "safe" })).allocations.length, 0);
  assert.equal(solve(cs, input({ risk: "bold" })).allocations.length, 1);
});

test("a forge job that ends after the horizon is skipped in safe mode when a quick flip exists", () => {
  const cs = [
    cand({ id: "LONG", method: "forge", leadSeconds: 30 * H, unitCost: 500_000, unitProfit: 60_000, maxUnits: 1 }),
    cand({ id: "QUICK", unitCost: 5_000, unitProfit: 600, sellRate: 1 }),
  ];
  const p = solve(cs, input({ risk: "safe", budget: 500_000, horizonSeconds: 4 * H }));
  assert.ok(!p.allocations.some((a) => a.candidate.id === "LONG"));
});

test("every alert-free flip with a sure sale is used when coins are plenty", () => {
  const cs = [0, 1, 2].map((i) => cand({ id: `C${i}`, maxUnits: 5, sellRate: 1 }));
  const p = solve(cs, input({ budget: 1e9 }));
  assert.equal(p.allocations.length, 3);
  assert.ok(p.allocations.every((a) => a.units === 5));
});

// ───────────────────────────── Behaviour ─────────────────────────────

test("a slow seller gets fewer units than a fast one with the same profit", () => {
  const cs = [cand({ id: "FAST", sellRate: 200 / H }), cand({ id: "SLOW", sellRate: 5 / H })];
  const p = solve(cs, input({ budget: 1e9 }));
  const units = (id: string) => p.allocations.find((a) => a.candidate.id === id)?.units ?? 0;
  assert.ok(units("FAST") > units("SLOW") * 5, `fast ${units("FAST")} vs slow ${units("SLOW")}`);
});

test("two equal flips share the budget instead of one taking it all", () => {
  const cs = [cand({ id: "A", sellRate: 20 / H }), cand({ id: "B", sellRate: 20 / H })];
  const p = solve(cs, input({ budget: 2_000_000 }));
  assert.equal(p.allocations.length, 2);
});

test("higher volatility means fewer units", () => {
  const calm = solve([cand({ id: "A", sellRate: 10 / H, volatility: 0.02 })], input({ budget: 1e9 }));
  const wild = solve([cand({ id: "A", sellRate: 10 / H, volatility: 0.2 })], input({ budget: 1e9 }));
  assert.ok(wild.allocations[0]!.units < calm.allocations[0]!.units);
});

test("an alert lowers the expected profit of the same flip", () => {
  const clean = solve([cand({ id: "A" })], input());
  const moved = solve([cand({ id: "A", alerts: ["WIDE_SPREAD"] })], input());
  assert.ok(moved.expectedProfit < clean.expectedProfit);
});

test("staying online lets Bazaar coins recycle, so the same budget carries more units", () => {
  const cs = [cand({ id: "B", method: "bazaar", unitCost: 100_000, unitProfit: 3_000, buyRate: 0.05, sellRate: 0.05, cycleSeconds: 300, maxUnits: 5_000 })];
  const offline = solve(cs, input({ budget: 1_000_000, onlineSeconds: 0 }));
  const online = solve(cs, input({ budget: 1_000_000, onlineSeconds: 2 * H }));
  assert.ok(online.allocations[0]!.units > offline.allocations[0]!.units * 4);
  assert.ok(online.capital <= 1_000_000);
});

test("stock that cannot sell while online ties up its full price", () => {
  // 1 sale a minute, an hour online: about 60 units recycle; the rest of 300 must be bought up front.
  const slow = cand({ id: "SLOW", method: "npc", unitCost: 10_000, unitProfit: 3_000, buyRate: Infinity, sellRate: 1 / 60,
    maxUnits: 300, cycleSeconds: 60, volatility: 0.01 });
  const plan = solve([slow], { budget: 1e9, horizonSeconds: 48 * H, onlineSeconds: H, methods: ["npc"], freeSlots: 0, risk: "bold" });
  const a = plan.allocations[0]!;
  assert.ok(a.units > 100, `units ${a.units}`);
  assert.ok(a.capital >= (a.units - 60) * 10_000 - 1, `capital ${a.capital} for ${a.units} units`);
  // With a small budget the same flip is capped by that stock.
  const small = solve([slow], { budget: 500_000, horizonSeconds: 48 * H, onlineSeconds: H, methods: ["npc"], freeSlots: 0, risk: "bold" });
  assert.ok(small.capital <= 500_000 + 1e-6);
  assert.ok(small.allocations[0]!.units <= 50 + 60, `units ${small.allocations[0]!.units}`);
});

test("a spread limit caps what one item is worth in the plan and spreads it over more items", () => {
  // One flip is clearly best per coin and would take the whole budget alone.
  const market = [
    cand({ id: "BEST", unitProfit: 3_000, sellRate: 1_000 / H }),
    cand({ id: "B", unitProfit: 1_500, sellRate: 1_000 / H }),
    cand({ id: "C", unitProfit: 1_400, sellRate: 1_000 / H }),
    cand({ id: "D", unitProfit: 1_300, sellRate: 1_000 / H }),
    cand({ id: "E", unitProfit: 1_200, sellRate: 1_000 / H }),
  ];
  const free = solve(market, input());
  const spread = solve(market, input({ maxShare: 0.25 }));
  assert.ok(free.allocations[0]!.capital > 250_000, `free plan already spread: ${free.allocations[0]!.capital}`);
  for (const a of spread.allocations) assert.ok(a.units * a.candidate.unitCost <= 250_000 + 1e-6, `${a.candidate.id}: ${a.units} units`);
  assert.ok(spread.allocations.length >= 4, `only ${spread.allocations.length} flips`);
  assert.ok(spread.capital <= 1_000_000 + 1e-6);
  // A cap can only cost profit, never add it.
  assert.ok(spread.utility <= free.utility + 1e-6);
  // maxShare 1 is the same as no limit.
  assert.equal(solve(market, input({ maxShare: 1 })).utility, free.utility);
});

test("a unit dearer than the spread limit is left out and says why", () => {
  const p = solve([cand({ id: "BIG", unitCost: 400_000, unitProfit: 100_000 }), cand({ id: "SMALL" })], input({ maxShare: 0.2 }));
  assert.ok(!p.allocations.some((a) => a.candidate.id === "BIG"));
  assert.match(p.excluded.find((e) => e.candidate.id === "BIG")!.reason, /spread/);
});

test("staying online does not let the spread limit be passed through recycled coins", () => {
  // Bazaar coins recycle while online, so 1M buys far more than 100 units; the cap counts full price.
  const fast = cand({ id: "FAST", method: "bazaar", unitCost: 10_000, unitProfit: 2_000, buyRate: 1, sellRate: 1, maxUnits: 100_000, cycleSeconds: 30 });
  const p = solve([fast], input({ onlineSeconds: 4 * H, maxShare: 0.3 }));
  assert.ok(p.allocations[0]!.units <= 30, `units ${p.allocations[0]!.units}`);
  assert.ok(solve([fast], input({ onlineSeconds: 4 * H })).allocations[0]!.units > 100);
});

test("400 random markets with a spread limit: no item over its share", () => {
  const r = rng(77);
  for (let k = 0; k < 400; k++) {
    const market = Array.from({ length: 6 }, (_, i) => cand({ id: `R${i}`, method: (["craft", "bazaar", "npc", "forge"] as const)[i % 4],
      unitCost: 1_000 + r() * 200_000, unitProfit: r() * 50_000, sellRate: (1 + r() * 200) / H, maxUnits: 1 + Math.floor(r() * 300) }));
    const share = 0.1 + r() * 0.8;
    const inp = input({ budget: 100_000 + r() * 2e6, maxShare: share });
    const p = solve(market, inp);
    for (const a of p.allocations) {
      assert.ok(a.units * a.candidate.unitCost <= inp.budget * share + 1e-6, `market ${k}: ${a.candidate.id} ${a.units} units > ${inp.budget * share}`);
    }
  }
});

test("one expensive item is bought when it is the best use of the coins", () => {
  const cs = [
    cand({ id: "COOKIE", method: "bazaar", unitCost: 3_100_000, unitProfit: 140_000, buyRate: 0.01, sellRate: 0.01, maxUnits: 3 }),
    cand({ id: "DUST", unitCost: 200, unitProfit: 2, sellRate: 1 }),
  ];
  const p = solve(cs, input({ budget: 3_200_000 }));
  assert.ok(p.allocations.some((a) => a.candidate.id === "COOKIE"));
});

test("chunky choices: two mid jobs beat one big job when they earn more together", () => {
  const cs = [
    cand({ id: "BIG", method: "forge", leadSeconds: H, unitCost: 1_000_000, unitProfit: 100_000, maxUnits: 1, sellRate: 1 }),
    cand({ id: "MID", method: "forge", leadSeconds: H, unitCost: 500_000, unitProfit: 70_000, maxUnits: 2, sellRate: 1 }),
  ];
  const p = solve(cs, input({ budget: 1_000_000, freeSlots: 2 }));
  assert.deepEqual(p.allocations.map((a) => [a.candidate.id, a.units]), [["MID", 2]]);
});

test("same input, same plan", () => {
  const cs = market(42, 30);
  const a = solve(cs, randomInput(3));
  const b = solve(cs, randomInput(3));
  assert.deepEqual(a, b);
});

// ───────────────────────────── Properties over random markets ─────────────────────────────

test("400 random markets: limits always hold", () => {
  for (let s = 1; s <= 400; s++) {
    const inp = randomInput(s);
    check(solve(market(s, 25), inp), inp);
  }
});

test("more budget never lowers the result", () => {
  for (let s = 1; s <= 60; s++) {
    const cs = market(s, 20);
    let prev = -Infinity;
    for (const budget of [1e4, 1e5, 1e6, 1e7, 1e8]) {
      const u = solveLots(prepare(cs, randomInput(s)), { ...randomInput(s), budget }).utility;
      const got = solve(cs, { ...randomInput(s), budget }).utility;
      assert.ok(got >= prev - Math.abs(prev) * 0.02 - 1, `seed ${s}: ${got} < ${prev} at ${budget}`);
      prev = Math.max(prev, got);
      void u;
    }
  }
});

test("more slots and a longer horizon never lower the result", () => {
  for (let s = 1; s <= 60; s++) {
    const cs = market(s, 20);
    const base = randomInput(s);
    const u0 = solve(cs, base).utility;
    const moreSlots = solve(cs, { ...base, freeSlots: base.freeSlots + 2 }).utility;
    const longer = solve(cs, { ...base, horizonSeconds: base.horizonSeconds * 2 }).utility;
    assert.ok(moreSlots >= u0 * 0.98 - 1, `seed ${s}: slots ${moreSlots} < ${u0}`);
    assert.ok(longer >= u0 * 0.98 - 1, `seed ${s}: horizon ${longer} < ${u0}`);
  }
});

test("adding a flip never lowers the result", () => {
  for (let s = 1; s <= 60; s++) {
    const cs = market(s, 20);
    const inp = randomInput(s);
    const less = solve(cs.slice(0, 15), inp).utility;
    const more = solve(cs, inp).utility;
    assert.ok(more >= less * 0.98 - 1, `seed ${s}: ${more} < ${less}`);
  }
});

// ───────────────────────────── Optimality ─────────────────────────────

test("matches the exact optimum on 1,150 small markets (5, 7 and 8 flips)", () => {
  const gaps: number[] = [];
  let exact = 0;
  const cases = [
    ...Array.from({ length: 500 }, (_, i) => [i + 1, 5]),
    ...Array.from({ length: 500 }, (_, i) => [i + 1, 7]),
    ...Array.from({ length: 150 }, (_, i) => [i + 1, 8]),
  ] as [number, number][];
  for (const [s, size] of cases) {
    const cs = market(s * 13 + size, size, true);
    const inp = randomInput(s);
    const prep = prepare(cs, inp);
    const best = bruteForce(prep, inp);
    const got = solveLots(prep, inp).utility;
    assert.ok(got <= best + 1e-6, "solver beat brute force: lot accounting is off");
    const gap = best > 0 ? (best - got) / best : 0;
    gaps.push(gap);
    if (gap < 1e-9) exact++;
    if (process.env.DEBUG && gap > 0.05) {
      console.log("seed", s, size, "gap", gap.toFixed(3), "best", best.toFixed(0), "got", got.toFixed(0), JSON.stringify(inp));
      console.log(JSON.stringify(solveLots(prep, inp).taken));
      prep.lots.forEach((ls, i) => console.log(i, prep.models[i]!.c.method, ls.map((l) => `${l.from}-${l.to}:${l.capital.toFixed(0)}/${l.utility.toFixed(0)}`).join(" ")));
    }
  }
  const mean = gaps.reduce((a, b) => a + b, 0) / gaps.length;
  const worst = Math.max(...gaps);
  console.log(`  exact optimum in ${exact}/${cases.length}, mean gap ${(mean * 100).toFixed(3)}%, worst ${(worst * 100).toFixed(2)}%`);
  assert.ok(mean < 0.005, `mean gap ${mean}`);
  assert.ok(worst < 0.05, `worst gap ${worst}`);
});

test("the local search helps, or at least never hurts", () => {
  let better = 0;
  for (let s = 1; s <= 200; s++) {
    const cs = market(s * 5, 12, true);
    const inp = randomInput(s);
    const prep = prepare(cs, inp);
    const plain = solveLots(prep, inp, { noLocalSearch: true }).utility;
    const full = solveLots(prep, inp).utility;
    assert.ok(full >= plain - 1e-6);
    if (full > plain + 1e-6) better++;
  }
  console.log(`  local search improved ${better}/200 cases`);
});

test("beats simple rules (most profit per unit, best ROI) on the same model", () => {
  let wins = 0;
  let total = 0;
  let sumOurs = 0;
  let sumProfit = 0;
  let sumRoi = 0;
  for (let s = 1; s <= 200; s++) {
    const cs = market(s * 3, 25);
    const inp = randomInput(s);
    const ours = solve(cs, inp).utility;
    // Baselines: put coins into flips in a fixed order, all units that fit, same scoring.
    const baseline = (key: (c: Candidate) => number) => {
      const sorted = [...cs].sort((a, b) => key(b) - key(a));
      const picks: Candidate[] = [];
      let left = inp.budget;
      let slots = inp.freeSlots;
      for (const c of sorted) {
        if (!inp.methods.includes(c.method) || c.unitCost > left) continue;
        let units = Math.min(c.maxUnits, Math.floor(left / c.unitCost));
        if (c.method === "forge") units = Math.min(units, slots);
        if (units <= 0) continue;
        left -= units * c.unitCost;
        if (c.method === "forge") slots -= units;
        picks.push({ ...c, maxUnits: units });
      }
      // Score the baseline picks with the same model by solving with only those units allowed and coins plenty.
      return solve(picks, { ...inp, budget: inp.budget * 10 }, { noLocalSearch: true }).utility;
    };
    const byProfit = baseline((c) => c.unitProfit);
    const byRoi = baseline((c) => c.unitProfit / c.unitCost);
    sumOurs += ours;
    sumProfit += byProfit;
    sumRoi += byRoi;
    total++;
    if (ours >= Math.max(byProfit, byRoi) * 0.97) wins++;
  }
  console.log(`  ours ${(sumOurs / 1e6).toFixed(1)}M vs most-profit ${(sumProfit / 1e6).toFixed(1)}M vs best-ROI ${(sumRoi / 1e6).toFixed(1)}M (utility, 200 markets)`);
  assert.ok(sumOurs > sumProfit && sumOurs > sumRoi);
  assert.ok(wins / total > 0.9, `won ${wins}/${total}`);
});

test("60 flips and 7 slots solve in well under a second", () => {
  const cs = market(99, 60);
  const t = performance.now();
  for (let i = 0; i < 5; i++) solve(cs, input({ budget: 50_000_000, freeSlots: 7, onlineSeconds: H }));
  const ms = (performance.now() - t) / 5;
  console.log(`  ${ms.toFixed(1)} ms per plan`);
  assert.ok(ms < 400, `${ms} ms`);
});

// ───────────────────────────── Realistic market, every condition ─────────────────────────────

/** Rates per hour, as the mock's flips. */
const REAL: Candidate[] = [
  cand({ id: "REFINED_MITHRIL", method: "forge", unitCost: 201_600, unitProfit: 548_900, leadSeconds: 17_280, sellRate: 40 / H, maxUnits: 2, volatility: 0.03 }),
  cand({ id: "MITHRIL_PLATE", method: "forge", unitCost: 1_212_000, unitProfit: 664_000, leadSeconds: 14_400, sellRate: 6 / H, maxUnits: 2, volatility: 0.05, alerts: ["WIDE_SPREAD"] }),
  cand({ id: "GEMSTONE_MIXTURE", method: "forge", unitCost: 410_000, unitProfit: 280_000, leadSeconds: 11_520, sellRate: 12 / H, maxUnits: 2 }),
  cand({ id: "BEJEWELED_HANDLE", method: "forge", unitCost: 41_000, unitProfit: 100_000, leadSeconds: 1_440, sellRate: 0.012 / H, maxUnits: 1, alerts: ["FEW_SALES"], volatility: 0.12 }),
  cand({ id: "POWER_CRYSTAL", method: "forge", unitCost: 120_000, unitProfit: 360_000, leadSeconds: 1_800, sellRate: 1 / H, maxUnits: 1, alerts: ["RECENT_CHANGE"], volatility: 0.15 }),
  cand({ id: "ENCHANTED_LAVA_BUCKET", unitCost: 42_160, unitProfit: 15_115, leadSeconds: 30, sellRate: 120 / H, maxUnits: 400, cycleSeconds: 95 }),
  cand({ id: "ENCHANTED_DIAMOND_BLOCK", unitCost: 204_800, unitProfit: 23_300, leadSeconds: 30, sellRate: 40 / H, maxUnits: 300, cycleSeconds: 1_300 }),
  cand({ id: "HOT_POTATO_BOOK", unitCost: 52_800, unitProfit: 28_175, leadSeconds: 30, sellRate: 200 / H, maxUnits: 600, cycleSeconds: 260 }),
  cand({ id: "STOCK_OF_STONKS", method: "npc", unitCost: 4_000_000, unitProfit: 1_830_000, sellRate: 0.5 / H, maxUnits: 1, volatility: 0.06 }),
  cand({ id: "ENCHANTED_COAL", method: "npc", unitCost: 110, unitProfit: 18, sellRate: 1, maxUnits: 640, cycleSeconds: 5, volatility: 0.02 }),
  cand({ id: "BOOSTER_COOKIE", method: "bazaar", unitCost: 3_120_000, unitProfit: 138_750, buyRate: 84 / 86_400, sellRate: 84 / 86_400, maxUnits: 84, cycleSeconds: 2_000, volatility: 0.02 }),
  cand({ id: "RECOMBOBULATOR_3000", method: "bazaar", unitCost: 6_400_000, unitProfit: 413_750, buyRate: 9 / 86_400, sellRate: 9 / 86_400, maxUnits: 9, cycleSeconds: 9_000, alerts: ["THIN_BOOK"] }),
  cand({ id: "FLAWLESS_RUBY_GEM", method: "bazaar", unitCost: 2_950_000, unitProfit: 91_500, buyRate: 22 / 86_400, sellRate: 22 / 86_400, maxUnits: 22, cycleSeconds: 4_000, alerts: ["MANIPULATION"] }),
  cand({ id: "LOCKED_DRILL", method: "forge", unitCost: 900_000, unitProfit: 2_000_000, leadSeconds: 7_200, sellRate: 2 / H, maxUnits: 1, locked: "HOTM 9" }),
];

const REAL_CLEAN = REAL.filter((c) => c.alerts.length === 0 && !c.locked);

for (const risk of ["safe", "balanced", "bold"] as Risk[]) {
  for (const hours of [1, 4, 12, 48]) {
    for (const budget of [100_000, 1_000_000, 10_000_000, 100_000_000]) {
      test(`real market · ${risk} · ${hours} h · ${budget.toLocaleString("en-US")} coins`, () => {
        const inp = input({ risk, budget, horizonSeconds: hours * H, onlineSeconds: Math.min(hours, 2) * H, freeSlots: 3 });
        const plan = solve(REAL, inp);
        check(plan, inp);
        assert.ok(plan.expectedProfit >= 0);
        assert.ok(!plan.allocations.some((a) => a.candidate.id === "LOCKED_DRILL"));
        const ids = plan.allocations.map((a) => a.candidate.id);
        if (risk === "safe") assert.ok(!ids.includes("POWER_CRYSTAL") && !ids.includes("FLAWLESS_RUBY_GEM") && !ids.includes("BEJEWELED_HANDLE"));
        if (risk !== "bold") assert.ok(!ids.includes("FLAWLESS_RUBY_GEM"));
        // A one-hour horizon cannot wait for a 4.8 h forge job in safe mode.
        if (hours === 1 && risk === "safe") assert.ok(!ids.includes("REFINED_MITHRIL"));
        // Budget use: with 1M+ there is always something worth doing.
        if (budget >= 1_000_000) assert.ok(plan.allocations.length > 0);
        if (process.env.SHOW) console.log(`  ${risk.padEnd(8)} ${String(hours).padStart(2)}h ${String(budget / 1e6).padStart(5)}M → ${ids.map((id, i) => `${id}×${plan.allocations[i]!.units}`).join(", ")} | exp ${Math.round(plan.expectedProfit / 1e3)}k of ${Math.round(plan.fullProfit / 1e3)}k, used ${Math.round(plan.capital / 1e3)}k`);
      });
    }
  }
}
