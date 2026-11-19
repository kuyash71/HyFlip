/** Credits: what HyFlip does and the projects it stands on, in a pixel-art frame. */
import { useContext, useMemo, useState } from "react";
import { useToast } from "../ui/common";
import logo from "../assets/logo.png";
import { PixelIcon, rng, ThemeContext } from "../ui/pixel";

/** Repository URL, set at build time (VITE_REPO_URL); empty until the first publish. */
const REPO: string = import.meta.env.VITE_REPO_URL ?? "";
const VERSION: string = import.meta.env.VITE_APP_VERSION ?? "0.1.0";

const FEATURES: { icon: string; title: string; text: string }[] = [
  { icon: "calc", title: "Flip Calc", text: "Splits your coins across every flipper for the time you have." },
  { icon: "forge", title: "Forge Flipper", text: "Forge recipes ranked by profit per session at your real forge speed." },
  { icon: "bazaar", title: "Bazaar Flipper", text: "Order-to-order spreads with volume, fill time and manipulation checks." },
  { icon: "craft", title: "Craft Flipper", text: "Crafts worth more than their parts, with buy-or-craft for every input." },
  { icon: "npc", title: "NPC Flipper", text: "NPC shops against the market, inside the 640 a day limit." },
  { icon: "manager", title: "Forge Manager", text: "Every slot's timer, a plan for free slots and speed calibration." },
  { icon: "journal", title: "Journal & Backtest", text: "What you earned against the estimate, and how estimates held." },
  { icon: "status", title: "Market History", text: "Its own price history, so trends and alerts work offline too." },
  { icon: "settings", title: "Your Profile", text: "HOTM, Quick Forge, collections and slots read from your profile." },
  { icon: "player", title: "Safe by Design", text: "Read-only. It never touches your game or account, and runs on your PC." },
];

const THANKS: { name: string; role: string; link: [string, string]; badge: string; tone: string }[] = [
  { name: "Hypixel", role: "SkyBlock itself, and the Public API behind every price, auction and profile here.",
    link: ["developer.hypixel.net", "https://developer.hypixel.net/"], badge: "H", tone: "gold" },
  { name: "SkyCofl", role: "Auction sale history that fills offline gaps and warms up fresh installs.",
    link: ["Prices provided by SkyCofl", "https://sky.coflnet.com/data"], badge: "C", tone: "green" },
  { name: "NotEnoughUpdates", role: "The NEU item repository: every recipe, forge time and NPC shop (MIT).",
    link: ["NEU-REPO on GitHub", "https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO"], badge: "N", tone: "purple" },
  { name: "Hypixel SkyBlock Wiki", role: "The reference for recipes, taxes and game rules we check against.",
    link: ["hypixelskyblock.minecraft.wiki", "https://hypixelskyblock.minecraft.wiki/"], badge: "W", tone: "sky" },
  { name: "Font designers", role: "Jersey by Sarah Cadigan-Fried and JetBrains Mono by JetBrains, both under the SIL Open Font License.",
    link: ["Google Fonts", "https://fonts.google.com/"], badge: "Aa", tone: "pink" },
  { name: "Open source", role: "Built with Tauri, React, Spring Boot, SQLite and Flyway.",
    link: ["tauri.app", "https://tauri.app/"], badge: "{ }", tone: "sky" },
];

const SPLASHES = [
  "I can't stop Winnig!",
  "Play to never play again",
  "OnlyFlippers",
  "One more Zealot",
  "Try step on Grass!",
  "Farming is dead...",
  "99% of Flippers quit before profit",
  "Technoblade never Dies!",
  "Dante was right",
  "Flipmaxxing!",
  "Hate on Runes",
  "Read Art of War for better Flipping",
];

/** Night sky (dark theme) or day sky (light theme): pixel stars, a moon or sun, and clouds. */
function PixelSky() {
  const theme = useContext(ThemeContext);
  const cells = useMemo(() => {
    const rand = rng(42);
    const stars = Array.from({ length: 140 }, () => ({ x: Math.floor(rand() * 400), y: Math.floor(rand() * 150), big: rand() < 0.12, tw: rand() < 0.3 }));
    const clouds: { x: number; y: number; w: number }[] = [];
    for (let i = 0; i < 8; i++) clouds.push({ x: Math.floor(rand() * 380), y: 12 + Math.floor(rand() * 110), w: 18 + Math.floor(rand() * 28) });
    return { stars, clouds };
  }, []);
  return (
    <svg className="cr-sky" viewBox="0 0 400 168" preserveAspectRatio="xMidYMin slice" shapeRendering="crispEdges" aria-hidden="true">
      {theme === "dark" ? (
        <>
          {cells.stars.map((s, i) => (
            <rect key={i} x={s.x} y={s.y} width={s.big ? 2 : 1} height={s.big ? 2 : 1} className={s.tw ? "star-tw" : "star-px"} />
          ))}
          <rect x={340} y={18} width={14} height={14} className="moon" />
          <rect x={338} y={20} width={18} height={10} className="moon" />
          <rect x={343} y={21} width={3} height={3} className="moon-spot" />
          <rect x={348} y={26} width={2} height={2} className="moon-spot" />
        </>
      ) : (
        <>
          <rect x={334} y={16} width={16} height={16} className="sun" />
          <rect x={331} y={19} width={22} height={10} className="sun" />
          <rect x={337} y={13} width={10} height={22} className="sun" />
        </>
      )}
      {cells.clouds.map((c, i) => (
        <g key={i} className="cloud">
          <rect x={c.x} y={c.y} width={c.w} height={4} />
          <rect x={c.x + 4} y={c.y - 3} width={c.w - 10} height={3} />
        </g>
      ))}
    </svg>
  );
}

/** Splash font size: long ones shrink, as in Minecraft, so they stay off the logo. */
export function splashSize(text: string): number {
  return Math.max(13, Math.min(22, Math.round((22 * 18) / Math.max(text.length, 1))));
}

export function CreditsScreen() {
  const [splash, setSplash] = useState(() => Math.floor(Math.random() * SPLASHES.length));
  const toast = useToast();
  return (
    <div className="credits">
      <PixelSky />
      <header className="cr-hero">
        <div className="cr-title-wrap">
          <h1 className="cr-title"><img className="cr-logo" src={logo} alt="HyFlip" width={460} /></h1>
          <button className="cr-splash" style={{ fontSize: splashSize(SPLASHES[splash]!) }}
            onClick={() => setSplash((n) => (n + 1) % SPLASHES.length)}
            data-tip="Click for another one.">{SPLASHES[splash]}</button>
        </div>
        <p className="cr-sub">Hypixel SkyBlock Flipping Toolkit · v{VERSION} · GPL-3.0</p>
        <div className="cr-buttons">
          <a className="mc-btn" href={REPO || "#"} target="_blank" rel="noreferrer"
            onClick={(e) => { if (!REPO) { e.preventDefault(); toast("The repository link is added at the first publish", "info"); } }}
            data-tip="The project's source code.">Source code</a>
          <a className="mc-btn" href={REPO ? `${REPO}/issues` : "#"} target="_blank" rel="noreferrer"
            onClick={(e) => { if (!REPO) { e.preventDefault(); toast("Issues open with the repository", "info"); } }}
            data-tip="Report a bug or ask for a feature on the repository.">Report a bug</a>
          <a className="mc-btn" href="https://www.gnu.org/licenses/gpl-3.0.html" target="_blank" rel="noreferrer"
            data-tip="Free software: use, study, share and change it under the GPL-3.0.">License</a>
        </div>
      </header>

      <section className="cr-section">
        <h2 className="cr-h"><span>✦</span> WHAT IT DOES <span>✦</span></h2>
        <div className="adv-grid">
          {FEATURES.map((f, i) => (
            <div key={f.title} className="adv" style={{ animationDelay: `${i * 60}ms` }}>
              <span className="adv-icon"><PixelIcon name={f.icon} size={36} /></span>
              <div>
                <div className="adv-title">{f.title}</div>
                <div className="adv-text">{f.text}</div>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="cr-section">
        <h2 className="cr-h cr-thanks-h"><PixelIcon name="credits" size={28} /> THANKS TO <PixelIcon name="credits" size={28} /></h2>
        <div className="thanks-grid">
          {THANKS.map((t, i) => (
            <div key={t.name} className={`thanks t-${t.tone}`} style={{ animationDelay: `${300 + i * 80}ms` }}>
              <span className="thanks-badge">{t.badge}</span>
              <div className="thanks-body">
                <div className="thanks-name">{t.name}</div>
                <div className="thanks-role">{t.role}</div>
                <a className="thanks-link" href={t.link[1]} target="_blank" rel="noreferrer">↗ {t.link[0]}</a>
              </div>
            </div>
          ))}
        </div>
      </section>

      <footer className="cr-foot">
        <p>Made with <span className="cr-heart">♥</span> for the SkyBlock community.</p>
        <p className="small">HyFlip is not affiliated with Hypixel Inc. or Mojang AB. It only reads public data and never touches your game.</p>
      </footer>
    </div>
  );
}

