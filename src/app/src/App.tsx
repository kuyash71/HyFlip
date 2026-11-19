import { isTauri } from "@tauri-apps/api/core";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createClient, type ApiClient, type FlipType, type ForgeSlots, type Opportunity, type Profile, type Status, type TradeSummary } from "./api/client";
import { DEV_CONNECTION, resolveConnection } from "./api/connection";
import { loadAppearance, saveAppearance } from "./appearance";
import { trackRecord } from "./calc/trackRecord";
import { CalcScreen } from "./screens/calc";
import { CreditsScreen } from "./screens/credits";
import { FlipperScreen } from "./screens/flipper";
import { JournalScreen } from "./screens/journal";
import { ManagerScreen } from "./screens/manager";
import { SettingsScreen, type ThemeChoice } from "./screens/settings";
import { health, StatusScreen } from "./screens/status";
import {
  ApiContext, CatalogContext, coins, errorText, ForgeContext, IconContext, KeptContext, ProfileContext, Slot, TipLayer, ToastContext,
  typing, useApi, usePolling, useStored, type IconAtlas, type ItemMeta, type Rarity, type ToastTone,
} from "./ui/common";
import { PixelIcon, ThemeContext, type Theme } from "./ui/pixel";
import {
  ALL_TABS, LEFT, Loading, Palette, Rail, RIGHT, ShortcutHelp, TAB_HINT, type Badge, type BootStep, type PaletteEntry, type Tab,
} from "./ui/shell";

const RETRY_MS = 1000;
/** The desktop app starts the server itself; give it this long before reporting a failure. */
const MAX_ATTEMPTS_DESKTOP = 60;
const FLIPPERS: FlipType[] = ["forge", "bazaar", "craft", "npc"];
const isFlipper = (t: Tab): t is FlipType => (FLIPPERS as string[]).includes(t);

function useResolvedTheme(choice: ThemeChoice): Theme {
  const query = useMemo(() => matchMedia("(prefers-color-scheme: light)"), []);
  const [light, setLight] = useState(query.matches);
  useEffect(() => {
    const on = () => setLight(query.matches);
    query.addEventListener("change", on);
    return () => query.removeEventListener("change", on);
  }, [query]);
  return choice === "system" ? (light ? "light" : "dark") : choice;
}

interface Booted {
  client: ApiClient;
  catalog: Map<string, ItemMeta>;
}

/** Connects to the local server behind the loading screen, then shows the app. */
export function App() {
  const [choice, setChoice] = useState<ThemeChoice>(() => loadAppearance().theme);
  const theme = useResolvedTheme(choice);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);
  const onTheme = (t: ThemeChoice) => {
    setChoice(t);
    saveAppearance({ ...loadAppearance(), theme: t });
    // saveAppearance removes the attribute for "system"; put the resolved theme back.
    document.documentElement.dataset.theme = t === "system" ? (matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark") : t;
  };

  const [booted, setBooted] = useState<Booted | null>(null);
  const [steps, setSteps] = useState<BootStep[]>([
    { label: "Starting the local server", done: false },
    { label: "Loading NEU items", done: false },
    { label: "Reading your SkyBlock profile", done: false },
    { label: "Checking the collectors", done: false },
  ]);
  const [bootError, setBootError] = useState<string | null>(null);
  const [logFile, setLogFile] = useState<string | undefined>(undefined);
  const [attempt, setAttempt] = useState(0);
  const desktop = isTauri();

  useEffect(() => {
    let cancelled = false;
    const done = (i: number, label?: string) =>
      !cancelled && setSteps((ss) => ss.map((s, k) => (k === i ? { label: label ?? s.label, done: true } : s)));
    (async () => {
      let lastError = "";
      try {
        const connection = await resolveConnection();
        if (!cancelled) setLogFile(connection.logFile);
        const client = createClient(connection);
        let up = false;
        // In a browser the server is started by hand, so keep waiting for it.
        for (let n = 0; (!desktop || n < MAX_ATTEMPTS_DESKTOP) && !cancelled; n++) {
          try {
            await client.health();
            up = true;
            break;
          } catch (e) {
            lastError = errorText(e);
            if (!desktop && n === 3) {
              setSteps((ss) => ss.map((s, k) => (k === 0 ? { ...s, label: `Waiting for the server at ${DEV_CONNECTION.url}` } : s)));
            }
            await new Promise((r) => setTimeout(r, RETRY_MS));
          }
        }
        if (!up) throw new Error(lastError || "The server did not start");
        done(0);
        const catalog = new Map<string, ItemMeta>();
        try {
          const c = await client.catalog();
          for (const [id, [name, rarity]] of Object.entries(c.items)) catalog.set(id, { name: name ?? id, rarity: rarity as Rarity | null });
          done(1, `Loaded ${catalog.size.toLocaleString("en-US")} NEU items`);
        } catch {
          // First start: NEU is still downloading. Names come from the flip lists until then.
          done(1, "NEU items still downloading");
        }
        await client.profile().then(() => done(2), () => done(2));
        await client.status().then(() => done(3), () => done(3));
        await new Promise((r) => setTimeout(r, 350));
        if (!cancelled) setBooted({ client, catalog });
      } catch (e) {
        if (!cancelled) setBootError(errorText(e) || lastError);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [desktop, attempt]);

  return (
    <ThemeContext.Provider value={theme}>
      <div className="app-root">
        {booted ? (
          <ApiContext.Provider value={booted.client}>
            <CatalogContext.Provider value={booted.catalog}>
              <Main choice={choice} onTheme={onTheme} />
            </CatalogContext.Provider>
          </ApiContext.Provider>
        ) : (
          <Loading steps={steps}
            error={bootError ? <>The local server is not answering: {bootError}{logFile && <><br />Server log: <code>{logFile}</code></>}</> : undefined}
            onRetry={() => { setBootError(null); setSteps((ss) => ss.map((s) => ({ ...s, done: false }))); setAttempt((a) => a + 1); }} />
        )}
        <TipLayer />
      </div>
    </ThemeContext.Provider>
  );
}

function Main({ choice, onTheme }: { choice: ThemeChoice; onTheme: (t: ThemeChoice) => void }) {
  const api = useApi();
  const [tab, setTabState] = useStored<Tab>("tab", "forge");
  const [focus, setFocus] = useState<{ id: string; n: number } | null>(null);
  const [palette, setPalette] = useState(false);
  const [help, setHelp] = useState(false);
  const [toasts, setToasts] = useState<{ id: number; text: string; tone: ToastTone }[]>([]);
  const current = ALL_TABS.find((t) => t.id === tab) ?? ALL_TABS[1]!;
  const setTab = (t: Tab) => setTabState(t);

  const slots = usePolling<ForgeSlots>(() => api.forgeSlots(), 30_000, [api]);
  const profile = usePolling<Profile>(() => api.profile(), 60_000, [api]);
  const status = usePolling<Status>(() => api.status(), 60_000, [api]);
  const summary = usePolling<TradeSummary>(() => api.tradeSummary(), 300_000, [api]);
  const icons = useIconAtlas(api);
  const kept = useMemo(() => trackRecord(summary.data), [summary.data]);

  const toast = useCallback((text: string, tone: ToastTone = "ok") => {
    const id = Date.now() + Math.random();
    setToasts((ts) => [...ts.slice(-3), { id, text, tone }]);
    setTimeout(() => setToasts((ts) => ts.filter((t) => t.id !== id)), tone === "error" ? 6000 : 3200);
  }, []);

  // Ctrl+1…9 and Ctrl+0 open the rail tabs, Ctrl+F the finder, ? the shortcut list.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && !e.altKey) {
        const i = e.key === "0" ? 9 : Number(e.key) - 1;
        if (/^[0-9]$/.test(e.key) && ALL_TABS[i]) {
          e.preventDefault();
          setTab(ALL_TABS[i].id);
        } else if (e.key.toLowerCase() === "f") {
          e.preventDefault();
          setPalette((p) => !p);
        }
      } else if (e.key === "?" && !typing(e)) {
        setHelp((h) => !h);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const badges: Partial<Record<Tab, Badge>> = {};
  const ready = (slots.data?.slots ?? []).filter((s) => s.job?.finished).length;
  if (ready > 0) badges.manager = { text: String(ready), tone: "gain", title: `${ready} forge slot${ready > 1 ? "s" : ""} ready to collect` };
  if (status.data) {
    const now = Date.now();
    const hs = status.data.collectors.map((c) => health(c, now));
    const bad = hs.filter((h) => h === "FAILED").length;
    const late = hs.filter((h) => h === "SLOW").length;
    const keyBad = status.data.apiKey.state === "EXPIRED" || status.data.apiKey.state === "EXPIRING_SOON";
    badges.status = bad > 0
      ? { text: "!", tone: "loss", title: `${bad} collector${bad > 1 ? "s" : ""} failing` }
      : late > 0 || keyBad
        ? { text: "!", tone: "warn", title: keyBad ? "The API key expires soon" : `${late} collector${late > 1 ? "s" : ""} behind` }
        : { text: "", tone: "live", title: "All systems operational" };
  }

  const openFlip = (type: FlipType, id: string) => {
    setTab(type);
    setFocus({ id, n: Date.now() });
  };
  const linked = profile.data?.link.linked ?? null;
  const flipper = isFlipper(tab);

  return (
    <ToastContext.Provider value={toast}>
      <ForgeContext.Provider value={{ slots: slots.data, reload: slots.reload }}>
        <ProfileContext.Provider value={{ profile: profile.data, reload: profile.reload }}>
          <KeptContext.Provider value={kept}>
           <IconContext.Provider value={icons}>
            <div className="shell">
              <Rail tabs={LEFT} tab={tab} onTab={setTab} side="left" badges={badges} />
              <main className="center">
                <header className="titlebar">
                  <PixelIcon name={tab} size={22} />
                  <h1>{current.label}</h1>
                  <span className="titlebar-hint">{TAB_HINT[tab]}</span>
                  <button className="search-btn" onClick={() => setPalette(true)} aria-label="Find an item or tab"
                    data-tip="Find any item or tab. Works from everywhere." data-tip-title="Find (Ctrl+F)">
                    <PixelIcon name="search" size={20} />
                    <span>Find item or tab</span><kbd>Ctrl F</kbd>
                  </button>
                  <button className={`player ${linked ? "" : "player-out"}`} onClick={() => setTab("settings")}
                    data-tip={linked ? `Your linked Minecraft account and SkyBlock profile${linked.purse !== null ? ` · purse ${coins(linked.purse)}` : ""}. Click to open Settings.` : "Link your Minecraft account in Settings to read your profile."}>
                    <PixelIcon name="player" size={20} />
                    {linked ? (
                      <>
                        <span className="player-name">{linked.username}</span>
                        <span className="player-profile">{linked.cuteName}</span>
                      </>
                    ) : (
                      <span>Not linked</span>
                    )}
                  </button>
                </header>
                <section className={`content ${flipper ? "content-split" : ""} ${tab === "credits" ? "content-credits" : ""}`}>
                  {tab === "calc" && <CalcScreen onOpen={openFlip} />}
                  {flipper && <FlipperScreen key={`${tab}:${focus?.n ?? 0}`} type={tab} focus={focus?.id} />}
                  {tab === "journal" && <JournalScreen onSummary={() => summary.reload()} />}
                  {tab === "manager" && <ManagerScreen />}
                  {tab === "settings" && <SettingsScreen theme={choice} onTheme={onTheme} onChanged={() => { slots.reload(); status.reload(); }} />}
                  {tab === "status" && <StatusScreen />}
                  {tab === "credits" && <CreditsScreen />}
                </section>
              </main>
              <Rail tabs={RIGHT} tab={tab} onTab={setTab} side="right" badges={badges} />
            </div>
            {palette && <Finder onChoose={(e) => { setPalette(false); setTab(e.tab); if (e.id) setFocus({ id: e.id, n: Date.now() }); }} onClose={() => setPalette(false)} />}
            {help && <ShortcutHelp onClose={() => setHelp(false)} />}
            <div className="toasts" role="status" aria-live="polite">
              {toasts.map((t) => <div key={t.id} className={`toast toast-${t.tone}`}>{t.text}</div>)}
            </div>
           </IconContext.Provider>
          </KeptContext.Provider>
        </ProfileContext.Provider>
      </ForgeContext.Provider>
    </ToastContext.Provider>
  );
}

const FLIPPER_LABEL: Record<FlipType, string> = { forge: "Forge Flipper", bazaar: "Bazaar Flipper", craft: "Craft Flipper", npc: "NPC Flipper" };
/** Flip lists for the finder, kept for two minutes. */
let finderCache: { at: number; rows: { type: FlipType; o: Opportunity }[] } | null = null;

/** Ctrl+F: tabs and every flip of the four flippers. */
function Finder({ onChoose, onClose }: { onChoose: (e: PaletteEntry) => void; onClose: () => void }) {
  const api = useApi();
  const [rows, setRows] = useState(finderCache?.rows ?? []);
  const loading = useRef(false);
  useEffect(() => {
    if ((finderCache && Date.now() - finderCache.at < 120_000) || loading.current) return;
    loading.current = true;
    void Promise.all(FLIPPERS.map((type) => api.flips(type, { limit: 500, includeLocked: true }).then((l) => l.items.map((o) => ({ type, o })), () => [])))
      .then((all) => {
        finderCache = { at: Date.now(), rows: all.flat() };
        setRows(finderCache.rows);
      });
  }, [api]);
  const entries: PaletteEntry[] = [
    ...ALL_TABS.map((t) => ({ kind: "tab" as const, tab: t.id, label: t.label, hint: "Open tab", icon: <PixelIcon name={t.id} size={22} /> })),
    ...rows.map(({ type, o }) => ({
      kind: "flip" as const,
      tab: type as Tab,
      id: o.id,
      label: o.name,
      hint: `${FLIPPER_LABEL[type]} · ${coins(o.netProfit)} profit`,
      icon: <Slot item={o.outputId} size={24} name={o.name} />,
    })),
  ];
  return <Palette entries={entries} onChoose={onChoose} onClose={onClose} />;
}

/**
 * Loads the item icon atlas once, and again after a pack import. The server rebuilds the atlas when it finds a
 * newer pack or NEU updates, so the index is checked every 5 minutes; the image is only fetched when `builtAt` changes.
 */
function useIconAtlas(api: ApiClient): { atlas: IconAtlas | null; reload: () => void } {
  const [atlas, setAtlas] = useState<IconAtlas | null>(null);
  const [tick, setTick] = useState(0);
  const built = useRef(0);
  useEffect(() => {
    let cancelled = false;
    let url: string | null = null;
    const load = async () => {
      try {
        const index = await api.icons();
        if (cancelled) return;
        if (!index) {
          built.current = 0;
          setAtlas(null);
          return;
        }
        if (index.builtAt === built.current) return;
        const blob = await api.iconAtlas(index.builtAt);
        if (cancelled) return;
        url = URL.createObjectURL(blob);
        built.current = index.builtAt;
        setAtlas((old) => {
          if (old) URL.revokeObjectURL(old.url);
          return { url: url!, cell: index.cell, columns: index.columns, items: index.items, glint: new Set(index.glint ?? []) };
        });
      } catch {
        // No icons is fine: items keep their letter.
      }
    };
    void load();
    const timer = setInterval(load, 5 * 60_000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [api, tick]);
  const reload = useCallback(() => {
    built.current = -1;
    setTick((t) => t + 1);
  }, []);
  return { atlas, reload };
}

