/** The app frame: loading screen, side rails, finder and shortcut list. */
import { useContext, useEffect, useRef, useState, type ReactNode } from "react";
import logo from "../assets/logo.png";
import { PixelIcon, rng, SPRITES, ThemeContext, Vine } from "./pixel";

/** Colours of the loading scene per theme: sky bands top to bottom, then everything that floats in it. */
const BOOT_SCENE = {
  dark: {
    sky: ["#070915", "#0b0f24", "#111633", "#181c42", "#20214d", "#2a2656", "#35295c"],
    star: ["#ffffff", "#a8dcff", "#fff0a8"],
    cloud: ["#232a52", "#2f3766"],
    spark: "#fff0a8",
  },
  light: {
    sky: ["#3f9fe6", "#55adeb", "#6dbbf0", "#86c9f4", "#a0d6f7", "#bce3fa", "#d8effc"],
    star: ["#ffffff", "#ffffff", "#fff4c2"],
    cloud: ["#ffffff", "#e3f2fc"],
    spark: "#ffffff",
  },
};

const COIN_FRAMES = [
  ["..kkk..", ".kglgk.", "kglwlgk", "kgllygk", "kglyygk", ".kgyyk.", "..kkk.."],
  ["..kk...", ".kglk..", ".kgwgk.", ".kglyk.", ".kgyyk.", ".kgyk..", "..kk..."],
  ["...k...", "..kgk..", "..kwk..", "..kgk..", "..kyk..", "..kyk..", "...k..."],
  ["...kk..", "..klgk.", ".kgwgk.", ".kylgk.", ".kyygk.", "..kygk.", "...kk.."],
];
const COIN_COLOURS: Record<string, string> = { k: "#5a3a00", g: "#ffcc33", l: "#ffe27a", w: "#fffbe0", y: "#c48a00" };

/**
 * Animated pixel sky behind the loading screen. The canvas is drawn at a quarter of the window's size and
 * scaled up without smoothing, so it stays crisp and cheap at any window size; it rebuilds on resize.
 */
function BootScene() {
  const theme = useContext(ThemeContext);
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = ref.current;
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx) return;
    const colours = BOOT_SCENE[theme];
    const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
    const rand = rng(42);
    let W = 0;
    let H = 0;
    let stars: { x: number; y: number; c: string; p: number }[] = [];
    let clouds: { x: number; y: number; w: number; layer: number; bumps: number[] }[] = [];
    let islands: { x: number; y: number; speed: number; phase: number; scale: number }[] = [];
    let coins: { x: number; y: number; vy: number; phase: number; drift: number }[] = [];
    let sparks: { x: number; y: number; life: number }[] = [];

    const newCoin = (anyHeight: boolean) => ({
      x: rand() * W,
      y: anyHeight ? rand() * H : H + 8 + rand() * 20,
      vy: 6 + rand() * 9,
      phase: rand() * 4,
      drift: (rand() - 0.5) * 3,
    });
    const build = () => {
      const px = Math.max(3, Math.round(Math.min(innerWidth, innerHeight) / 220));
      W = Math.ceil(innerWidth / px);
      H = Math.ceil(innerHeight / px);
      canvas.width = W;
      canvas.height = H;
      stars = Array.from({ length: Math.round((W * H) / 260) }, () => ({
        x: Math.floor(rand() * W), y: Math.floor(rand() * H * 0.75), c: colours.star[Math.floor(rand() * 3)]!, p: rand() * 6,
      }));
      clouds = Array.from({ length: Math.max(4, Math.round(W / 40)) }, () => {
        const w = 14 + Math.floor(rand() * 26);
        return { x: rand() * W, y: H * (0.15 + rand() * 0.7), w, layer: rand() < 0.5 ? 0 : 1,
          bumps: Array.from({ length: w }, (_, i) => Math.round(Math.sin((i / w) * Math.PI) * (2 + rand() * 1.5))) };
      });
      const islandCount = Math.max(2, Math.round(W / 140));
      islands = Array.from({ length: islandCount }, (_, i) => ({
        x: ((i + 0.3 + rand() * 0.4) * W) / islandCount, y: H * (0.2 + rand() * 0.6), speed: 1 + rand() * 1.5, phase: rand() * 6,
        scale: rand() < 0.4 ? 2 : 1,
      }));
      coins = Array.from({ length: Math.max(8, Math.round(W / 22)) }, () => newCoin(true));
      sparks = [];
    };

    const island = SPRITES.island!;
    const islandPalette = theme === "light" ? island.light ?? island.palette : island.palette;
    const drawSprite = (rows: string[], palette: Record<string, string>, x0: number, y0: number, scale = 1) => {
      rows.forEach((row, y) => {
        for (let x = 0; x < row.length; x++) {
          const fill = palette[row[x]!];
          if (!fill) continue;
          ctx.fillStyle = fill;
          ctx.fillRect(Math.round(x0) + x * scale, Math.round(y0) + y * scale, scale, scale);
        }
      });
    };

    let last = 0;
    let t = 0;
    let frame = 0;
    const draw = (now: number) => {
      const dt = Math.min(0.1, (now - last) / 1000 || 0);
      last = now;
      t += dt;
      // Sky: hard-edged bands, like the rest of the pixel art.
      const band = Math.ceil(H / colours.sky.length);
      colours.sky.forEach((c, i) => {
        ctx.fillStyle = c;
        ctx.fillRect(0, i * band, W, band + 1);
      });
      // Dithered seams between the bands.
      for (let i = 1; i < colours.sky.length; i++) {
        ctx.fillStyle = colours.sky[i]!;
        for (let x = (i % 2); x < W; x += 2) ctx.fillRect(x, i * band - 1, 1, 1);
      }
      for (const s of stars) {
        const tw = Math.sin(t * 2.2 + s.p);
        if (tw < -0.55) continue;
        ctx.fillStyle = s.c;
        ctx.fillRect(s.x, s.y, 1, 1);
        if (tw > 0.93) {
          ctx.fillRect(s.x - 1, s.y, 1, 1); ctx.fillRect(s.x + 1, s.y, 1, 1);
          ctx.fillRect(s.x, s.y - 1, 1, 1); ctx.fillRect(s.x, s.y + 1, 1, 1);
        }
      }
      const drawClouds = (layer: number) => {
        for (const c of clouds) {
          if (c.layer !== layer) continue;
          c.x += dt * (layer === 0 ? 2 : 4.5);
          if (c.x > W + 4) c.x = -c.w - 4;
          ctx.fillStyle = colours.cloud[layer]!;
          const x0 = Math.round(c.x);
          const y0 = Math.round(c.y);
          c.bumps.forEach((b, i) => ctx.fillRect(x0 + i, y0 - b, 1, b + 2));
        }
      };
      drawClouds(0);
      for (const is of islands) {
        is.x -= dt * is.speed;
        const w = 32 * is.scale;
        if (is.x < -w - 4) is.x = W + 4;
        const bob = Math.round(Math.sin(t * 0.9 + is.phase) * 1.5);
        drawSprite(island.rows, islandPalette, is.x, is.y + bob, is.scale);
      }
      drawClouds(1);
      for (const c of coins) {
        c.y -= dt * c.vy;
        c.x += Math.sin(t + c.phase) * c.drift * dt;
        if (c.y < -10) Object.assign(c, newCoin(false));
        const f = COIN_FRAMES[Math.floor(t * 8 + c.phase * 2) % COIN_FRAMES.length]!;
        drawSprite(f, COIN_COLOURS, c.x, c.y);
        if (rand() < dt * 0.6) sparks.push({ x: c.x + 3 + Math.round((rand() - 0.5) * 8), y: c.y + 3 + Math.round((rand() - 0.5) * 8), life: 0.6 });
      }
      sparks = sparks.filter((s) => (s.life -= dt) > 0);
      for (const s of sparks) {
        ctx.fillStyle = s.life > 0.3 ? colours.spark : "#ffcc33";
        const r = s.life > 0.3 ? 1 : 0;
        ctx.fillRect(Math.round(s.x), Math.round(s.y), 1, 1);
        if (r) {
          ctx.fillRect(Math.round(s.x) - 1, Math.round(s.y), 1, 1); ctx.fillRect(Math.round(s.x) + 1, Math.round(s.y), 1, 1);
          ctx.fillRect(Math.round(s.x), Math.round(s.y) - 1, 1, 1); ctx.fillRect(Math.round(s.x), Math.round(s.y) + 1, 1, 1);
        }
      }
      if (!still) frame = requestAnimationFrame(draw);
    };
    build();
    frame = requestAnimationFrame(draw);
    const onResize = () => build();
    addEventListener("resize", onResize);
    return () => {
      cancelAnimationFrame(frame);
      removeEventListener("resize", onResize);
    };
  }, [theme]);
  return <canvas ref={ref} className="boot-scene" aria-hidden="true" />;
}

export interface BootStep {
  label: string;
  done: boolean;
}

/** The loading screen: real start-up steps over the pixel sky. `error` replaces the bar with a message. */
export function Loading({ steps, error, onRetry }: { steps: BootStep[]; error?: ReactNode; onRetry?: () => void }) {
  const doneCount = steps.filter((s) => s.done).length;
  const current = steps.find((s) => !s.done);
  const percent = Math.round((doneCount / Math.max(1, steps.length)) * 100);
  const label = current ? `${current.label}…` : "Ready";
  return (
    <div className="boot">
      <BootScene />
      <div className="boot-center">
        <div className="boot-logo">
          <img src={logo} alt="HyFlip" width={300} />
        </div>
        <div className="boot-fullname">HyFlip · Hypixel SkyBlock Flipping Toolkit</div>
        {error ? (
          <div className="boot-error" role="alert">
            <div>{error}</div>
            {onRetry && <button onClick={onRetry}>Try again</button>}
          </div>
        ) : (
          <div className="boot-bar" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}
            aria-valuetext={label}>
            {/* The same text twice: light on the empty part, dark on the filled part, split at the fill edge. */}
            <span className="boot-text">{label}</span>
            <span className="boot-fill" style={{ clipPath: `inset(0 ${100 - percent}% 0 0)` }}>
              <span className="boot-text">{label}</span>
            </span>
          </div>
        )}
      </div>
    </div>
  );
}

export type Tab = "calc" | "forge" | "bazaar" | "craft" | "npc" | "journal" | "manager" | "settings" | "status" | "credits";

export const LEFT: { id: Tab; label: string }[] = [
  { id: "calc", label: "Flip Calc" },
  { id: "forge", label: "Forge Flipper" },
  { id: "bazaar", label: "Bazaar Flipper" },
  { id: "craft", label: "Craft Flipper" },
  { id: "npc", label: "NPC Flipper" },
];
export const RIGHT: { id: Tab; label: string }[] = [
  { id: "journal", label: "Journal" },
  { id: "manager", label: "Forge Manager" },
  { id: "settings", label: "Settings" },
  { id: "status", label: "Status" },
  { id: "credits", label: "Credits" },
];
export const ALL_TABS = [...LEFT, ...RIGHT];

export const TAB_HINT: Record<Tab, string> = {
  calc: "The best mix of flips for your coins, time and profile",
  forge: "Forge recipes ranked by profit per session",
  bazaar: "Buy-order / sell-offer spreads",
  craft: "Crafts that sell for more than their parts",
  npc: "NPC shops against the market",
  journal: "Your trades, and how the estimates held",
  manager: "Forge slots, plan and speed calibration",
  settings: "Profile, fees, thresholds and look",
  status: "Collectors, data and API key",
  credits: "What HyFlip does, and the projects it stands on",
};

/** Ctrl+1 … Ctrl+9, then Ctrl+0 for the tenth tab. */
export const tabKey = (i: number) => (i === 9 ? "0" : String(i + 1));

export type Badge = { text: string; tone: "gain" | "live" | "warn" | "loss"; title: string };

export function Rail({ tabs, tab, onTab, side, badges }: {
  tabs: typeof LEFT; tab: Tab; onTab: (t: Tab) => void; side: "left" | "right"; badges: Partial<Record<Tab, Badge>>;
}) {
  const offset = side === "left" ? 0 : LEFT.length;
  return (
    <nav className={`rail rail-${side}`} aria-label={side === "left" ? "Flippers" : "Tools"}>
      <Vine edge="top" seed={side === "left" ? 3 : 11} />
      {tabs.map((t, i) => {
        const badge = badges[t.id];
        return (
          <button key={t.id} className={`rail-btn ${tab === t.id ? "active" : ""}`} onClick={() => onTab(t.id)}
            aria-current={tab === t.id ? "page" : undefined} data-tip={`${TAB_HINT[t.id]}\nCtrl+${tabKey(offset + i)}`} data-tip-title={t.label}>
            <span className="rail-icon">
              <PixelIcon name={t.id} size={52} />
              {badge && <span className={`rail-badge rail-badge-${badge.tone}`} data-tip={badge.title}>{badge.text}</span>}
            </span>
            <span className="rail-label">{t.label}</span>
          </button>
        );
      })}
      <Vine edge="bottom" seed={side === "left" ? 7 : 19} />
    </nav>
  );
}

export interface PaletteEntry {
  kind: "tab" | "flip";
  tab: Tab;
  id?: string;
  label: string;
  hint: string;
  icon: ReactNode;
}

export function Palette({ entries, onChoose, onClose }: { entries: PaletteEntry[]; onChoose: (e: PaletteEntry) => void; onClose: () => void }) {
  const [q, setQ] = useState("");
  const [i, setI] = useState(0);
  const words = q.toLowerCase().split(/\s+/).filter(Boolean);
  const hits = entries.filter((e) => words.every((w) => `${e.label} ${e.hint}`.toLowerCase().includes(w))).slice(0, 9);
  const sel = Math.min(i, Math.max(0, hits.length - 1));
  return (
    <div className="overlay" onClick={onClose}>
      <div className="palette" role="dialog" aria-label="Find" onClick={(e) => e.stopPropagation()}>
        <div className="pal-input">
          <PixelIcon name="search" size={22} />
          <input autoFocus placeholder="Type an item or tab…" value={q}
          onChange={(e) => { setQ(e.target.value); setI(0); }}
          onKeyDown={(e) => {
            if (e.key === "ArrowDown") { e.preventDefault(); setI(Math.min(hits.length - 1, sel + 1)); }
            else if (e.key === "ArrowUp") { e.preventDefault(); setI(Math.max(0, sel - 1)); }
            else if (e.key === "Enter" && hits[sel]) onChoose(hits[sel]);
            else if (e.key === "Escape") onClose();
          }} />
        </div>
        <ul>
          {hits.map((h, k) => (
            <li key={`${h.kind}${h.id ?? h.tab}`} className={k === sel ? "on" : ""} onMouseEnter={() => setI(k)} onClick={() => onChoose(h)}>
              {h.icon}
              <span className="pal-label">{h.label}</span>
              <span className="muted small">{h.hint}</span>
            </li>
          ))}
          {hits.length === 0 && <li className="muted">No match</li>}
        </ul>
        <div className="pal-foot muted small">↑ ↓ to move · Enter to open · Esc to close</div>
      </div>
    </div>
  );
}

const SHORTCUTS: [string, string][] = [
  ["Ctrl 1 … 9, 0", "Open a rail tab (left column first, Ctrl 0 is Credits)"],
  ["Ctrl F", "Find an item or tab"],
  ["/", "Search the list"],
  ["↑ ↓", "Move through the list"],
  ["D", "Show or hide the detail panel"],
  ["F", "Favourite the selected item (or click its ☆)"],
  ["C", "Copy the item name (paste it in /bz or /ah)"],
  ["Enter", "Open the full crafting tree"],
  ["+ − 0", "Zoom in, out, fit (crafting tree)"],
  ["Esc", "Close a window"],
  ["?", "This list"],
];

export function ShortcutHelp({ onClose }: { onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div className="overlay" onClick={onClose}>
      <div className="help" role="dialog" aria-label="Keyboard shortcuts" onClick={(e) => e.stopPropagation()}>
        <div className="section-head"><h3>Keyboard shortcuts</h3><button onClick={onClose} aria-label="Close">✕</button></div>
        <table>
          <tbody>
            {SHORTCUTS.map(([k, what]) => <tr key={k}><td><kbd>{k}</kbd></td><td>{what}</td></tr>)}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ───────────────────────────── Flipper screens ─────────────────────────────
