/** Pixel art: sprite data, the icon renderer and the rail vines. */
import { createContext, useContext, useLayoutEffect, useMemo, useRef, useState } from "react";

export type Theme = "dark" | "light";
/** The resolved theme (never "system"), for palettes that differ per theme. */
export const ThemeContext = createContext<Theme>("dark");

export interface Sprite {
  palette: Record<string, string>;
  /** Optional palette for the light theme; the dark one is used when it is missing. */
  light?: Record<string, string>;
  rows: string[];
}

export const SPRITES: Record<string, Sprite> = {
  // Forge with a red fire: Forge Flipper (user's drawing).
  forge: {
    palette: { o: "#36130b", b: "#61372e", c: "#000000", d: "#ff3b1f", e: "#eef069", y: "#f2ae61" },
    rows: [
      "................",
      ".......oo.......",
      ".......oo.......",
      ".......bb.......",
      "......oooo......",
      ".....bbbbbb.....",
      "....boocccooo...",
      "...bbbccccbbb...",
      "...oocccdccoo...",
      "..bbbdcdedcbbb..",
      "..ooccdeddccbo..",
      "..bbccdeecccbb..",
      ".obocccddcccoob.",
      ".bbbbbbbbbbbbbb.",
      ".oooboooboooboo.",
      "................",
    ],
  },
  // Coin sack: Bazaar Flipper (user's drawing).
  bazaar: {
    palette: { o: "#36130b", b: "#9c5b4e", c: "#61372e", d: "#f2ae61", e: "#c48037" },
    rows: [
      "................",
      "......oooo......",
      ".....obbbco.....",
      ".....ooocco.....",
      "........oco.....",
      ".......oco......",
      ".......oooo.....",
      "....ooobccco....",
      "...obbbccccco...",
      "..obcccddccco...",
      ".obbccddddccco..",
      ".obcccdeedcccco.",
      ".obccccddccccco.",
      "..occcccccccco..",
      "...oooooooooo...",
      "................",
    ],
  },
  // Crafting grid with a gold pickaxe recipe: Craft Flipper.
  craft: {
    palette: { o: "#36130b", w: "#85623d", s: "#4a2a20", y: "#f2ae61", Y: "#c48037", k: "#fff2c4", T: "#e0b080", t: "#9c5b4e" },
    rows: [
      "oooooooooooooooo",
      "oskyswskyswskyso",
      "oyyyYwyyyYwyyyYo",
      "oyYYYwyYYYwyYYYo",
      "osssswsssswsssso",
      "owwwwwwwwwwwwwwo",
      "osssswsssTwsssso",
      "osssswssTtwsssso",
      "osssswsTtswsssso",
      "osssswTtsswsssso",
      "owwwwwwwwwwwwwwo",
      "osssswsssTwsssso",
      "osssswssTtwsssso",
      "osssswsTtswsssso",
      "osssswTtsswsssso",
      "oooooooooooooooo",
    ],
  },
  // Villager head with a gold coin: NPC Flipper.
  npc: {
    palette: { o: "#36130b", S: "#d79c74", s: "#b07a58", n: "#9c5b4e", N: "#7a4434", w: "#ffffff", g: "#36c72c", r: "#61372e", R: "#85623d", y: "#f2ae61", Y: "#c48037", k: "#fff2c4" },
    rows: [
      "................",
      "...oooooooooo...",
      "..oSSSSSSSSSSo..",
      "..oSSSSSSSSSSo..",
      "..oSSSSSSSSSSo..",
      "..ooooooooooooo.",
      "..owgSSnnSSgwo..",
      "..oSSSSnnSSSSo..",
      "..osSSSnnSSSso..",
      "..osSSSNNSSSso..",
      "..ossssNNsssso..",
      "..orRRRNNRooo...",
      "..orrrrNNRoyyo..",
      "...ooooNNoykyYo.",
      ".......oooyyYYo.",
      "..........oooo..",
    ],
  },
  // Closed book with sky-blue corners: Journal.
  journal: {
    palette: { o: "#36130b", b: "#61372e", B: "#9c5b4e", h: "#85623d", p: "#f2e6c8", P: "#d8c49a", c: "#6ac3e6", C: "#3a8fc4", W: "#d6f3ff" },
    rows: [
      "................",
      "..ooooooooooooo.",
      "..ohBBBBBBBBBcco",
      "..ohBbbbbbbbbbco",
      "..ohBbooooooobbo",
      "..ohBboWccccobbo",
      "..ohBbocCCCcobbo",
      "..ohBboooooooBbo",
      "..ohBbbbbbbbbbbo",
      "..ohBbbbbbbbbbbo",
      "..ohBbbbbbbbbbbo",
      "..ohBbbbbbbbbbco",
      "..ohbbbbbbbbbcco",
      "..ooooocoooooooo",
      "..oPpppcCpppppo.",
      "...ooooCcooooo..",
    ],
  },
  // Forge with a sky-blue soul fire: Forge Manager.
  manager: {
    palette: { o: "#36130b", b: "#61372e", c: "#0b1a24", d: "#3a8fc4", e: "#d6f3ff", f: "#6ac3e6" },
    rows: [
      "................",
      ".......oo.......",
      ".......oo.......",
      ".......bb.......",
      "......oooo......",
      ".....bbbbbb.....",
      "....boocccooo...",
      "...bbbccccbbb...",
      "...oocccdccoo...",
      "..bbbdcdfdcbbb..",
      "..ooccdfeedcbo..",
      "..bbccdfeecdbb..",
      ".obocccdddccoob.",
      ".bbbbbbbbbbbbbb.",
      ".oooboooboooboo.",
      "................",
    ],
  },
  // Gear with a sky-blue hub: Settings.
  settings: {
    palette: { o: "#303030", m: "#a1a1a1", l: "#d4d4d4", s: "#545454", c: "#6ac3e6", C: "#3a8fc4" },
    rows: [
      "................",
      "......oooo......",
      "......omlo......",
      "..oo.omllmo.oo..",
      "..omoommmmoomo..",
      "...ommmllmmmo...",
      "..oommocccommoo.",
      ".ommmocCCCcommo.",
      ".ommlocCCCclmmo.",
      "..oommocccmmoo..",
      "...ommsllmsmo...",
      "..omoosmmsooso..",
      "..oo.osmmso.oo..",
      "......osso......",
      "......oooo......",
      "................",
    ],
  },
  // Globe: Status.
  status: {
    palette: { o: "#36130b", c: "#6ac3e6", C: "#3a8fc4", W: "#d6f3ff", g: "#5fbf3a", G: "#2f7a24" },
    rows: [
      "................",
      ".....oooooo.....",
      "...ooWcccccoo...",
      "..oWcccgggccco..",
      "..occggggGcccgo.",
      ".occcgggGGccgggo",
      ".occccggGccccGGo",
      ".occcccgccccccco",
      ".oggccccccccccco",
      ".ogggGcccccgggCo",
      ".oGggGgcccgggGCo",
      "..oGGcgcccCgGCo.",
      "..occccccCCCCCo.",
      "...ooCCCCCCCoo..",
      ".....oooooo.....",
      "................",
    ],
  },
  // Generic player silhouette for the title bar (no skins).
  player: {
    palette: { d: "#1d4e7a", m: "#3a8fd6", l: "#8fd0ff", g: "#ffcc33" },
    light: { d: "#1d4e7a", m: "#2f7fc4", l: "#6dbdf2", g: "#c48a00" },
    rows: [
      "................",
      ".....dddddd.....",
      "....dmmmmmmd....",
      "....dmllllmd....",
      "....dmllllmd....",
      "....dmmmmmmd....",
      ".....dddddd.....",
      "......dggd......",
      "...dddmmmmddd...",
      "..dmmmmmmmmmmd..",
      "..dmmmllllmmmd..",
      "..dmmmllllmmmd..",
      "..dmmmmmmmmmmd..",
      "..dddddddddddd..",
      "................",
      "................",
    ],
  },
  // Calculator: Flip Calc (user's drawing).
  calc: {
    palette: { o: "#36130b", b: "#c48037", c: "#85623d", d: "#e68383", e: "#303030", f: "#ffffff", g: "#2a400c", y: "#f2ae61" },
    rows: [
      "................",
      "...oooooooooo...",
      "..obbbbbbbbbbo..",
      "..obyybybbybbo..",
      "..obbyyyybybbo..",
      "..oobbbbbbbboo..",
      "..ocooooooooco..",
      "..occcccccccco..",
      "..ocdddceeeeco..",
      "..ocdddceffeco..",
      "..occccceeeeco..",
      "..ocgggceffeco..",
      "..ocgggceeeeco..",
      "..occcccccccco..",
      "...oooooooooo...",
      "................",
    ],
  },
  // Heart with sky-blue sparkles: Credits.
  credits: {
    palette: { o: "#36130b", r: "#e0303a", l: "#ff7a80", w: "#ffffff", d: "#a01818", c: "#6ac3e6", W: "#d6f3ff" },
    rows: [
      ".............c..",
      "............cWc.",
      "..ooo..ooo...c..",
      ".olllo.orrro....",
      "olwllrooorrrdo..",
      "olwlrrrrrrrrdo..",
      "ollrrrrrrrrrdo..",
      "orrrrrrrrrrrdo..",
      ".orrrrrrrrrdo...",
      "..orrrrrrrdo..c.",
      "...orrrrrdo..cWc",
      "....orrrdo....c.",
      ".....ordo.......",
      "......oo........",
      "................",
      "................",
    ],
  },
  // Magnifying glass: search.
  search: {
    palette: { d: "#8b8b9c", l: "#4fb3f6", w: "#ffffff", h: "#b98448", k: "#6b4423" },
    light: { d: "#5c5c6b", l: "#7cc4f5", w: "#ffffff", h: "#8a5a2b", k: "#4a2f17" },
    rows: [
      "................",
      "...dddd.........",
      "..dllwwd........",
      ".dllllwld.......",
      ".dlllllld.......",
      ".dlllllld.......",
      ".dlllllld.......",
      "..dlllld........",
      "...ddddhh.......",
      "........hh......",
      ".........khh....",
      "..........khh...",
      "...........khh..",
      "............kk..",
      "................",
      "................",
    ],
  },
  // Floating island for the Credits sky (32 × 19).
  island: {
    palette: { g: "#5fbf3a", G: "#3f8f2a", d: "#8a5a2b", D: "#5e3c1c", s: "#7d7d86", S: "#55555e", t: "#6b4423", l: "#3fa34d", L: "#6fd16a" },
    light: { g: "#6fd04a", G: "#4fa03a", d: "#9a6a3b", D: "#6e4c2c", s: "#8d8d96", S: "#65656e", t: "#7b5433", l: "#4fb35d", L: "#8fe18a" },
    rows: [
      "..............LLLL..............",
      "............LLlllLL.............",
      "...........LlllllllL............",
      "...........llllLlllll...........",
      "............lllllll.............",
      "..............tt................",
      "..............tt................",
      "....gggggggggggttgggggggggggg...",
      "...gGgggGggggggggggGgggggGggggg.",
      "...dddDddddddDdddddddDddddDddd..",
      "....dDdddsddddDddddsdddddDddd...",
      ".....DdssdddDdsssdddDddsdd......",
      "......sSsssSssssSsssSss.........",
      ".......sSssssSsssSsss...........",
      "........SsssSsssSss.............",
      "..........SssSsss...............",
      "...........SsSs.................",
      "............Ss..................",
      ".............S..................",
    ],
  },
};

/** One SVG path per colour, cached per sprite and theme: far fewer DOM nodes than a rect per pixel. */
const pathCache = new Map<string, { w: number; h: number; paths: [string, string][] }>();

function spritePaths(name: string, theme: Theme) {
  const key = `${name}|${theme}`;
  let hit = pathCache.get(key);
  if (hit) return hit;
  const sprite = SPRITES[name]!;
  const palette = theme === "light" ? sprite.light ?? sprite.palette : sprite.palette;
  const w = Math.max(...sprite.rows.map((r) => r.length));
  const h = sprite.rows.length;
  const byColour = new Map<string, string[]>();
  sprite.rows.forEach((row, y) => {
    // Runs of the same colour on a row become one rectangle.
    let x = 0;
    while (x < w) {
      const fill = palette[row[x] ?? "."];
      let end = x + 1;
      while (end < w && palette[row[end] ?? "."] === fill && fill) end++;
      if (fill) {
        const list = byColour.get(fill) ?? [];
        list.push(`M${x} ${y}h${end - x}v1h-${end - x}z`);
        byColour.set(fill, list);
      }
      x = end;
    }
  });
  hit = { w, h, paths: [...byColour.entries()].map(([fill, d]) => [fill, d.join("")]) };
  pathCache.set(key, hit);
  return hit;
}

export function PixelIcon({ name, size = 48 }: { name: string; size?: number }) {
  const theme = useContext(ThemeContext);
  if (!SPRITES[name]) return null;
  const { w, h, paths } = spritePaths(name, theme);
  return (
    <svg width={size} height={(size * h) / w} viewBox={`0 0 ${w} ${h}`} shapeRendering="crispEdges" aria-hidden="true">
      {paths.map(([fill, d]) => <path key={fill} fill={fill} d={d} />)}
    </svg>
  );
}

/** Deterministic pseudo-random numbers, so the vines look the same on every render. */
export function rng(seed: number): () => number {
  let t = seed * 0x9e3779b9;
  return () => {
    t = (t + 0x6d2b79f5) | 0;
    let x = Math.imul(t ^ (t >>> 15), 1 | t);
    x ^= x + Math.imul(x ^ (x >>> 7), 61 | x);
    return ((x ^ (x >>> 14)) >>> 0) / 4294967296;
  };
}

export const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5];

/** Size of one vine pixel in CSS pixels. Vines keep square pixels at any rail height. */
const VINE_PX = 4;

/**
 * Pixel ivy at the rail ends: a dithered sky-blue haze from the edge, with gold vines and leaves
 * hanging into the rail (or growing up from the bottom). It measures its box and grows to fit, so a
 * shorter rail end gets shorter vines instead of squashed pixels.
 */
export function Vine({ edge, seed }: { edge: "top" | "bottom"; seed: number }) {
  const box = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ w: 28, h: 16 });
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const measure = () => {
      const w = Math.max(8, Math.round(el.clientWidth / VINE_PX));
      const h = Math.max(4, Math.floor(el.clientHeight / VINE_PX));
      setSize((old) => (old.w === w && old.h === h ? old : { w, h }));
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const { w: W, h: H } = size;
  const cells = useMemo(() => {
    const rand = rng(seed);
    const grid = new Map<string, { x: number; y: number; c: string }>();
    const put = (x: number, y: number, c: string) => {
      if (x < 0 || x >= W || y < 0 || y >= H) return;
      grid.set(`${x},${y}`, { x, y, c });
    };
    // Haze: ordered dithering, dense at the edge and thinning out over about ten pixels.
    const hazeH = Math.min(H, 10);
    for (let y = 0; y < hazeH; y++) {
      for (let x = 0; x < W; x++) {
        const t = y / 10;
        const density = Math.pow(Math.max(0, 1 - t * 1.25), 2.4) * 0.8;
        if (density * 16 > BAYER[(y % 4) * 4 + (x % 4)]! + rand() * 2) put(x, y, t < 0.15 ? "sky-2" : "sky-1");
      }
    }
    // Vines: wavy gold stems with leaves, each its own length, spread across the width.
    const count = Math.max(2, Math.round(W / 9));
    for (let i = 0; i < count; i++) {
      const x0 = Math.round(((i + 0.5) * W) / count + (rand() - 0.5) * 3);
      const len = Math.max(3, Math.round(H * (0.45 + rand() * 0.5)) - 1);
      const phase = rand() * 6;
      let lastX = x0;
      for (let y = 0; y < len; y++) {
        const x = x0 + Math.round(Math.sin(y * 0.55 + phase) * 1.6);
        if (Math.abs(x - lastX) > 1) put(lastX + Math.sign(x - lastX), y, "gold-2");
        put(x, y, y === len - 1 ? "gold-3" : "gold-2");
        lastX = x;
        if (y > 1 && (y + i) % 3 === 0) {
          const side = (y + i) % 2 === 0 ? 1 : -1;
          put(x + side, y, "leaf-1");
          put(x + side * 2, y, "leaf-2");
          put(x + side, y - 1, "leaf-2");
        }
      }
      put(lastX, len, "gold-1");
    }
    return [...grid.values()];
  }, [seed, W, H]);
  return (
    <div ref={box} className={`vine vine-${edge}`} aria-hidden="true">
      <svg width={W * VINE_PX} height={H * VINE_PX} viewBox={`0 0 ${W} ${H}`} shapeRendering="crispEdges">
        <g transform={edge === "bottom" ? `translate(0 ${H}) scale(1 -1)` : undefined}>
          {cells.map((c) => <rect key={`${c.x},${c.y}`} x={c.x} y={c.y} width={1} height={1} className={`v-${c.c}`} />)}
        </g>
      </svg>
    </div>
  );
}
