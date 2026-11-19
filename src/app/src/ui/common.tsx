/** Shared state, helpers and small components of the UI. */
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import type { Alert, ApiClient, ForgeSlots, LiquidityLevel, Profile, Venue } from "../api/client";
import { formatBytes, formatClock, parseCoins, parseDuration, parseIsoDuration } from "../format";
import { ThemeContext } from "./pixel";

// ───────────────────────────── Formatting ─────────────────────────────

/** Coins in short form: 950, 12.3k, 4.56M, 1.20B; a real minus sign for losses. */
export function coins(n: number): string {
  if (!Number.isFinite(n)) return "—";
  const a = Math.abs(n);
  const s = n < 0 ? "−" : "";
  if (a >= 1e9) return `${s}${(a / 1e9).toFixed(2)}B`;
  if (a >= 1e6) return `${s}${(a / 1e6).toFixed(a >= 1e7 ? 1 : 2)}M`;
  if (a >= 1e4) return `${s}${(a / 1e3).toFixed(a >= 1e5 ? 0 : 1)}k`;
  if (a >= 100) return `${s}${Math.round(a).toLocaleString("en-US")}`;
  return `${s}${a.toFixed(a % 1 === 0 ? 0 : a >= 10 ? 1 : 2)}`;
}

export const exact = (n: number) => `${Math.round(n).toLocaleString("en-US")} coins`;

/** "45s", "30m", "4h 48m", "3d 2h". */
export function duration(sec: number): string {
  if (!Number.isFinite(sec)) return "—";
  const s0 = Math.max(0, Math.round(sec));
  if (s0 < 60) return `${s0}s`;
  // Round to whole minutes first, so 59m 59s reads "1h", not "60m".
  const s = Math.round(s0 / 60) * 60;
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (d > 0) return h === 0 ? `${d}d` : `${d}d ${h}h`;
  if (h === 0) return `${m}m`;
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}

export function pct(f: number | null | undefined): string {
  if (f === null || f === undefined || !Number.isFinite(f)) return "—";
  return `${Math.round(f * 100)}%`;
}

/** Quantities; fractional when a recipe yields several units. */
export function qty(q: number): string {
  return Number.isInteger(q) ? q.toLocaleString("en-US") : q.toLocaleString("en-US", { maximumFractionDigits: 2 });
}

/** Local clock time `seconds` from now: "18:40", "01:10 tomorrow". */
export function clockIn(seconds: number, timeZone?: string): string {
  return clockAt(Date.now() + seconds * 1000, timeZone);
}

/** Clock time of an instant: "18:40", "18:40 tomorrow", "18:40 yesterday", "18:40 Thu 20 Nov". */
export const clockAt = formatClock;
export const bytes = formatBytes;
export { parseCoins, parseDuration, parseIsoDuration };

/** "12 s ago", "5 min ago", "3 h ago", "2 d ago". */
export function ago(epochMs: number | null | undefined, now = Date.now()): string {
  if (epochMs === null || epochMs === undefined) return "never";
  const s = Math.max(0, Math.round((now - epochMs) / 1000));
  if (s < 60) return `${s} s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 48) return `${h} h ago`;
  return `${Math.round(h / 24)} d ago`;
}

export function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

export function typing(e: KeyboardEvent): boolean {
  const t = e.target as HTMLElement | null;
  return !!t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable);
}

export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

// ───────────────────────────── Shared state ─────────────────────────────

/** A value kept in localStorage; works (unsaved) when storage is blocked. */
export function useStored<T>(key: string, initial: T): [T, (v: T | ((p: T) => T)) => void] {
  const full = `hyflip.${key}`;
  const [value, setValue] = useState<T>(() => readStored(full, initial));
  const set = (next: T | ((p: T) => T)) =>
    setValue((prev) => {
      const v = typeof next === "function" ? (next as (p: T) => T)(prev) : next;
      try {
        localStorage.setItem(full, JSON.stringify(v));
      } catch {
        // Storage unavailable: keep it for this session only.
      }
      return v;
    });
  return [value, set];
}

export function readStored<T>(fullKey: string, initial: T): T {
  try {
    const raw = localStorage.getItem(fullKey);
    return raw === null ? initial : (JSON.parse(raw) as T);
  } catch {
    return initial;
  }
}

export type ToastTone = "ok" | "info" | "error";
export const ToastContext = createContext<(text: string, tone?: ToastTone) => void>(() => {});
export const useToast = () => useContext(ToastContext);

export const ApiContext = createContext<ApiClient | null>(null);
export function useApi(): ApiClient {
  const c = useContext(ApiContext);
  if (!c) throw new Error("No API client");
  return c;
}

/** Forge slots, shared by the rail badge, the Forge Flipper and the Forge Manager. */
export const ForgeContext = createContext<{ slots: ForgeSlots | null; reload: () => void }>({ slots: null, reload: () => {} });

/** The player context and link state; reload after settings change. */
export const ProfileContext = createContext<{ profile: Profile | null; reload: () => void }>({ profile: null, reload: () => {} });

export interface ItemMeta {
  name: string;
  rarity: Rarity | null;
}

/** Item icons from the player's SkyBlock resource pack: the atlas as an object URL, and how to reload it. */
export interface IconAtlas {
  url: string;
  cell: number;
  columns: number;
  items: Record<string, number>;
  glint: Set<string>;
}
export const IconContext = createContext<{ atlas: IconAtlas | null; reload: () => void }>({ atlas: null, reload: () => {} });

/**
 * The icon's CSS size in a slot of `slot` px (2 px border each side) on a screen with `dpr` device pixels per CSS
 * pixel. Atlas cells are 32 px: 16 px textures doubled, cubes and 32 px textures drawn at full size. So the icon
 * covers a whole multiple of 32 device pixels (or 16, half size, in very small slots), and every texel lands on
 * whole screen pixels even at 125% or 150% scaling.
 */
export function iconPixels(slot: number, dpr = 1): number {
  const inner = (slot - 4) * dpr;
  const device = inner >= 32 ? Math.floor(inner / 32) * 32 : 16;
  return device / dpr;
}

/** The screen's device pixel ratio, updated when the window moves to another screen or the zoom changes. */
export function useDevicePixelRatio(): number {
  const [dpr, setDpr] = useState(() => (typeof window === "undefined" ? 1 : window.devicePixelRatio || 1));
  useEffect(() => {
    if (typeof window === "undefined" || !window.matchMedia) return;
    let query = window.matchMedia(`(resolution: ${window.devicePixelRatio}dppx)`);
    const update = () => {
      setDpr(window.devicePixelRatio || 1);
      query.removeEventListener("change", update);
      query = window.matchMedia(`(resolution: ${window.devicePixelRatio}dppx)`);
      query.addEventListener("change", update);
    };
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return dpr;
}

/** CSS that shows cell `index` of the atlas at `px` px. */
export function iconStyle(atlas: Pick<IconAtlas, "url" | "cell" | "columns">, index: number, px: number) {
  const col = index % atlas.columns;
  const row = Math.floor(index / atlas.columns);
  return {
    width: px,
    height: px,
    backgroundImage: `url("${atlas.url}")`,
    backgroundSize: `${atlas.columns * px}px auto`,
    backgroundPosition: `-${col * px}px -${row * px}px`,
  };
}

/** One atlas icon; the enchanted glint is masked by the icon itself, as in the game. */
function IconImage({ atlas, cell, px, glint }: { atlas: IconAtlas; cell: number; px: number; glint: boolean }) {
  const style = iconStyle(atlas, cell, px);
  const mask = glint
    ? {
        maskImage: style.backgroundImage, WebkitMaskImage: style.backgroundImage,
        maskSize: style.backgroundSize, WebkitMaskSize: style.backgroundSize,
        maskPosition: style.backgroundPosition, WebkitMaskPosition: style.backgroundPosition,
        maskRepeat: "no-repeat", WebkitMaskRepeat: "no-repeat",
      }
    : undefined;
  return (
    <span className="slot-icon" style={style}>
      {mask && <span className="glint" style={mask} />}
    </span>
  );
}

/** Item names and rarities from the NEU catalogue, plus names learned from flip details. */
export const CatalogContext = createContext<Map<string, ItemMeta>>(new Map());

/** "ENCHANTED_LAVA_BUCKET" → "Enchanted Lava Bucket" when nothing better is known. */
export function prettyId(id: string): string {
  return id.toLowerCase().replace(/[_;:-]+/g, " ").replace(/\b\w/g, (c) => c.toUpperCase()).trim();
}

/** A lookup of item names and rarities, for lists of many items. */
export function useItems(): (id: string) => ItemMeta {
  const catalog = useContext(CatalogContext);
  return (id) => {
    const hit = catalog.get(id);
    return { name: hit?.name ?? prettyId(id), rarity: hit?.rarity ?? null };
  };
}

export function useItem(id: string | undefined, fallbackName?: string): ItemMeta {
  const catalog = useContext(CatalogContext);
  if (!id) return { name: fallbackName ?? "", rarity: null };
  const hit = catalog.get(id);
  return { name: hit?.name ?? fallbackName ?? prettyId(id), rarity: hit?.rarity ?? null };
}

/** Calls `load` now, again every `intervalMs` (0 = never), and whenever `deps` change. */
/**
 * Last answers by cache key, kept while the app runs. A screen that is opened again starts from its last data and
 * refreshes in the background, so switching tabs does not show "Loading…" every time.
 */
const answers = new Map<string, unknown>();
const MAX_ANSWERS = 40;

function remember(key: string, value: unknown) {
  answers.delete(key);
  answers.set(key, value);
  if (answers.size > MAX_ANSWERS) answers.delete(answers.keys().next().value!);
}

/**
 * Loads `load()` now and every `intervalMs` (0: once). With a `cacheKey`, the last answer for that key is shown at
 * once while a fresh one loads.
 */
export function usePolling<T>(load: () => Promise<T>, intervalMs: number, deps: unknown[], cacheKey?: string) {
  const [data, setData] = useState<T | null>(() => (cacheKey ? (answers.get(cacheKey) as T | undefined) ?? null : null));
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let cancelled = false;
    if (cacheKey && answers.has(cacheKey)) setData(answers.get(cacheKey) as T);
    const run = async () => {
      setLoading(true);
      try {
        const value = await load();
        if (cancelled) return;
        if (cacheKey) remember(cacheKey, value);
        setData(value);
        setError(null);
      } catch (e) {
        if (!cancelled) setError(errorText(e));
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void run();
    // Pause polling while the window is hidden; catch up when it shows again.
    const timer = intervalMs > 0 ? setInterval(() => document.visibilityState === "visible" && void run(), intervalMs) : undefined;
    const onShow = () => document.visibilityState === "visible" && intervalMs > 0 && void run();
    document.addEventListener("visibilitychange", onShow);
    return () => {
      cancelled = true;
      if (timer) clearInterval(timer);
      document.removeEventListener("visibilitychange", onShow);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, tick, cacheKey, ...deps]);
  return { data, error, loading, reload: () => setTick((t) => t + 1) };
}

/** Current time, updated every `intervalMs`. */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}

// ───────────────────────────── Items ─────────────────────────────

export type Rarity = "COMMON" | "UNCOMMON" | "RARE" | "EPIC" | "LEGENDARY" | "MYTHIC" | "DIVINE" | "SPECIAL" | "VERY_SPECIAL" | "ULTIMATE" | "ADMIN";

const RARITY_COLOR: Record<string, string> = {
  COMMON: "#ffffff",
  UNCOMMON: "#55ff55",
  RARE: "#6f6fff",
  EPIC: "#c64fd6", // brighter than the game's #aa00aa for contrast on dark panels
  LEGENDARY: "#ffaa00",
  MYTHIC: "#ff55ff",
  DIVINE: "#55ffff",
  SPECIAL: "#ff5555",
  VERY_SPECIAL: "#ff5555",
  ULTIMATE: "#aa0000",
  ADMIN: "#aa0000",
};

/** The game's darker colour codes, readable on light panels and the grey slot. */
const RARITY_DARK: Record<string, string> = {
  COMMON: "#2b2b2b",
  UNCOMMON: "#007a00",
  RARE: "#1a1ab0",
  EPIC: "#7f007f",
  LEGENDARY: "#9a5a00",
  MYTHIC: "#a0208c",
  DIVINE: "#00707a",
  SPECIAL: "#a01818",
  VERY_SPECIAL: "#a01818",
  ULTIMATE: "#7a0000",
  ADMIN: "#7a0000",
};

export function useRarityColour(): (r: string | null) => string {
  const theme = useContext(ThemeContext);
  const table = theme === "light" ? RARITY_DARK : RARITY_COLOR;
  return (r) => table[r ?? "COMMON"] ?? table.COMMON!;
}

export const rarityText = (r: string | null) => (r ? r.replace("_", " ") : "");

/** An inventory slot with the item placeholder: first letter in the rarity colour. */
export function Slot({ item, count, size = 40, big = false, name }: { item?: string; count?: number; size?: number; big?: boolean; name?: string }) {
  const info = useItem(item, name);
  const colour = useRarityColour();
  const { atlas } = useContext(IconContext);
  const dpr = useDevicePixelRatio();
  const [hover, setHover] = useState(false);
  const letter = info.name.replace(/^Enchanted /, "").replace(/^[^A-Za-z0-9]+/, "").charAt(0).toUpperCase();
  const cell = item && atlas ? atlas.items[item] : undefined;
  return (
    <span className={`slot ${big ? "slot-big" : ""}`} style={{ width: size, height: size }}
      onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}>
      {item && atlas && cell !== undefined ? (
        <IconImage atlas={atlas} cell={cell} px={iconPixels(big ? size - 2 : size, dpr)} glint={atlas.glint.has(item)} />
      ) : item && (
        <span className="slot-letter" style={{ color: colour(info.rarity), fontSize: size * 0.5 }}>
          {letter}
          {info.name.startsWith("Enchanted") && <span className="glint" />}
        </span>
      )}
      {count !== undefined && count > 1 && <span className="slot-count">{count >= 1000 ? coins(count) : qty(Math.round(count * 100) / 100)}</span>}
      {hover && item && (
        <span className="mc-tooltip" role="tooltip">
          <span style={{ color: RARITY_COLOR[info.rarity ?? "COMMON"] }}>{info.name}</span>
          {count !== undefined && count > 1 && <span className="tt-dim">× {qty(count)}</span>}
          {info.rarity && <span className="tt-rarity" style={{ color: RARITY_COLOR[info.rarity] }}>{rarityText(info.rarity)}</span>}
        </span>
      )}
    </span>
  );
}

// ───────────────────────────── Chips ─────────────────────────────

export const VENUE_LABEL: Record<Venue, string> = { BAZAAR: "Bazaar", AUCTION: "Auction House", NPC: "NPC" };
export const VENUE_SHORT: Record<Venue, string> = { BAZAAR: "BZ", AUCTION: "AH", NPC: "NPC" };
export const VENUE_INFO: Record<Venue, string> = {
  BAZAAR: "Bazaar: buy and sell orders at a fixed tax of 1.25% (less with a Booster Cookie).",
  AUCTION: "Auction House: BIN listings. A listing fee, plus 1% claim tax above 1M.",
  NPC: "NPC shop: fixed prices, at most 640 of each item a day.",
};

export function VenueChip({ venue }: { venue: Venue | null | undefined }) {
  if (!venue) return <span className="venue venue-none" data-tip="No price is known for this item.">—</span>;
  return (
    <span className={`venue venue-${venue}`} data-tip={VENUE_INFO[venue]} data-tip-title={VENUE_LABEL[venue]}>
      {VENUE_SHORT[venue]}
    </span>
  );
}

/** Short labels, capitalised, for the alert tags. */
export const ALERT_LABEL: Record<string, string> = {
  MISSING_PRICE: "No price",
  ESTIMATED_PRICE: "Estimated",
  STALE_PRICE: "Old price",
  FEW_SALES: "Few sales",
  WIDE_SPREAD: "Wide spread",
  MANIPULATION: "Manipulated?",
  RECENT_CHANGE: "Price moved",
  THIN_BOOK: "Few orders",
  LOW_VOLUME: "Low volume",
  BULK_CRAFT: "Bulk craft",
  UNLOCK_UNKNOWN: "Unlock?",
  FORGE_FINISHED: "Ready",
};

/** What each alert means, in one line. */
export const ALERT_INFO: Record<string, string> = {
  MISSING_PRICE: "An input or the output has no market price, so the numbers are incomplete.",
  ESTIMATED_PRICE: "Valued at the median of recent sales, not a live listing.",
  STALE_PRICE: "A price was last seen a long time ago.",
  WIDE_SPREAD: "Buy and sell prices are far apart: orders fill slowly and the profit can shrink.",
  FEW_SALES: "Fewer than 3 clean sales in 7 days, so the price is a guess.",
  RECENT_CHANGE: "The price jumped in the last 10 minutes. Wait for it to settle.",
  MANIPULATION: "The price is far above its 7-day average. Someone may be pushing it.",
  THIN_BOOK: "Only a few orders on the book: one player can move the price.",
  LOW_VOLUME: "Little of it trades a day compared with one run.",
  BULK_CRAFT: "The parts to buy do not fit in one inventory (36 × 64): many rounds of buying and crafting, more than one session.",
  UNLOCK_UNKNOWN: "A requirement cannot be checked from your profile.",
  FORGE_FINISHED: "A forge slot is done.",
};

const ALERT_DANGER = new Set(["MANIPULATION", "RECENT_CHANGE", "MISSING_PRICE"]);

/** How much of the estimate flips with each alert kept; set from the journal when it has results. */
export const KeptContext = createContext<(types: string[]) => number>(() => 0.92);

/**
 * Each tag's tile: its own colour and 7×7 pixel symbol, so tags tell apart at a glance. Warm colours are the
 * dangerous ones (no price, manipulation, a price jump); the rest use distinct cool and neutral tones.
 */
export const TAG_LOOK: Record<string, { colour: string; glyph: string[] }> = {
  MISSING_PRICE: { colour: "#d33a32", glyph: ["..###..", ".#...##", "#...#.#", "#..#..#", "#.#...#", "##...#.", "..###.."] },
  MANIPULATION: { colour: "#b8327f", glyph: ["....#..", "...#.#.", "...#.#.", "..#...#", "..#....", ".#.....", "#......"] },
  RECENT_CHANGE: { colour: "#e0700f", glyph: ["...##..", "..##...", ".##....", "#####..", "...##..", "..##...", ".##...."] },
  ESTIMATED_PRICE: { colour: "#138f80", glyph: [".......", ".##..#.", "#..##..", ".......", ".##..#.", "#..##..", "......."] },
  STALE_PRICE: { colour: "#7a6a58", glyph: ["#######", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#.#.#.", "#######"] },
  FEW_SALES: { colour: "#b78400", glyph: [".......", "..###..", ".#.#.#.", ".#.#.#.", ".#.#.#.", "..###..", "......."] },
  WIDE_SPREAD: { colour: "#7446c4", glyph: [".......", ".#...#.", "##...##", "#######", "##...##", ".#...#.", "......."] },
  THIN_BOOK: { colour: "#2d68d0", glyph: ["#######", "#..#..#", "#..#..#", "#..#..#", "#..#..#", "#######", "...#..."] },
  BULK_CRAFT: { colour: "#3d4451", glyph: ["##.#.##", "##.#.##", ".......", "##.#.##", "##.#.##", ".......", "##.#.##"] },
  LOW_VOLUME: { colour: "#5f8c16", glyph: [".......", ".......", ".......", "#......", "#.#....", "#.#.#.#", "#######"] },
  UNLOCK_UNKNOWN: { colour: "#1489aa", glyph: ["..###..", ".#...#.", ".#.....", "#######", "###.###", "###.###", "#######"] },
  LOCKED: { colour: "#8a1c1c", glyph: ["..###..", ".#...#.", ".#...#.", "#######", "###.###", "###.###", "#######"] },
  FORGE_FINISHED: { colour: "#2a944a", glyph: [".......", "......#", ".....##", "#...##.", "##.##..", ".###...", "..#...."] },
};

/** A tag's square tile: bevelled like a Minecraft button, white pixel symbol. */
export function TagGlyph({ type, size = 18 }: { type: string; size?: number }) {
  const look = TAG_LOOK[type] ?? { colour: "#666", glyph: ["..###..", ".#...#.", "....#..", "...#...", "...#...", ".......", "...#..."] };
  return (
    <svg className="tag-glyph" width={size} height={size} viewBox="0 0 9 9" shapeRendering="crispEdges" aria-hidden="true">
      <rect width="9" height="9" fill={look.colour} />
      <rect width="9" height="1" fill="#fff" opacity="0.35" />
      <rect width="1" height="9" fill="#fff" opacity="0.25" />
      <rect y="8" width="9" height="1" fill="#000" opacity="0.35" />
      <rect x="8" width="1" height="9" fill="#000" opacity="0.25" />
      {look.glyph.flatMap((row, y) => [...row].map((c, x) => (c === "#" ? <rect key={`${x},${y}`} x={x + 1} y={y + 1} width="1" height="1" fill="#fff" /> : null)))}
    </svg>
  );
}

function tagTip(a: Alert, kept: (types: string[]) => number): string {
  const message = a.message.replace(/\.$/, "");
  return `${ALERT_INFO[a.type] ?? ""}\n${message}.\nFlips with this tag kept about ${Math.round(kept([a.type]) * 100)}% of their estimate.`;
}

/** A tag as a small square tile; the label and explanation show on hover. */
export function AlertTile({ a }: { a: Alert }) {
  const kept = useContext(KeptContext);
  const label = ALERT_LABEL[a.type] ?? a.type;
  return (
    <span className={`tag-tile ${ALERT_DANGER.has(a.type) ? "tag-danger" : ""}`} data-tip-title={label}
      data-tip={tagTip(a, kept)} role="img" aria-label={label}>
      <TagGlyph type={a.type} />
    </span>
  );
}

/** "Locked" in the same tile form. */
export function LockedTile({ requirements }: { requirements: string }) {
  return (
    <span className="tag-tile tag-danger" data-tip-title="Locked" data-tip={`Not unlocked yet: ${requirements}.`} role="img" aria-label="Locked">
      <TagGlyph type="LOCKED" />
    </span>
  );
}

/** A tag with its label, for the detail panel. */
export function AlertChip({ a }: { a: Alert }) {
  const danger = ALERT_DANGER.has(a.type);
  const kept = useContext(KeptContext);
  const label = ALERT_LABEL[a.type] ?? a.type;
  return (
    <span className={`alert-chip ${danger ? "alert-danger" : "alert-warn"}`} data-tip-title={label} data-tip={tagTip(a, kept)}>
      <TagGlyph type={a.type} size={15} />
      {label}
    </span>
  );
}

export const LIQUIDITY_INFO: Record<LiquidityLevel, string> = {
  HIGH: "High liquidity: thousands trade a day, so it sells within minutes.",
  MEDIUM: "Medium liquidity: sells within hours. Do not stack many copies.",
  LOW: "Low liquidity: few trades a day. One copy at a time, and expect to wait.",
  UNKNOWN: "Unknown liquidity: no trade volume is known yet.",
};

/** Daily trades per output unit from which HIGH counts as very high: about one every 43 s (the server's HIGH is 20). */
export const VERY_HIGH_RATIO = 2_000;

export type LiquidityTier = "UNKNOWN" | "LOW" | "MEDIUM" | "HIGH" | "VERY_HIGH";

export function liquidityTier(level: LiquidityLevel, dailyVolume?: number | null, perRun = 1): LiquidityTier {
  if (level === "HIGH" && dailyVolume != null && dailyVolume / Math.max(perRun, 1e-9) >= VERY_HIGH_RATIO) return "VERY_HIGH";
  return level;
}

const TIER_INFO: Record<LiquidityTier, string> = {
  ...LIQUIDITY_INFO,
  VERY_HIGH: "Very high liquidity: far more trades a day than you could sell. It goes in seconds.",
};

/** Filled rows of the gem (of 7) and the fill colour class per tier: red → orange → green → blue. */
const GEM_FILL: Record<LiquidityTier, number> = { UNKNOWN: 0, LOW: 2, MEDIUM: 4, HIGH: 6, VERY_HIGH: 7 };

/** Inner rows of the gem, top to bottom: [first column, width]. */
const GEM_ROWS: [number, number][] = [[3, 1], [2, 3], [1, 5], [1, 5], [1, 5], [2, 3], [3, 1]];
/** Pointed, faceted outline like the Minecraft emerald. */
const GEM_EDGE = "M3 0h1v1h1v1h1v1h1v3h-1v1h-1v1h-1v1h-1v-1h-1v-1h-1v-1h-1v-3h1v-1h1v-1h1z";

/** How easily the output sells: an emerald that fills from the bottom, its colour rising with the volume. */
export function LiquidityBar({ level, dailyVolume, perRun }: { level: LiquidityLevel; dailyVolume?: number | null; perRun?: number }) {
  const tier = liquidityTier(level, dailyVolume, perRun);
  const filled = GEM_FILL[tier];
  return (
    <span className={`gem gem-${tier}`} data-tip={TIER_INFO[tier]} role="img"
      aria-label={`${tier === "VERY_HIGH" ? "Very high" : tier.charAt(0) + tier.slice(1).toLowerCase()} liquidity`}>
      <svg width={14} height={18} viewBox="0 0 7 9" shapeRendering="crispEdges" aria-hidden="true">
        <path className="gem-edge" d={GEM_EDGE} />
        {GEM_ROWS.map(([x, w], i) => (
          <rect key={i} x={x} y={i + 1} width={w} height={1} className={7 - i <= filled ? "gem-on" : "gem-off"} />
        ))}
        {/* Facets: the right edge of each row in shade, a glint at the top left of the filled part. */}
        {GEM_ROWS.map(([x, w], i) => w > 1 && <rect key={`s${i}`} x={x + w - 1} y={i + 1} width={1} height={1} className="gem-shade" />)}
        {filled > 0 && <rect x={GEM_ROWS[7 - filled]![0]} y={8 - filled} width={1} height={1} className="gem-shine" />}
      </svg>
    </span>
  );
}

export function Stat({ label, value, tone, tip }: { label: string; value: ReactNode; tone?: "gain" | "loss"; tip?: string }) {
  return (
    <div className="stat" data-tip={tip} data-tip-title={tip ? label : undefined}>
      <div className={`stat-value ${tone ?? ""}`}>{value}</div>
      <div className="stat-label">{label}</div>
    </div>
  );
}

export function FromProfile({ on }: { on: boolean }) {
  return on ? (
    <span className="chip from-profile" data-tip="Read from your SkyBlock profile through the Hypixel API, refreshed every 10 minutes. Unlink to set it by hand.">
      From profile
    </span>
  ) : null;
}

/**
 * One tooltip for the whole app: any element with data-tip (and optional data-tip-title) shows a short
 * explanation after a moment, kept inside the window.
 */
export function TipLayer() {
  const [tip, setTip] = useState<{ title?: string; text: string; x: number; y: number; above: boolean } | null>(null);
  useEffect(() => {
    let timer = 0;
    let current: HTMLElement | null = null;
    const show = (el: HTMLElement) => {
      if (!el.isConnected) return;
      const r = el.getBoundingClientRect();
      const above = r.bottom + 140 > window.innerHeight;
      const x = Math.max(170, Math.min(window.innerWidth - 170, r.left + r.width / 2));
      setTip({ title: el.dataset.tipTitle, text: el.dataset.tip ?? "", x, y: above ? r.top - 8 : r.bottom + 8, above });
    };
    const enter = (e: Event) => {
      const el = (e.target as HTMLElement | null)?.closest?.("[data-tip]") as HTMLElement | null;
      if (el === current) return;
      current = el;
      window.clearTimeout(timer);
      setTip(null);
      if (el && el.dataset.tip) timer = window.setTimeout(() => show(el), 280);
    };
    const hide = () => {
      window.clearTimeout(timer);
      current = null;
      setTip(null);
    };
    document.addEventListener("mouseover", enter);
    document.addEventListener("focusin", enter);
    document.addEventListener("pointerdown", hide);
    document.addEventListener("wheel", hide, { passive: true });
    document.addEventListener("keydown", hide);
    return () => {
      window.clearTimeout(timer);
      document.removeEventListener("mouseover", enter);
      document.removeEventListener("focusin", enter);
      document.removeEventListener("pointerdown", hide);
      document.removeEventListener("wheel", hide);
      document.removeEventListener("keydown", hide);
    };
  }, []);
  if (!tip) return null;
  return (
    <div className={`tip ${tip.above ? "tip-above" : ""}`} role="tooltip" style={{ left: tip.x, top: tip.y }}>
      {tip.title && <div className="tip-title">{tip.title}</div>}
      <div className="tip-text">{tip.text}</div>
    </div>
  );
}

/** An error line with a retry button. */
export function ErrorLine({ text, onRetry }: { text: string; onRetry?: () => void }) {
  return (
    <p className="error-line" role="alert">
      <span>{text}</span>
      {onRetry && <button className="ghost small" onClick={onRetry}>Try again</button>}
    </p>
  );
}
