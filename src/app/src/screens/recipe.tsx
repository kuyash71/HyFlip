/** Recipe views: the step card with its 3×3 grid or forge row, the step strip, and the full crafting web. */
import { useEffect, useMemo, useRef, useState } from "react";
import type { Venue } from "../api/client";
import { coins, duration, pct, Slot, Stat, useItems, useRarityColour, VENUE_LABEL, VENUE_SHORT, VenueChip } from "../ui/common";
import { PixelIcon } from "../ui/pixel";
import { workOf, type Cell, type Node } from "./tree";

/** What the crafting web shows about the flip as a whole. */
export interface WebFlip {
  item: string;
  tree: Node;
  /** Sale after tax. */
  value: number;
  cost: number;
  profit: number;
  roi: number | null;
  sells: Venue | null;
}

/** Without a known grid, the ingredients fill the grid in order. */
function fallbackGrid(node: Node): Cell[] {
  const cells: Cell[] = Array<Cell>(9).fill(null);
  (node.children ?? []).slice(0, 9).forEach((c, i) => (cells[i] = { item: c.item, count: c.qty }));
  return cells;
}

/** Step chips under the recipe; the mouse wheel over the strip moves between steps. */
export function StepStrip({ pages, page, onPage }: { pages: Node[]; page: number; onPage: (p: number) => void }) {
  const items = useItems();
  const strip = useRef<HTMLDivElement>(null);
  const state = useRef({ page, acc: 0, last: 0 });
  state.current.page = page;
  useEffect(() => {
    const el = strip.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const st = state.current;
      st.acc += Math.abs(e.deltaY) > Math.abs(e.deltaX) ? e.deltaY : e.deltaX;
      const now = performance.now();
      if (Math.abs(st.acc) < 30 || now - st.last < 140) return;
      const next = Math.max(0, Math.min(pages.length - 1, st.page + (st.acc > 0 ? 1 : -1)));
      st.acc = 0;
      st.last = now;
      onPage(next);
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, [pages.length, onPage]);
  // Keep the active chip in view by scrolling the strip only, never the panels around it.
  useEffect(() => {
    const el = strip.current;
    const chip = el?.querySelector<HTMLElement>(".step-chip.on");
    if (!el || !chip) return;
    const left = chip.offsetLeft - el.offsetLeft;
    if (left < el.scrollLeft) el.scrollLeft = left - 4;
    else if (left + chip.offsetWidth > el.scrollLeft + el.clientWidth) el.scrollLeft = left + chip.offsetWidth - el.clientWidth + 4;
  }, [page]);
  return (
    <div className="step-strip" ref={strip} data-tip="Scroll to move between steps">
      {pages.map((p, i) => (
        <button key={i} className={`step-chip ${i === page ? "on" : ""}`} onClick={() => onPage(i)}>
          <span className="step-no">{i + 1}</span>
          <Slot item={p.item} size={24} />
          <span className="step-name">{p.method === "FORGE" ? "Forge" : "Craft"} {items(p.item).name}</span>
        </button>
      ))}
    </div>
  );
}

/** One recipe step: a 3×3 grid (crafting) or a forge row, the output, and where each input comes from. */
export function RecipeCard({ node }: { node: Node }) {
  const items = useItems();
  const forge = node.method === "FORGE";
  const saved = node.buyTotal !== undefined && node.buyTotal > node.total ? node.buyTotal - node.total : null;
  return (
    <div className="recipe-card">
      <div className="recipe-visual">
        <div className="recipe-in">
          {forge ? (
            <div className="forge-inputs">
              {(node.children ?? []).map((c) => <Slot key={c.item} item={c.item} count={c.qty} size={44} />)}
            </div>
          ) : (
            <div className="grid3">
              {(node.grid ?? fallbackGrid(node)).map((cell, i) => (
                <Slot key={i} item={cell?.item} count={cell?.count} size={44} />
              ))}
            </div>
          )}
        </div>
        <div className="recipe-arrow">
          {forge ? (
            <>
              <PixelIcon name="manager" size={28} />
              <span className="small">{duration(node.forgeSeconds ?? 0)}</span>
            </>
          ) : (
            <span className="mc-arrow">➜</span>
          )}
        </div>
        <Slot item={node.item} count={node.qty > 1 ? node.qty : node.outputCount} size={56} big />
      </div>
      <table className="inputs">
        <colgroup>
          <col style={{ width: 34 }} />
          <col />
          <col style={{ width: 60 }} />
          <col style={{ width: 80 }} />
        </colgroup>
        <tbody>
          {(node.children ?? []).map((c) => (
            <tr key={c.item}>
              <td><Slot item={c.item} size={24} /></td>
              <td>
                <div className="ellipsis" data-tip={items(c.item).name}>{items(c.item).name}</div>
                <div className="muted small">× {c.qty.toLocaleString("en-US")} · {coins(c.unit)} each</div>
              </td>
              <td>{c.method === "BUY" ? <VenueChip venue={c.venue} /> : <span className="chip craft">craft</span>}</td>
              <td className="num">{coins(c.total)}</td>
            </tr>
          ))}
          <tr className="total">
            <td />
            <td colSpan={2}>Step cost</td>
            <td className="num">{coins(node.total)}</td>
          </tr>
          <tr className="saving">
            <td />
            <td colSpan={3}>
              <span className="saving-line">
                <span className="muted ellipsis">
                  {saved !== null ? `Buying it would cost ${coins(node.buyTotal!)}` : "Buying it costs the same"}
                </span>
                {saved !== null && <span className="gain">saves {coins(saved)}</span>}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  );
}

// ───────────────────────────── Full crafting web ─────────────────────────────

interface Placed {
  key: string;
  parent: string | null;
  node: Node;
  /** Row from the top: 0 holds what is bought first, the item itself is in the last row. */
  stage: number;
  x: number;
  y: number;
  step: number;
}

const T_W = 220;
const T_H = 140;
const GAP_X = 40;
const ROW_H = 230;
const PAD = 60;

/**
 * Top-down tree: each node sits in the row of its stage (how many crafts lead up to it), so bought
 * inputs are on top and the item at the bottom. Leaves get their own columns and every craft is centred
 * over its inputs, so arrows never cross a card. Steps are numbered in the order they are done.
 */
function layoutTree(root: Node): { nodes: Placed[]; width: number; height: number; stages: number } {
  const stageOf = (n: Node): number => (n.children?.length ? 1 + Math.max(...n.children.map(stageOf)) : 0);
  const stages = stageOf(root);
  const nodes: Placed[] = [];
  let cursor = PAD;
  const place = (n: Node, parent: string | null, key: string): number => {
    const p: Placed = { key, parent, node: n, stage: stageOf(n), x: 0, y: PAD + stageOf(n) * ROW_H + T_H / 2, step: 0 };
    nodes.push(p);
    const kids = n.children ?? [];
    if (kids.length === 0) {
      p.x = cursor + T_W / 2;
      cursor += T_W + GAP_X;
    } else {
      const xs = kids.map((c, i) => place(c, key, `${key}.${i}`));
      p.x = (xs[0]! + xs[xs.length - 1]!) / 2;
    }
    return p.x;
  };
  place(root, null, "0");
  let step = 0;
  const number = (key: string) => {
    nodes.filter((p) => p.parent === key).forEach((p) => number(p.key));
    nodes.find((p) => p.key === key)!.step = ++step;
  };
  number("0");
  const width = Math.max(cursor - GAP_X + PAD, T_W + 2 * PAD);
  // One more row for the sale under the item.
  const height = PAD + (stages + 1) * ROW_H + T_H / 2 + PAD;
  return { nodes, width, height, stages };
}

function stepText(p: Placed, name: string): string {
  const qty = p.node.qty.toLocaleString("en-US");
  if (p.node.method === "BUY") return `Buy ${qty} ${name}`;
  if (p.node.method === "MISSING") return `Get ${qty} ${name} (no price known)`;
  return `${p.node.method === "FORGE" ? "Forge" : "Craft"} ${qty} ${name}`;
}

const savedOf = (n: Node) => (n.method !== "BUY" && n.buyTotal !== undefined ? n.buyTotal - n.total : 0);

type Edge = "BAZAAR" | "AUCTION" | "NPC" | "CRAFT" | "SELL";

/** Pan and zoom state for the tree: translate, then scale. */
interface View {
  x: number;
  y: number;
  k: number;
}

/** Full-window crafting tree with pan (drag) and zoom (wheel, buttons, keys). */
export function CraftWeb({ flip, onClose }: { flip: WebFlip; onClose: () => void }) {
  const { nodes, width, height } = useMemo(() => layoutTree(flip.tree), [flip.tree]);
  const viewport = useRef<HTMLDivElement>(null);
  const [view, setView] = useState<View>({ x: 0, y: 0, k: 1 });
  const viewRef = useRef(view);
  viewRef.current = view;
  const [hot, setHot] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const colour = useRarityColour();
  const items = useItems();
  const byKey = new Map(nodes.map((n) => [n.key, n]));
  const ordered = [...nodes].sort((a, b) => a.step - b.step);
  const root = nodes.find((n) => n.parent === null)!;
  const item = items(flip.item);
  const crafted = nodes.filter((n) => n.node.method === "CRAFT" || n.node.method === "FORGE");
  const savedTotal = crafted.filter((n) => n.parent !== null).reduce((s, n) => s + Math.max(0, savedOf(n.node)), 0);
  const forgeTime = crafted.reduce((s, n) => s + (n.node.forgeSeconds ?? 0), 0);
  const work = workOf(root.node);
  const venues = [...new Set(nodes.filter((n) => n.node.method === "BUY" && n.node.venue).map((n) => n.node.venue!))];
  const sellY = root.y + ROW_H;

  const fit = () => {
    const el = viewport.current;
    if (!el) return;
    const k = Math.min(1.15, (el.clientWidth - 20) / width, (el.clientHeight - 20) / height);
    setView({ k, x: (el.clientWidth - width * k) / 2, y: (el.clientHeight - height * k) / 2 });
  };
  const zoomAt = (factor: number, mx?: number, my?: number) => {
    const el = viewport.current;
    if (!el) return;
    const v = viewRef.current;
    const cx = mx ?? el.clientWidth / 2;
    const cy = my ?? el.clientHeight / 2;
    const k = Math.max(0.25, Math.min(2.5, v.k * factor));
    setView({ k, x: cx - ((cx - v.x) * k) / v.k, y: cy - ((cy - v.y) * k) / v.k });
  };

  useEffect(() => {
    fit();
    const el = viewport.current;
    if (!el) return;
    const ro = new ResizeObserver(() => fit());
    ro.observe(el);
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const r = el.getBoundingClientRect();
      zoomAt(Math.exp(-e.deltaY * (e.deltaMode === 1 ? 0.05 : 0.0015)), e.clientX - r.left, e.clientY - r.top);
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => {
      ro.disconnect();
      el.removeEventListener("wheel", onWheel);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [width, height]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      else if (e.key === "+" || e.key === "=") zoomAt(1.2);
      else if (e.key === "-") zoomAt(1 / 1.2);
      else if (e.key === "0") fit();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onClose]);

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    const start = { px: e.clientX, py: e.clientY, x: viewRef.current.x, y: viewRef.current.y };
    const el = e.currentTarget;
    el.setPointerCapture(e.pointerId);
    setDragging(true);
    const move = (ev: PointerEvent) => setView((v) => ({ ...v, x: start.x + ev.clientX - start.px, y: start.y + ev.clientY - start.py }));
    const up = () => {
      setDragging(false);
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
      el.removeEventListener("pointercancel", up);
    };
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", up);
  };

  // A node lights up with its whole path down to the item.
  const lit = new Set<string>();
  for (let k = hot; k; k = byKey.get(k)?.parent ?? null) lit.add(k);
  if (hot === "sell") nodes.forEach((n) => lit.add(n.key));

  const edgeOf = (n: Placed): Edge => (n.node.method === "BUY" ? n.node.venue ?? "NPC" : n.node.method === "MISSING" ? "SELL" : "CRAFT");
  const arrow = (x1: number, y1: number, x2: number, y2: number) => {
    const mid = y2 - 30;
    return x1 === x2 ? `M${x1},${y1} V${y2}` : `M${x1},${y1} V${mid} H${x2} V${y2}`;
  };
  const grid = 28 * view.k;

  return (
    <div className="web-backdrop" onClick={onClose}>
      <div className="web" role="dialog" aria-label={`Crafting tree of ${item.name}`} onClick={(e) => e.stopPropagation()}>
        <header className="web-head">
          <PixelIcon name="craft" size={26} />
          <h2>Crafting tree · <span style={{ color: colour(item.rarity) }}>{item.name}</span></h2>
          <span className="web-legend">
            <span className="legend-arrow edge-BAZAAR">Bazaar</span>
            <span className="legend-arrow edge-AUCTION">Auction</span>
            <span className="legend-arrow edge-NPC">NPC</span>
            <span className="legend-arrow edge-CRAFT">Crafted</span>
          </span>
          <button className="web-close" onClick={onClose} aria-label="Close" data-tip="Close (Esc)">✕</button>
        </header>
        <div className="web-kpis">
          <Stat label="Sell (after tax)" value={coins(flip.value)} />
          <Stat label="Total cost" value={coins(flip.cost)} />
          <Stat label="Profit" value={coins(flip.profit)} tone="gain" />
          <Stat label="Return" value={pct(flip.roi)} tone="gain" />
          <Stat label="Steps" value={`${nodes.length} (${crafted.length} ${crafted.length === 1 ? "recipe" : "recipes"})`} />
          <Stat label="Crafts in all" value={work.crafts.toLocaleString("en-US")} />
          <Stat label="Items to buy" value={work.bought.toLocaleString("en-US")} />
          {savedTotal > 0 && <Stat label="Saved by crafting parts" value={coins(savedTotal)} tone="gain" />}
          {forgeTime > 0 && <Stat label="Forge time" value={duration(forgeTime)} />}
          <Stat label="Buy from" value={venues.map((v) => VENUE_SHORT[v]).join(" + ")} />
        </div>
        <div className="web-body">
          <div className={`web-canvas ${dragging ? "dragging" : ""}`} ref={viewport} onPointerDown={onPointerDown}
            style={{ backgroundSize: `${grid}px ${grid}px, ${grid}px ${grid}px, ${grid * 4}px ${grid * 4}px, ${grid * 4}px ${grid * 4}px`,
              backgroundPosition: `${view.x}px ${view.y}px` }}>
            <div className="web-world" style={{ width, height, transform: `translate(${view.x}px, ${view.y}px) scale(${view.k})` }}>
              <div className="web-stage-labels">
                {Array.from({ length: root.stage + 2 }, (_, i) => (
                  <span key={i} style={{ top: PAD + i * ROW_H + T_H / 2 }}>
                    {i === 0 ? "Buy" : i === root.stage + 1 ? "Sell" : `Step ${i}`}
                  </span>
                ))}
              </div>
              <svg width={width} height={height} aria-hidden="true">
                <defs>
                  {(["BAZAAR", "AUCTION", "NPC", "CRAFT", "SELL"] as Edge[]).map((e) => (
                    <marker key={e} id={`arrow-${e}`} viewBox="0 0 10 10" refX={8} refY={5} markerWidth={13} markerHeight={13}
                      markerUnits="userSpaceOnUse" orient="auto-start-reverse">
                      <path d="M0,0 L10,5 L0,10 Z" className={`arrow-head edge-${e}`} />
                    </marker>
                  ))}
                </defs>
                {nodes.filter((n) => n.parent).map((n) => {
                  const p = byKey.get(n.parent!)!;
                  const e = edgeOf(n);
                  return (
                    <g key={n.key} className={`edge edge-${e} ${lit.has(n.key) ? "lit" : ""} ${hot && !lit.has(n.key) ? "dim" : ""}`}>
                      <path d={arrow(n.x, n.y + T_H / 2, p.x, p.y - T_H / 2 - 3)} markerEnd={`url(#arrow-${e})`} />
                    </g>
                  );
                })}
                <g className={`edge edge-SELL ${hot === "sell" ? "lit" : ""}`}>
                  <path d={arrow(root.x, root.y + T_H / 2, root.x, sellY - 34 - 3)} markerEnd="url(#arrow-SELL)" />
                </g>
              </svg>
              {nodes.filter((n) => n.parent).map((n) => (
                <span key={`q${n.key}`} className={`edge-qty edge-${edgeOf(n)}`} style={{ left: n.x, top: n.y + T_H / 2 + 14 }}>
                  ×{n.node.qty.toLocaleString("en-US")}
                </span>
              ))}
              {nodes.map((n) => {
                const saved = savedOf(n.node);
                const share = flip.cost > 0 ? n.node.total / flip.cost : 0;
                const isRoot = n.parent === null;
                return (
                  <div key={n.key} onPointerDown={(e) => e.stopPropagation()}
                    className={`tcard tcard-${n.node.method} ${isRoot ? "tcard-root" : ""} ${lit.has(n.key) ? "lit" : ""} ${hot && !lit.has(n.key) ? "dim" : ""}`}
                    style={{ left: n.x - T_W / 2, top: n.y - T_H / 2, width: T_W, height: T_H }}
                    onMouseEnter={() => setHot(n.key)} onMouseLeave={() => setHot(null)}>
                    <div className="tcard-head">
                      <span className="wstep">{n.step}</span>
                      <Slot item={n.node.item} size={36} />
                      <div className="tcard-title">
                        <div className="tcard-name" style={{ color: colour(items(n.node.item).rarity) }}>{items(n.node.item).name}</div>
                        <div className="tcard-how">
                          {n.node.method === "BUY" ? (
                            <><VenueChip venue={n.node.venue} /> <span className="muted">buy {n.node.qty.toLocaleString("en-US")}</span></>
                          ) : (
                            <span className="chip craft">
                              {n.node.method === "FORGE" ? `forge ${duration(n.node.forgeSeconds ?? 0)}` : `craft ${n.node.qty.toLocaleString("en-US")}`}
                            </span>
                          )}
                        </div>
                      </div>
                    </div>
                    <div className="tcard-row">
                      <span className="muted">{coins(n.node.unit)} each</span>
                      <b>{coins(n.node.total)}</b>
                    </div>
                    <div className="share" data-tip={`${Math.round(share * 100)}% of the total cost`}>
                      <span style={{ width: `${Math.min(100, share * 100)}%` }} />
                    </div>
                    <div className="tcard-row small">
                      {n.node.method === "BUY" ? (
                        <span className="muted">{Math.round(share * 100)}% of cost · {VENUE_LABEL[n.node.venue ?? "NPC"]}</span>
                      ) : saved > 0 ? (
                        <span className="gain">saves {coins(saved)} vs buying</span>
                      ) : (
                        <span className="muted">same as buying</span>
                      )}
                    </div>
                  </div>
                );
              })}
              <div className={`tcard tcard-sell ${hot === "sell" ? "lit" : ""}`} onPointerDown={(e) => e.stopPropagation()}
                style={{ left: root.x - T_W / 2, top: sellY - 34, width: T_W }}
                onMouseEnter={() => setHot("sell")} onMouseLeave={() => setHot(null)}>
                <div className="tcard-row"><span>Sell on {(flip.sells ? VENUE_LABEL[flip.sells] : "the market")}</span><b>{coins(flip.value)}</b></div>
                <div className="tcard-row small"><span className="muted">after tax, minus cost</span><b className="gain">+{coins(flip.profit)}</b></div>
              </div>
            </div>
            <div className="web-tools" onPointerDown={(e) => e.stopPropagation()}>
              <button onClick={() => zoomAt(1 / 1.2)} data-tip="Zoom out (−)">−</button>
              <span className="web-zoom">{Math.round(view.k * 100)}%</span>
              <button onClick={() => zoomAt(1.2)} data-tip="Zoom in (+)">+</button>
              <button onClick={fit} data-tip="Fit to window (0)">Fit</button>
              <span className="muted small">drag to move · wheel to zoom</span>
            </div>
          </div>
          <ol className="web-steps">
            {ordered.map((n) => {
              const saved = savedOf(n.node);
              return (
                <li key={n.key} className={lit.has(n.key) ? "lit" : ""} onMouseEnter={() => setHot(n.key)} onMouseLeave={() => setHot(null)}>
                  <span className="wstep">{n.step}</span>
                  <Slot item={n.node.item} size={26} />
                  <span className="web-step-text">
                    {stepText(n, items(n.node.item).name)}
                    <span className="muted small">
                      {n.node.method === "BUY" ? ` · ${VENUE_LABEL[n.node.venue ?? "NPC"]}, ${coins(n.node.unit)} each` : ""}
                      {saved > 0 ? ` · saves ${coins(saved)}` : ""}
                    </span>
                  </span>
                  <span className="num">{coins(n.node.total)}</span>
                </li>
              );
            })}
            <li className={`web-final ${hot === "sell" ? "lit" : ""}`} onMouseEnter={() => setHot("sell")} onMouseLeave={() => setHot(null)}>
              <span className="wstep">✓</span>
              <span className="web-step-text">Sell on {(flip.sells ? VENUE_LABEL[flip.sells] : "the market")} for {coins(flip.value)} after tax</span>
              <span className="num gain">+{coins(flip.profit)}</span>
            </li>
          </ol>
        </div>
      </div>
    </div>
  );
}

