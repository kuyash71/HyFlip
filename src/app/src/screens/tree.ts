/** Turns a flip detail from the server into the recipe tree the recipe card and crafting web draw. */
import type { FlipType, OpportunityDetail, Plan, RecipeView, Venue } from "../api/client";

export type Cell = { item: string; count: number } | null;

/** A node of a recipe tree: what is needed, how it is obtained, and what it costs. */
export interface Node {
  item: string;
  qty: number;
  method: "BUY" | "CRAFT" | "FORGE" | "MISSING";
  /** Where it is bought (BUY), or where buying it would cost `buyTotal` (CRAFT/FORGE). */
  venue?: Venue;
  unit: number;
  total: number;
  /** For crafted nodes: what buying the same quantity would cost. */
  buyTotal?: number;
  grid?: Cell[];
  /** Forge recipes: duration for the player. */
  forgeSeconds?: number;
  outputCount?: number;
  /** Coins paid on top of the items (NPC shops, some forge recipes). */
  coins?: number;
  children?: Node[];
}

function fromPlan(p: Plan, recipes: Record<string, RecipeView>): Node {
  if (p.method === "CRAFT") {
    const r = recipes[p.itemId];
    return {
      item: p.itemId,
      qty: p.quantity,
      method: "CRAFT",
      venue: p.quote?.venue,
      unit: p.quantity > 0 ? p.cost / p.quantity : p.cost,
      total: p.cost,
      buyTotal: p.quote?.gross,
      grid: r?.grid ?? undefined,
      outputCount: r?.outputCount,
      coins: p.coins > 0 ? p.coins : undefined,
      children: p.inputs.map((i) => fromPlan(i, recipes)),
    };
  }
  if (p.method === "MISSING" || !p.quote) {
    return { item: p.itemId, qty: p.quantity, method: "MISSING", unit: 0, total: 0 };
  }
  return { item: p.itemId, qty: p.quantity, method: "BUY", venue: p.quote.venue, unit: p.quote.unitPrice, total: p.cost };
}

/** The whole tree, rooted at the flip's output. Bazaar flips are a single bought item. */
export function treeOf(type: FlipType, d: OpportunityDetail): Node {
  const s = d.summary;
  const r = d.recipes[s.outputId];
  const children = d.sheet.inputs.map((l) => fromPlan(l.plan, d.recipes));
  const method: Node["method"] = type === "forge" ? "FORGE" : type === "craft" ? "CRAFT" : "BUY";
  if (method === "BUY") {
    const first = children[0];
    return {
      item: s.outputId,
      qty: s.outputCount,
      method: "BUY",
      venue: first?.venue ?? d.sheet.inputs[0]?.quote?.venue,
      unit: s.outputCount > 0 ? d.sheet.cost / s.outputCount : d.sheet.cost,
      total: d.sheet.cost,
    };
  }
  return {
    item: s.outputId,
    qty: s.outputCount,
    method,
    unit: s.outputCount > 0 ? d.sheet.cost / s.outputCount : d.sheet.cost,
    total: d.sheet.cost,
    buyTotal: d.sheet.output?.gross,
    grid: r?.grid ?? undefined,
    forgeSeconds: type === "forge" ? s.durationSeconds : undefined,
    outputCount: r?.outputCount ?? s.outputCount,
    coins: d.sheet.coins > 0 ? d.sheet.coins : undefined,
    children,
  };
}

/** Crafted or forged nodes in the order a player does them: deepest first, the item itself last. */
export function craftedNodes(root: Node): Node[] {
  const out: Node[] = [];
  const walk = (n: Node) => {
    n.children?.forEach(walk);
    if (n.method === "CRAFT" || n.method === "FORGE") out.push(n);
  };
  walk(root);
  return out;
}
