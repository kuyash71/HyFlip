import { describe, expect, it } from "vitest";
import type { CollectorStatus, Opportunity, OpportunityDetail, TradeStats, TradeSummary } from "../api/client";
import { realisation, solve } from "../calc/advisor";
import { sellRate, toCandidate } from "../calc/candidates";
import { MIN_RESULTS, trackRecord } from "../calc/trackRecord";
import { coins, duration, iconPixels, iconStyle, liquidityTier, pct, prettyId } from "../ui/common";
import { fromText, parseCollections } from "./settings";
import { health, uptimeCells } from "./status";
import { craftedNodes, treeOf } from "./tree";

function opp(over: Partial<Opportunity> = {}): Opportunity {
  return {
    id: "REFINED_MITHRIL", type: "FORGE", name: "Refined Mithril", outputId: "REFINED_MITHRIL", outputCount: 1,
    cost: 200_000, value: 760_000, fees: 9_500, netProfit: 550_500, roi: 2.75, baseDurationSeconds: 21_600,
    durationSeconds: 17_280, profitPerHour: 114_687, sessions: 1, profitPerSession: 550_500, sellVenue: "BAZAAR",
    buyVenues: ["BAZAAR"], liquidity: { level: "HIGH", dailyVolume: 20_000 }, unlock: "MET", complete: true, alerts: [],
    runsPerDay: null, profitPerDay: null, fillSeconds: null, ...over,
  };
}

const quote = (itemId: string, quantity: number, unitPrice: number) => ({
  itemId, quantity, venue: "BAZAAR" as const, unitPrice, gross: unitPrice * quantity, fees: 0, priceAt: 0, estimated: false,
});

function forgeDetail(): OpportunityDetail {
  return {
    summary: opp(),
    sheet: {
      inputs: [{
        itemId: "ENCHANTED_MITHRIL", quantity: 160, quote: quote("ENCHANTED_MITHRIL", 160, 1330),
        plan: {
          itemId: "ENCHANTED_MITHRIL", quantity: 160, method: "CRAFT", cost: 128_000, quote: quote("ENCHANTED_MITHRIL", 160, 1330),
          crafts: 160, coins: 0,
          inputs: [{ itemId: "MITHRIL_ORE", quantity: 25_600, method: "BUY", cost: 128_000, quote: quote("MITHRIL_ORE", 25_600, 5), crafts: 0, coins: 0, inputs: [] }],
        },
      }],
      coins: 0, cost: 128_000, output: quote("REFINED_MITHRIL", 1, 760_000), value: 760_000, fees: 9_500, netProfit: 622_500,
      missingPrices: [],
    },
    unlock: { state: "MET", checks: [] },
    guide: { steps: [], logic: "" },
    names: {},
    recipes: {
      REFINED_MITHRIL: { type: "FORGE", grid: null, outputCount: 1, durationSeconds: 21_600, coins: 0 },
      ENCHANTED_MITHRIL: { type: "CRAFTING", grid: [null, { item: "MITHRIL_ORE", count: 32 }, null, null, null, null, null, null, null], outputCount: 1, durationSeconds: null, coins: 0 },
    },
    rarities: {},
    extras: { rarity: "EPIC", requirements: [], trend: null, volatility: null },
  };
}

describe("recipe tree", () => {
  it("roots forge flips at the output and keeps buy-vs-craft per input", () => {
    const t = treeOf("forge", forgeDetail());
    expect(t.method).toBe("FORGE");
    expect(t.forgeSeconds).toBe(17_280);
    const em = t.children![0]!;
    expect(em.method).toBe("CRAFT");
    expect(em.buyTotal).toBe(160 * 1330);
    expect(em.total).toBe(128_000);
    expect(em.grid![1]).toEqual({ item: "MITHRIL_ORE", count: 32 });
    expect(em.children![0]).toMatchObject({ method: "BUY", unit: 5, venue: "BAZAAR" });
  });

  it("lists crafted steps deepest first, the item last", () => {
    const steps = craftedNodes(treeOf("forge", forgeDetail()));
    expect(steps.map((s) => s.item)).toEqual(["ENCHANTED_MITHRIL", "REFINED_MITHRIL"]);
  });

  it("marks inputs without a price as missing, not free", () => {
    const d = forgeDetail();
    d.sheet.inputs[0]!.plan = { itemId: "X", quantity: 2, method: "MISSING", cost: 0, quote: null, crafts: 0, coins: 0, inputs: [] };
    expect(treeOf("forge", d).children![0]!.method).toBe("MISSING");
    expect(craftedNodes(treeOf("forge", d)).map((n) => n.item)).toEqual(["REFINED_MITHRIL"]);
  });

  it("gives Bazaar flips a single bought node", () => {
    const t = treeOf("bazaar", forgeDetail());
    expect(t.method).toBe("BUY");
    expect(t.children).toBeUndefined();
  });
});

describe("Flip Calc candidates", () => {
  const ctx = { bazaarShare: 0.05, kept: realisation };

  it("sells a Bazaar output at the player's share of volume, per run", () => {
    expect(sellRate(opp(), ctx)).toBeCloseTo((20_000 * 0.05) / 86_400);
    expect(sellRate(opp({ outputCount: 4 }), ctx)).toBeCloseTo((20_000 * 0.05) / 4 / 86_400);
    expect(sellRate(opp({ sellVenue: "AUCTION" }), ctx)).toBeCloseTo(20_000 / 86_400);
    expect(sellRate(opp({ sellVenue: "NPC" }), ctx)).toBe(Infinity);
    expect(sellRate(opp({ liquidity: { level: "UNKNOWN", dailyVolume: null } }), ctx)).toBe(0);
  });

  it("maps a forge row to one job per slot with the forge time as lead", () => {
    const c = toCandidate("forge", opp({ fillSeconds: 600 }), undefined, ctx);
    expect(c).toMatchObject({ method: "forge", unitCost: 200_000, unitProfit: 550_500, leadSeconds: 17_880, cycleSeconds: 0, maxUnits: 2 });
    expect(toCandidate("forge", opp({ liquidity: { level: "LOW", dailyVolume: 3 } }), undefined, ctx).maxUnits).toBe(1);
  });

  it("carries locks, alerts and the track record", () => {
    const o = opp({ unlock: "LOCKED", alerts: [{ type: "FEW_SALES", message: "", itemId: null }, { type: "FEW_SALES", message: "", itemId: "X" }] });
    const c = toCandidate("craft", o, { rarity: null, requirements: ["HOTM 5"], trend: null, volatility: 0.2 }, ctx);
    expect(c.locked).toBe("HOTM 5");
    const unknown = toCandidate("craft", opp({ unlock: "UNKNOWN" }), { rarity: null, requirements: ["Catacombs V"], trend: null, volatility: 0.2 }, { ...ctx, strictUnlocks: true });
    // Without a linked profile nearly everything is unconfirmed, so it stays in.
    expect(toCandidate("craft", opp({ unlock: "UNKNOWN" }), undefined, ctx).locked).toBeUndefined();
    expect(unknown.locked).toMatch(/^Catacombs V \(cannot be checked/);
    expect(toCandidate("craft", opp({ unlock: "MET" }), undefined, ctx).locked).toBeUndefined();
    expect(c.alerts).toEqual(["FEW_SALES"]);
    expect(c.realisation).toBe(0.7);
    expect(c.volatility).toBe(0.2);
  });

  it("feeds the solver: a profitable forge recipe gets a free slot", () => {
    const c = toCandidate("forge", opp(), undefined, ctx);
    const plan = solve([c], { budget: 1_000_000, horizonSeconds: 24 * 3600, onlineSeconds: 3600, methods: ["forge"], freeSlots: 2, risk: "balanced" });
    expect(plan.allocations[0]?.units).toBeGreaterThanOrEqual(1);
    expect(plan.capital).toBeLessThanOrEqual(1_000_000);
  });

  it("uses Bazaar runs a day for both sides of a Bazaar flip", () => {
    const c = toCandidate("bazaar", opp({ runsPerDay: 8_640, fillSeconds: 120 }), undefined, ctx);
    expect(c.buyRate).toBeCloseTo(0.1);
    expect(c.sellRate).toBeCloseTo(0.1);
    expect(c.maxUnits).toBe(8_640);
    expect(c.cycleSeconds).toBe(120);
  });
});

describe("track record", () => {
  const stats = (withResult: number, accuracy: number | null): TradeStats => ({
    trades: withResult, withResult, expectedProfit: 1, actualProfit: 1, accuracy, profitable: 1,
  });
  const summary = (over: Partial<TradeSummary>): TradeSummary => ({ all: stats(0, null), byType: {}, byAlert: {}, noAlerts: stats(0, null), ...over });

  it("falls back to the default table without enough results", () => {
    const k = trackRecord(summary({ byAlert: { FEW_SALES: stats(MIN_RESULTS - 1, 0.1) } }));
    expect(k([])).toBe(0.92);
    expect(k(["FEW_SALES"])).toBe(0.7);
    expect(trackRecord(null)(["MANIPULATION"])).toBe(0.3);
  });

  it("uses the journal once there are enough results, the lowest alert winning", () => {
    const k = trackRecord(summary({ noAlerts: stats(10, 0.98), byAlert: { FEW_SALES: stats(6, 0.5), THIN_BOOK: stats(9, 0.95) } }));
    expect(k([])).toBe(0.98);
    expect(k(["THIN_BOOK"])).toBe(0.95);
    expect(k(["THIN_BOOK", "FEW_SALES"])).toBe(0.5);
    expect(trackRecord(summary({ noAlerts: stats(10, 3) }))([])).toBe(1.1);
  });
});

describe("collector health", () => {
  const now = 10_000_000;
  const c = (over: Partial<CollectorStatus>): CollectorStatus => ({
    name: "BAZAAR", intervalSeconds: 60, lastRun: { collector: "BAZAAR", startedAt: now - 5_000, finishedAt: now - 4_000, status: "OK", rowsWritten: 1, error: null },
    lastSuccessAt: now - 4_000, consecutiveFailures: 0, ...over,
  });

  it("reads OK, late, failing and idle", () => {
    expect(health(c({}), now)).toBe("OK");
    expect(health(c({ lastSuccessAt: now - 400_000 }), now)).toBe("SLOW");
    expect(health(c({ lastRun: { ...c({}).lastRun!, status: "FAILED" }, consecutiveFailures: 2 }), now)).toBe("FAILED");
    expect(health(c({ lastRun: null }), now)).toBe("IDLE");
  });

  it("buckets runs into half hours, a success beating a failure", () => {
    const day = 86_400_000;
    const cells = uptimeCells([
      { collector: "X", startedAt: now - day + 1, finishedAt: null, status: "FAILED" },
      { collector: "X", startedAt: now - day + 2, finishedAt: null, status: "OK" },
      { collector: "X", startedAt: now - 60_000, finishedAt: null, status: "FAILED" },
      { collector: "X", startedAt: now - 2 * day, finishedAt: null, status: "OK" },
    ], now);
    expect(cells).toHaveLength(48);
    expect(cells[0]).toBe("OK");
    expect(cells[47]).toBe("FAILED");
    expect(cells.filter((x) => x === "IDLE")).toHaveLength(46);
  });
});

describe("formatting", () => {
  it("shortens coins with a real minus", () => {
    expect(coins(950)).toBe("950");
    expect(coins(12_345)).toBe("12.3k");
    expect(coins(4_560_000)).toBe("4.56M");
    expect(coins(-1_200_000_000)).toBe("−1.20B");
    expect(coins(Number.NaN)).toBe("—");
  });

  it("writes durations and shares", () => {
    expect(duration(45)).toBe("45s");
    expect(duration(17_280)).toBe("4h 48m");
    expect(duration(3 * 86_400 + 7_200)).toBe("3d 2h");
    expect(duration(3_599)).toBe("1h");
    expect(pct(0.256)).toBe("26%");
    expect(pct(null)).toBe("—");
  });

  it("names unknown IDs readably", () => {
    expect(prettyId("ENCHANTED_LAVA_BUCKET")).toBe("Enchanted Lava Bucket");
    expect(prettyId("SHARPNESS;6")).toBe("Sharpness 6");
  });
});

describe("settings fields", () => {
  it("reads percentages, coins, durations and blanks", () => {
    expect(fromText("1.25", { kind: "pct" })).toEqual({ value: 0.0125 });
    expect(fromText("1,25%", { kind: "pct" })).toEqual({ value: 0.0125 });
    expect(fromText("50m", { kind: "coins" })).toEqual({ value: 50_000_000 });
    expect(fromText("4h 30m", { kind: "duration" })).toEqual({ value: "PT16200S" });
    expect(fromText("", { kind: "int", optional: true })).toEqual({ value: null });
    expect(fromText("", { kind: "int" })).toEqual({ error: "required" });
    expect(fromText("2.5", { kind: "int" })).toEqual({ error: "a whole number" });
  });

  it("reads collection tiers one per line", () => {
    expect(parseCollections("MITHRIL_ORE: 9\n\nGemstone = 7")).toEqual({ MITHRIL_ORE: 9, Gemstone: 7 });
    expect(typeof parseCollections("nonsense")).toBe("string");
  });
});

describe("liquidity gem", () => {
  it("adds a very high tier for HIGH items that trade far more than one run", () => {
    expect(liquidityTier("HIGH", 5_000, 1)).toBe("VERY_HIGH");
    expect(liquidityTier("HIGH", 1_999, 1)).toBe("HIGH");
    expect(liquidityTier("HIGH", 5_000, 64)).toBe("HIGH");
    expect(liquidityTier("HIGH", null)).toBe("HIGH");
    expect(liquidityTier("MEDIUM", 1e9)).toBe("MEDIUM");
    expect(liquidityTier("UNKNOWN")).toBe("UNKNOWN");
  });
});

describe("pack icons", () => {
  it("covers whole multiples of the 32 px cell in device pixels", () => {
    expect(iconPixels(36)).toBe(32);
    expect(iconPixels(40)).toBe(32);
    expect(iconPixels(68)).toBe(64);
    expect(iconPixels(70 - 2)).toBe(64); // the big slot: 70 px with a 3 px border
    expect(iconPixels(24)).toBe(16);
    // 125% and 150% scaling: the CSS size changes, the device size stays a multiple of 32
    expect(iconPixels(36, 1.25)).toBeCloseTo(25.6);
    expect(iconPixels(36, 1.5) * 1.5).toBe(32);
    expect(iconPixels(68, 2) * 2).toBe(128);
    for (const dpr of [1, 1.25, 1.5, 1.75, 2]) {
      for (const slot of [24, 26, 36, 40, 44, 68]) {
        const device = Math.round(iconPixels(slot, dpr) * dpr);
        expect(device === 16 || device % 32 === 0).toBe(true);
        expect(iconPixels(slot, dpr)).toBeLessThanOrEqual(Math.max(16 / dpr, slot - 4));
      }
    }
  });

  it("places a cell of the atlas", () => {
    const style = iconStyle({ url: "blob:x", cell: 32, columns: 10 }, 23, 16);
    expect(style.backgroundPosition).toBe("-48px -32px");
    expect(style.backgroundSize).toBe("160px auto");
    expect(style.backgroundImage).toBe('url("blob:x")');
  });
});

describe("desktop content security policy", () => {
  it("lets the icon atlas, loaded as a blob URL, be drawn", async () => {
    const { readFileSync } = await import("node:fs");
    const conf = JSON.parse(readFileSync(new URL("../../src-tauri/tauri.conf.json", import.meta.url), "utf8"));
    const img = (conf.app.security.csp as string).split(";").map((s) => s.trim()).find((s) => s.startsWith("img-src"));
    expect(img?.split(/\s+/)).toContain("blob:");
  });
});
