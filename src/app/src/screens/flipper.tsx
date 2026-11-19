/** The four flipper screens: a sortable list with column picker, and the detail panel of the chosen flip. */
import { useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import type { FlipExtras, FlipList, FlipType, Opportunity, OpportunityDetail } from "../api/client";
import {
  AlertChip, AlertTile, clockAt, coins, copyText, duration, ErrorLine, exact, ForgeContext, KeptContext, LIQUIDITY_INFO, LiquidityBar, LockedTile,
  parseCoins, parseDuration, pct, ProfileContext, Slot, Stat, typing, useApi, useItem, useItems, usePolling, useRarityColour,
  useStored, useToast, VENUE_LABEL, VenueChip,
} from "../ui/common";
import { PriceChart } from "./chart";
import { CraftWeb, RecipeCard, StepStrip } from "./recipe";
import { craftedNodes, treeOf } from "./tree";

type ColKey =
  | "cost" | "value" | "fees" | "profit" | "roi" | "seconds" | "perHour" | "perSession" | "perDay" | "runs" | "spread"
  | "fill" | "cashBack" | "volume" | "trend" | "swing" | "kept" | "needs" | "route" | "sells";
type SortKey = "name" | Exclude<ColKey, "route" | "sells" | "needs">;

/** A row of the list: the opportunity with its display extras and the share of the estimate it tends to keep. */
export interface Row {
  o: Opportunity;
  x: FlipExtras | undefined;
  kept: number;
}

interface Column {
  key: ColKey;
  label: string;
  /** One or two plain sentences shown on hover. */
  tip: string;
  types: FlipType[];
  num?: boolean;
  sort?: (r: Row) => number;
  cell: (r: Row) => ReactNode;
}

const ALL: FlipType[] = ["forge", "bazaar", "craft", "npc"];
/** About how long until the coins are back: forge time, then filling the orders. */
const cashBack = (o: Opportunity) => (o.durationSeconds ?? 0) + (o.fillSeconds ?? 0);
const spread = (o: Opportunity) => (o.cost > 0 ? (o.value - o.cost) / o.cost : 0);
const NONE = -Infinity;

const COLUMNS: Column[] = [
  { key: "cost", label: "Cost", types: ALL, num: true, sort: (r) => r.o.cost,
    tip: "Coins needed to start one run: all inputs at today's prices.",
    cell: (r) => <span data-tip={exact(r.o.cost)}>{coins(r.o.cost)}</span> },
  { key: "value", label: "Sells for", types: ALL, num: true, sort: (r) => r.o.value,
    tip: "What the output sells for, before tax.",
    cell: (r) => <span data-tip={exact(r.o.value)}>{coins(r.o.value)}</span> },
  { key: "fees", label: "Tax", types: ALL, num: true, sort: (r) => r.o.fees,
    tip: "Bazaar tax or Auction House fees taken from the sale.",
    cell: (r) => <span className="muted">{r.o.fees > 0 ? coins(r.o.fees) : "—"}</span> },
  { key: "profit", label: "Profit", types: ALL, num: true, sort: (r) => r.o.netProfit,
    tip: "Profit of one run after tax: sells for − cost − tax.",
    cell: (r) => <span className={`${r.o.netProfit >= 0 ? "gain" : "loss"} strong`} data-tip={exact(r.o.netProfit)}>{coins(r.o.netProfit)}</span> },
  { key: "seconds", label: "Time", types: ["forge"], num: true, sort: (r) => r.o.durationSeconds,
    tip: "Forge time with your Quick Forge level and calibration.",
    cell: (r) => (r.o.durationSeconds ? duration(r.o.durationSeconds) : "—") },
  { key: "perHour", label: "Per hour", types: ["forge"], num: true, sort: (r) => r.o.profitPerHour ?? NONE,
    tip: "Profit ÷ forge hours. Good when you can refill slots often.",
    cell: (r) => (r.o.profitPerHour === null ? "—" : <span className="gain">{coins(r.o.profitPerHour)}</span>) },
  { key: "perSession", label: "Per session", types: ["forge"], num: true, sort: (r) => r.o.profitPerSession,
    tip: "Profit one slot makes until your next session, counting refills you can do in that time.",
    cell: (r) => <span className="gain" data-tip={`${exact(r.o.profitPerSession)} · ${r.o.sessions} run${r.o.sessions === 1 ? "" : "s"}`}>{coins(r.o.profitPerSession)}</span> },
  { key: "runs", label: "Runs/day", types: ["bazaar", "npc"], num: true, sort: (r) => r.o.runsPerDay ?? NONE,
    tip: "Runs a day you can expect: the NPC limit (640) or your share of the market volume.",
    cell: (r) => (r.o.runsPerDay === null ? "—" : Math.floor(r.o.runsPerDay).toLocaleString("en-US")) },
  { key: "perDay", label: "Per day", types: ["bazaar", "npc"], num: true, sort: (r) => r.o.profitPerDay ?? NONE,
    tip: "Profit per run × runs a day.",
    cell: (r) => (r.o.profitPerDay === null ? "—" : <span className="gain" data-tip={exact(r.o.profitPerDay)}>{coins(r.o.profitPerDay)}</span>) },
  { key: "roi", label: "ROI", types: ALL, num: true, sort: (r) => r.o.roi ?? NONE,
    tip: "Return on investment: profit ÷ cost. 50% means 1,000 coins come back as 1,500.",
    cell: (r) => pct(r.o.roi) },
  { key: "spread", label: "Spread", types: ["bazaar"], num: true, sort: (r) => spread(r.o),
    tip: "Gap between the best sell offer and the best buy order, as a share of the buy price.",
    cell: (r) => pct(spread(r.o)) },
  { key: "fill", label: "Fill time", types: ["forge", "craft", "bazaar"], num: true, sort: (r) => -(r.o.fillSeconds ?? Infinity),
    tip: "About how long one run's buy orders and sell offer take to fill at today's volume.",
    cell: (r) => (r.o.fillSeconds !== null ? `~${duration(r.o.fillSeconds)}` : "—") },
  { key: "cashBack", label: "Coins back", types: ["forge", "craft"], num: true, sort: (r) => -cashBack(r.o),
    tip: "About when the coins are back in your purse: forge time plus order filling.",
    cell: (r) => (cashBack(r.o) > 0 ? `~${duration(cashBack(r.o))}` : "—") },
  { key: "volume", label: "Volume/day", types: ALL, num: true, sort: (r) => r.o.liquidity.dailyVolume ?? NONE,
    tip: "Units of the output traded a day (7-day average). Higher sells faster.",
    cell: (r) => {
      const v = r.o.liquidity.dailyVolume;
      return v === null ? <span className="muted">—</span> : v < 1 ? <span className="loss">&lt; 1</span> : coins(v);
    } },
  { key: "trend", label: "7d trend", types: ALL, num: true, sort: (r) => r.x?.trend ?? NONE,
    tip: "Output price now against its 7-day level. A big jump can fall back.",
    cell: (r) => {
      const t = r.x?.trend;
      if (t === null || t === undefined) return <span className="muted">—</span>;
      return <span className={Math.abs(t) < 0.015 ? "muted" : t > 0 ? "gain" : "loss"}>{t > 0.0005 ? "▲" : t < -0.0005 ? "▼" : "•"} {pct(Math.abs(t))}</span>;
    } },
  { key: "swing", label: "Daily swing", types: ["bazaar", "npc", "craft", "forge"], num: true, sort: (r) => -(r.x?.volatility ?? Infinity),
    tip: "How much the output's Bazaar price moves in a typical day. Lower is calmer.",
    cell: (r) => (r.x?.volatility == null ? <span className="muted">—</span> : <span className={r.x.volatility > 0.15 ? "warn-text" : ""}>±{pct(r.x.volatility)}</span>) },
  { key: "kept", label: "Track record", types: ALL, num: true, sort: (r) => r.kept,
    tip: "Share of the estimate that flips with these tags actually made, from your journal (or a default until it has results).",
    cell: (r) => <span className={r.kept < 0.6 ? "loss" : r.kept < 0.9 ? "warn-text" : ""}>{pct(r.kept)}</span> },
  { key: "needs", label: "Needs", types: ["forge", "craft"],
    tip: "What unlocks the recipe. Rows you cannot make yet are hidden unless “Locked too” is on.",
    cell: (r) => <span className="muted small">{r.x?.requirements.join(", ") || "—"}</span> },
  { key: "route", label: "Buys", types: ALL,
    tip: "Where the inputs are bought. Hover a tag for what it means.",
    cell: (r) => <span className="route">{r.o.buyVenues.map((v) => <VenueChip key={v} venue={v} />)}</span> },
  { key: "sells", label: "Sells", types: ALL,
    tip: "Where the output sells best, and how quickly it trades (bars).",
    cell: (r) => <span className="route"><VenueChip venue={r.o.sellVenue} /> <LiquidityBar level={r.o.liquidity.level} dailyVolume={r.o.liquidity.dailyVolume} perRun={r.o.outputCount} /></span> },
];

export const colTip = (k: ColKey) => COLUMNS.find((c) => c.key === k)!.tip;

const DEFAULT_HIDDEN: Record<FlipType, ColKey[]> = {
  forge: ["value", "fees", "perHour", "roi", "fill", "cashBack", "volume", "trend", "swing", "kept", "needs"],
  craft: ["value", "fees", "cashBack", "volume", "trend", "swing", "kept", "needs"],
  bazaar: ["value", "fees", "roi", "volume", "trend", "kept", "fill"],
  npc: ["value", "fees", "roi", "volume", "trend", "swing", "kept"],
};
const DEFAULT_SORT: Record<FlipType, SortKey> = { forge: "perSession", craft: "profit", bazaar: "perDay", npc: "perDay" };
/** Rows fetched per list; the list sorts and filters them here. */
const FETCH_LIMIT = 500;
/** Selection per flipper, so switching tabs and back keeps your place. */
const lastSelected: Partial<Record<FlipType, string>> = {};

/** The flip list of one type, refreshed every minute, with extras and track record per row. */
export function useFlipRows(type: FlipType, opts: { nextSessionIn?: string; includeLocked?: boolean } = {}) {
  const api = useApi();
  const kept = useContext(KeptContext);
  const list = usePolling<FlipList>(
    () => api.flips(type, { limit: FETCH_LIMIT, includeLocked: opts.includeLocked, nextSessionIn: opts.nextSessionIn, includeIncomplete: true }),
    60_000, [api, type, opts.nextSessionIn, opts.includeLocked]);
  const rows = useMemo<Row[]>(
    () => (list.data?.items ?? []).map((o) => ({ o, x: list.data!.extras?.[o.id], kept: kept(o.alerts.map((a) => a.type)) })),
    [list.data, kept]);
  return { ...list, rows };
}

export function FlipperScreen({ type, focus }: { type: FlipType; focus?: string }) {
  const toast = useToast();
  const { profile } = useContext(ProfileContext);
  const [session, setSession] = useStored<string>("nextSession", "");
  const sessionSeconds = parseDuration(session);
  const sessionBad = session.trim() !== "" && sessionSeconds === null;
  const [locked, setLocked] = useStored(`locked.${type}`, false);
  const { rows, error, data, reload, loading } = useFlipRows(type, {
    nextSessionIn: type === "forge" && sessionSeconds ? `PT${sessionSeconds}S` : undefined,
    includeLocked: type === "forge" || type === "craft" ? locked : undefined,
  });
  const [selected, setSelected] = useState<string | null>(focus ?? lastSelected[type] ?? null);
  const [search, setSearch] = useState("");
  const [budget, setBudget] = useStored<string>(`budget.${type}`, "");
  const [hideRisky, setHideRisky] = useStored(`hideAlerts.${type}`, false);
  const [onlyFav, setOnlyFav] = useState(false);
  const [favs, setFavs] = useStored<string[]>("favourites", []);
  const [hidden, setHidden] = useStored<ColKey[]>(`columns.${type}`, DEFAULT_HIDDEN[type]);
  const [sort, setSort] = useStored<{ key: SortKey; desc: boolean }>(`sort.${type}`, { key: DEFAULT_SORT[type], desc: true });
  const [showDetail, setShowDetail] = useStored("detail.shown", true);
  // null = automatic: 40% of the split, between 340 and 470 px. Dragging the divider sets a fixed width.
  const [storedW, setDetailW] = useStored<number | null>("detail.width", null);
  const [splitW, setSplitW] = useState(1200);
  const [colsOpen, setColsOpen] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);
  const splitRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const filtersRef = useRef<HTMLDivElement>(null);
  // The filter bar stays on top while the list scrolls; the column headers stick right under it.
  useEffect(() => {
    const bar = filtersRef.current;
    const list = listRef.current;
    if (!bar || !list) return;
    const set = () => list.style.setProperty("--filters-h", `${bar.offsetHeight}px`);
    set();
    const ro = new ResizeObserver(set);
    ro.observe(bar);
    return () => ro.disconnect();
  }, []);
  const items = useItems();

  // Pick the first row once data arrives, and keep a selection that dropped out of the list.
  useEffect(() => {
    if (rows.length > 0 && (!selected || !rows.some((r) => r.o.id === selected))) {
      if (!selected || data) setSelected(rows[0]!.o.id);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows]);
  useEffect(() => {
    if (selected) lastSelected[type] = selected;
  }, [selected, type]);
  useEffect(() => {
    const el = splitRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setSplitW(el.getBoundingClientRect().width));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const detailW = Math.min(storedW ?? Math.round(Math.max(340, Math.min(470, splitW * 0.4))), Math.max(340, splitW - 380));

  const max = parseCoins(budget);
  const budgetBad = budget.trim() !== "" && max === null;
  const cols = COLUMNS.filter((c) => c.types.includes(type));
  const shownCols = cols.filter((c) => !hidden.includes(c.key));
  const sortCol = COLUMNS.find((c) => c.key === sort.key && c.types.includes(type));
  const words = search.toLowerCase().split(/\s+/).filter(Boolean);
  const shown = rows
    .filter((r) => words.every((w) => `${r.o.name} ${r.o.outputId}`.toLowerCase().includes(w)))
    .filter((r) => !hideRisky || r.o.alerts.length === 0)
    .filter((r) => max === null || r.o.cost <= max)
    .filter((r) => !onlyFav || favs.includes(r.o.id))
    .sort((a, b) => {
      const d = sort.key === "name" || !sortCol?.sort ? a.o.name.localeCompare(b.o.name) : sortCol.sort(a) - sortCol.sort(b);
      return sort.desc && sortCol ? -d || 0 : d || 0;
    });
  const flip = rows.find((r) => r.o.id === selected) ?? null;
  const toggleFav = (id: string) => setFavs((f) => (f.includes(id) ? f.filter((x) => x !== id) : [...f, id]));
  const sortBy = (key: SortKey) => setSort((s) => ({ key, desc: s.key === key ? !s.desc : key !== "name" }));

  // List keys: ↑/↓ select, / search, D details, F favourite, C copy.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (typing(e) || e.ctrlKey || e.metaKey || e.altKey || document.querySelector(".overlay, .web-backdrop")) return;
      const i = shown.findIndex((r) => r.o.id === selected);
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        const next = shown[Math.max(0, Math.min(shown.length - 1, i + (e.key === "ArrowDown" ? 1 : -1)))];
        if (next) {
          setSelected(next.o.id);
          listRef.current?.querySelector(`[data-id="${CSS.escape(next.o.id)}"]`)?.scrollIntoView({ block: "nearest" });
        }
      } else if (e.key === "/") {
        e.preventDefault();
        searchRef.current?.focus();
      } else if (e.key.toLowerCase() === "d") {
        setShowDetail((v) => !v);
      } else if (e.key.toLowerCase() === "f" && selected) {
        toggleFav(selected);
      } else if (e.key.toLowerCase() === "c" && flip) {
        const name = flip.o.name || items(flip.o.outputId).name;
        void copyText(name).then((ok) => toast(ok ? `Copied “${name}”` : "Could not copy", ok ? "ok" : "error"));
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const onDivider = (e: React.PointerEvent<HTMLDivElement>) => {
    const el = e.currentTarget;
    const box = splitRef.current!.getBoundingClientRect();
    el.setPointerCapture(e.pointerId);
    el.classList.add("dragging");
    const move = (ev: PointerEvent) => setDetailW(Math.round(Math.max(340, Math.min(box.width - 380, box.right - ev.clientX - 5))));
    const up = () => {
      el.classList.remove("dragging");
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
    };
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
  };

  const hotm = profile?.context.hotmLevel;
  return (
    <div className="split" ref={splitRef}>
      <div className="list" ref={listRef}>
        <div className="filters" ref={filtersRef}>
          <input ref={searchRef} placeholder="Search items…  ( / )" value={search} onChange={(e) => setSearch(e.target.value)}
            onKeyDown={(e) => e.key === "Escape" && (setSearch(""), e.currentTarget.blur())} aria-label="Search items" />
          {type === "forge" && (
            <label className={`field ${sessionBad ? "bad" : ""}`}
              data-tip={`How long until you are back to refill the forge. Per session counts what fits in it. Empty uses Settings (${profile?.context.nextSessionIn ?? "8h"}).`}>
              Next session <input value={session} placeholder={profile?.context.nextSessionIn?.replace(/^PT/, "").toLowerCase() ?? "8h"} size={5}
                onChange={(e) => setSession(e.target.value)} />
            </label>
          )}
          <label className={`field ${budgetBad ? "bad" : ""}`} data-tip="Hide flips that cost more than this. Type 500k, 2.5m or 1b.">
            Budget <input placeholder="no limit" size={8} value={budget} onChange={(e) => setBudget(e.target.value)} />
          </label>
          <label className="check" data-tip="Hide every row that carries a warning tag.">
            <input type="checkbox" checked={hideRisky} onChange={(e) => setHideRisky(e.target.checked)} /> Hide alerts
          </label>
          {(type === "forge" || type === "craft") && (
            <label className="check" data-tip={`Also show recipes your profile has not unlocked yet${hotm !== null && hotm !== undefined ? ` (HOTM ${hotm})` : ""}.`}>
              <input type="checkbox" checked={locked} onChange={(e) => setLocked(e.target.checked)} /> Locked too
            </label>
          )}
          <label className="check" data-tip="Show only the items you starred (F, or click a ☆).">
            <input type="checkbox" checked={onlyFav} onChange={(e) => setOnlyFav(e.target.checked)} /> ★ Only
          </label>
          <span className="filters-right">
            <span className="popover-anchor">
              <button className="ghost" onClick={() => setColsOpen((o) => !o)} aria-expanded={colsOpen}
                data-tip="Pick which columns to show. Hover a name for what it means.">Columns ▾</button>
              {colsOpen && (
                <div className="popover" onMouseLeave={() => setColsOpen(false)}>
                  {cols.map((c) => (
                    <label key={c.key} className="check" data-tip={c.tip}>
                      <input type="checkbox" checked={!hidden.includes(c.key)}
                        onChange={() => setHidden((h) => (h.includes(c.key) ? h.filter((x) => x !== c.key) : [...h, c.key]))} /> {c.label}
                    </label>
                  ))}
                  <button className="ghost small" onClick={() => setHidden(DEFAULT_HIDDEN[type])}>Reset</button>
                </div>
              )}
            </span>
            <button className={`ghost toggle-detail ${showDetail ? "on" : ""}`} onClick={() => setShowDetail((v) => !v)}
              data-tip={showDetail ? "Hide the detail panel (D). The list takes the full width." : "Show the detail panel (D)."}>
              {showDetail ? "Details ▸" : "◂ Details"}
            </button>
          </span>
        </div>
        {error && <ErrorLine text={`Could not load the list: ${error}`} onRetry={reload} />}
        <table className="flips">
          <thead>
            <tr>
              <th className="star-col"><span data-tip="Favourites: click a ☆ or press F.">★</span></th>
              <th />
              <th aria-sort={sort.key === "name" ? (sort.desc ? "descending" : "ascending") : undefined}>
                <button className="th-btn" onClick={() => sortBy("name")} data-tip="Sort by name. Tags under a name are warnings: hover them.">Item {sort.key === "name" ? (sort.desc ? "▼" : "▲") : ""}</button>
              </th>
              {shownCols.map((c) => (
                <th key={c.key} className={c.num ? "num" : ""}
                  aria-sort={sort.key === c.key ? (sort.desc ? "descending" : "ascending") : undefined}>
                  {c.sort ? (
                    <button className="th-btn" onClick={() => sortBy(c.key as SortKey)} data-tip={c.tip} data-tip-title={c.label}>
                      {c.label} {sort.key === c.key ? (sort.desc ? "▼" : "▲") : ""}
                    </button>
                  ) : <span className="th-btn" data-tip={c.tip} data-tip-title={c.label}>{c.label}</span>}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {shown.map((r) => (
              <FlipRow key={r.o.id} r={r} cols={shownCols} selected={selected === r.o.id} fav={favs.includes(r.o.id)}
                onSelect={setSelected} onOpen={() => setShowDetail(true)} onFav={toggleFav} />
            ))}
          </tbody>
        </table>
        {!data && loading && !error ? (
          <div className="empty muted">Loading flips…</div>
        ) : shown.length === 0 && data ? (
          <div className="empty">
            {rows.length === 0 ? (
              <>No flips yet. Prices arrive within a minute of the first start; see Status.</>
            ) : (
              <>
                Nothing matches.{" "}
                <button className="ghost" onClick={() => { setSearch(""); setBudget(""); setHideRisky(false); setOnlyFav(false); }}>Clear filters</button>
              </>
            )}
          </div>
        ) : data ? (
          <div className="list-foot muted small">
            {shown.length} of {data.total > rows.length ? `${rows.length} (best of ${data.total})` : rows.length} shown ·
            {" "}prices from {clockAt(data.generatedAt)} · ↑ ↓ to move, ? for shortcuts
            <div className="attribution">
              Auction prices provided by{" "}
              <a href="https://sky.coflnet.com/data" target="_blank" rel="noreferrer">SkyCofl</a>
            </div>
          </div>
        ) : null}
      </div>
      {showDetail && (
        <>
          <div className="divider" role="separator" aria-orientation="vertical" data-tip="Drag to resize. Double-click to reset."
            onPointerDown={onDivider} onDoubleClick={() => setDetailW(null)} />
          <div className="detail-wrap" style={{ width: detailW }}>
            {flip ? (
              <Detail key={flip.o.id} type={type} row={flip} fav={favs.includes(flip.o.id)} onFav={() => toggleFav(flip.o.id)}
                onHide={() => setShowDetail(false)} nextSessionIn={type === "forge" && sessionSeconds ? `PT${sessionSeconds}S` : undefined} />
            ) : (
              <aside className="detail empty">{data ? "Pick a row to see its details." : ""}</aside>
            )}
          </div>
        </>
      )}
    </div>
  );
}

/** One list row; rendered apart so a selection change does not rebuild every cell's tooltip text. */
function FlipRow({ r, cols, selected, fav, onSelect, onOpen, onFav }: {
  r: Row; cols: Column[]; selected: boolean; fav: boolean; onSelect: (id: string) => void; onOpen: () => void; onFav: (id: string) => void;
}) {
  const info = useItem(r.o.outputId, r.o.name);
  return (
    <tr data-id={r.o.id} className={`${selected ? "selected" : ""} ${r.o.unlock === "LOCKED" ? "locked" : ""}`}
      onClick={() => onSelect(r.o.id)} onDoubleClick={onOpen}>
      <td className="star-col">
        <button className={`star ${fav ? "on" : ""}`} aria-label="Favourite" aria-pressed={fav}
          onClick={(e) => { e.stopPropagation(); onFav(r.o.id); }}>{fav ? "★" : "☆"}</button>
      </td>
      <td><Slot item={r.o.outputId} size={36} name={r.o.name} /></td>
      <td>
        <div className="name-cell">
          <div className="item-name">
            {r.o.name || info.name}
            {r.o.outputCount > 1 && <span className="muted small"> ×{r.o.outputCount}</span>}
          </div>
          {(r.o.alerts.length > 0 || r.o.unlock === "LOCKED") && (
            <div className="row-tags">
              {r.o.unlock === "LOCKED" && <LockedTile requirements={r.x?.requirements.join(", ") || "see the detail panel"} />}
              {uniqueAlerts(r.o.alerts).map((a) => <AlertTile key={a.type} a={a} />)}
            </div>
          )}
        </div>
      </td>
      {cols.map((c) => <td key={c.key} className={c.num ? "num" : ""}>{c.cell(r)}</td>)}
    </tr>
  );
}

/** One chip per alert type; the messages of repeats are joined. */
function uniqueAlerts(alerts: Opportunity["alerts"]): Opportunity["alerts"] {
  const by = new Map<string, Opportunity["alerts"][number]>();
  for (const a of alerts) {
    const seen = by.get(a.type);
    by.set(a.type, seen ? { ...seen, message: `${seen.message.replace(/\.$/, "")}; ${a.message}` } : a);
  }
  return [...by.values()];
}

function Detail({ type, row, fav, onFav, onHide, nextSessionIn }: {
  type: FlipType; row: Row; fav: boolean; onFav: () => void; onHide: () => void; nextSessionIn?: string;
}) {
  const api = useApi();
  const toast = useToast();
  const colour = useRarityColour();
  const o = row.o;
  const item = useItem(o.outputId, o.name);
  const detail = usePolling<OpportunityDetail>(() => api.flip(type, o.id, { nextSessionIn }), 0, [api, type, o.id, nextSessionIn]);
  const tree = useMemo(() => (detail.data ? treeOf(type, detail.data) : null), [detail.data, type]);
  const pages = useMemo(() => (tree ? craftedNodes(tree) : []), [tree]);
  // Steps are in the order they are done; open on the last one, the item itself.
  const [page, setPage] = useState<number | null>(null);
  const [full, setFull] = useState(false);
  const shownPage = Math.min(page ?? pages.length - 1, pages.length - 1);
  const timed = type === "forge";
  const name = o.name || item.name;

  const record = async () => {
    try {
      await api.recordTrade({ type, id: o.id, runs: 1 });
      toast(`${name} added to the journal`);
    } catch (e) {
      toast(`Could not add it: ${e instanceof Error ? e.message : String(e)}`, "error");
    }
  };

  return (
    <aside className="detail">
      <div className="detail-head">
        <Slot item={o.outputId} size={70} big name={o.name} />
        <div className="detail-title">
          <div className="title-line">
            <h2 style={{ color: colour(item.rarity ?? row.x?.rarity ?? null) }} className="mc-name">{name}</h2>
            <button className={`fav-btn ${fav ? "on" : ""}`} onClick={onFav} aria-pressed={fav}
              aria-label={fav ? "Remove from favourites" : "Add to favourites"}
              data-tip={fav ? "In your favourites. Click to remove (F)." : "Add to favourites (F). ★ Only in the list shows them."}>
              {fav ? "★" : "☆"}
            </button>
          </div>
          <div className="muted">
            {o.buyVenues.map((v) => VENUE_LABEL[v]).join(" + ") || "—"} → {o.sellVenue ? VENUE_LABEL[o.sellVenue] : "no market"} ·{" "}
            <span data-tip={LIQUIDITY_INFO[o.liquidity.level]}>{o.liquidity.level.charAt(0) + o.liquidity.level.slice(1).toLowerCase()} liquidity</span>
          </div>
        </div>
        <div className="detail-tools">
          <button onClick={() => void copyText(name).then((ok) => toast(ok ? `Copied “${name}”, paste it in /bz or /ah` : "Could not copy", ok ? "ok" : "error"))}
            data-tip="Copy the item name to paste in the in-game /bz or /ah search (C).">⧉ Copy</button>
          <button onClick={onHide} data-tip="Hide the detail panel (D)." aria-label="Hide details">▸</button>
        </div>
      </div>

      <div className="stats">
        <Stat label={type === "bazaar" ? "Profit per unit" : "Profit per run"} value={coins(o.netProfit)} tone={o.netProfit >= 0 ? "gain" : "loss"}
          tip="After Bazaar tax or Auction House fees." />
        {timed && <Stat label="Per session" value={coins(o.profitPerSession)} tone="gain" tip={colTip("perSession")} />}
        {o.profitPerDay !== null && <Stat label="Per day" value={coins(o.profitPerDay)} tone="gain" tip={colTip("perDay")} />}
        <Stat label="Cost" value={coins(o.cost)} tip={colTip("cost")} />
        <Stat label="ROI" value={pct(o.roi)} tip={colTip("roi")} />
        {timed && o.durationSeconds > 0 && <Stat label="Forge time" value={duration(o.durationSeconds)} tip={colTip("seconds")} />}
        {o.fillSeconds !== null && <Stat label="Orders fill in about" value={duration(o.fillSeconds)} tip={colTip("fill")} />}
        <Stat label="Track record" value={pct(row.kept)} tip={colTip("kept")} />
      </div>

      {o.alerts.length > 0 && (
        <ul className="alerts">
          {o.alerts.map((a, i) => (
            <li key={`${a.type}${i}`} className={`alert-line alert-${a.type}`}>
              <AlertChip a={a} /> {a.message}
            </li>
          ))}
        </ul>
      )}

      {detail.data && detail.data.unlock.checks.some((c) => c.state !== "MET") && (
        <ul className="alerts">
          {detail.data.unlock.checks.filter((c) => c.state !== "MET").map((c) => (
            <li key={c.requirement} className="alert-line">
              <span className={`chip ${c.state === "LOCKED" ? "locked-chip" : ""}`}>{c.state === "LOCKED" ? "Locked" : "Unknown"}</span>{" "}
              Needs {c.requirement}{c.player !== null ? ` (you have ${c.player})` : ""}
            </li>
          ))}
        </ul>
      )}

      {detail.error && <ErrorLine text={detail.error} onRetry={detail.reload} />}
      {!detail.data && !detail.error && <p className="muted">Loading recipe…</p>}

      {tree && pages.length > 0 && (
        <section className="recipe">
          <div className="section-head">
            <h3>Recipe</h3>
            <button className="full-btn" onClick={() => setFull(true)}>⛶ Show full</button>
          </div>
          <RecipeCard node={pages[shownPage]!} />
          {pages.length > 1 && <StepStrip pages={pages} page={shownPage} onPage={setPage} />}
        </section>
      )}

      {detail.data && (type === "bazaar" || type === "npc") && <GuideSteps steps={detail.data.guide.steps} />}

      <section>
        <h3>Price history</h3>
        <PriceChart itemId={o.outputId} />
      </section>

      {timed && <ForgeStart id={o.id} seconds={o.durationSeconds} name={name} />}

      <div className="actions">
        <button onClick={() => void record()}
          data-tip="Write this flip in the journal with today's estimate, to fill in what you really paid and got later.">＋ Add to journal</button>
      </div>
      {full && tree && detail.data && createPortal(
        <CraftWeb flip={{ item: o.outputId, tree, value: o.value - o.fees, cost: o.cost, profit: o.netProfit, roi: o.roi, sells: o.sellVenue }}
          onClose={() => setFull(false)} />,
        document.querySelector(".app-root") ?? document.body)}
    </aside>
  );
}

function GuideSteps({ steps }: { steps: string[] }) {
  if (steps.length === 0) return null;
  return (
    <section>
      <h3>How to do it</h3>
      <ol className="guide">
        {steps.map((s, i) => <li key={i}>{s}</li>)}
      </ol>
    </section>
  );
}

/** Pick the forge slot a job goes into; busy slots show when they free up. */
function ForgeStart({ id, seconds, name }: { id: string; seconds: number; name: string }) {
  const api = useApi();
  const { slots, reload } = useContext(ForgeContext);
  const toast = useToast();
  const list = slots?.slots ?? [];
  const usable = (s: (typeof list)[number]) => !s.job;
  const best = () => list.find(usable)?.slot ?? null;
  const [pick, setPick] = useState<number | null>(best);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const cur = list.find((s) => s.slot === pick);
    if (!cur || !usable(cur)) setPick(best());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slots, pick]);
  const chosen = list.find((s) => s.slot === pick);
  const zone = slots?.timeZone;
  const start = async () => {
    if (!chosen) return;
    setBusy(true);
    try {
      await api.startForgeJob({ slot: chosen.slot, recipeId: id });
      toast(`${name} started in slot ${chosen.slot} · ready at ${clockAt(Date.now() + seconds * 1000, zone)}`);
      reload();
    } catch (e) {
      toast(`Could not start it: ${e instanceof Error ? e.message : String(e)}`, "error");
    } finally {
      setBusy(false);
    }
  };
  if (!slots) return null;
  return (
    <section className="start-box">
      <div className="section-head">
        <h3>Start in a forge slot</h3>
        <span className="muted small">{list.filter(usable).length} of {list.length} free</span>
      </div>
      <div className="slot-pick" role="radiogroup" aria-label="Forge slot">
        {list.map((s) => {
          const j = s.job;
          const free = !j;
          return (
            <button key={s.slot} role="radio" aria-checked={pick === s.slot} disabled={!free}
              className={`sp ${pick === s.slot ? "on" : ""} ${!j ? "sp-free" : j.finished ? "sp-ready" : "sp-busy"}`}
              data-tip-title={`Slot ${s.slot}`}
              data-tip={!j ? "Empty: the job starts here." : j.finished ? `${j.name} is done. Collect it in the Forge Manager first.` : `${j.name}, ends ${clockAt(j.expectedEndAt, zone)}. Busy until then.`}
              onClick={() => setPick(s.slot)}>
              <span className="sp-no">{s.slot}</span>
              {j ? <Slot item={j.recipeId} size={26} name={j.name} /> : <span className="sp-empty" />}
              <span className="sp-state">{!j ? "Free" : j.finished ? "Ready" : duration(j.remainingSeconds)}</span>
            </button>
          );
        })}
      </div>
      <div className="start-row">
        <span className="muted small">
          {chosen ? <>Ready at <b className="fg">{clockAt(Date.now() + seconds * 1000, zone)}</b></> : "All slots are busy"}
        </span>
        <button className="primary" disabled={!chosen || busy} onClick={() => void start()}
          data-tip={`Tracks ${name} in the Forge Manager. Start it in game too.`}>
          Start{chosen ? ` in slot ${chosen.slot}` : ""}
        </button>
      </div>
    </section>
  );
}
