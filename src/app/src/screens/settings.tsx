/**
 * Settings: everything the flippers assume about the player and the market rules (saved in the app over
 * config.yaml), the Minecraft account link and API key, appearance, and market history import/export.
 */
import { useContext, useEffect, useState, type ReactNode } from "react";
import type { ApiKeyState, IconIndex, PackCandidate, Settings, SettingsPatch, SettingsView } from "../api/client";
import {
  ago, bytes, duration, ErrorLine, FromProfile, IconContext, parseCoins, parseDuration, parseIsoDuration, pct, ProfileContext, Slot,
  useApi, usePolling, useStored, useToast,
} from "../ui/common";
import { PixelIcon } from "../ui/pixel";

export type ThemeChoice = "system" | "dark" | "light";

const SECTIONS = ["Profile", "Forge", "Trading & fees", "Flippers", "Alerts", "Item icons", "Market history"] as const;
type Section = (typeof SECTIONS)[number];

type Kind = "text" | "int" | "number" | "pct" | "coins" | "duration" | "select";

interface FieldDef {
  path: [keyof Settings, ...string[]];
  label: string;
  kind: Kind;
  /** Blank means "not set" (null). */
  optional?: boolean;
  options?: { value: string; label: string }[];
  hint?: string;
  /** Read from the linked profile when linked. */
  profile?: boolean;
  width?: number;
}

const PLAYER: FieldDef[] = [
  { path: ["player", "hotmLevel"], label: "Heart of the Mountain", kind: "int", optional: true, hint: "Unlocks forge recipes", profile: true },
  { path: ["player", "forgeSlots"], label: "Forge slots unlocked", kind: "int", optional: true,
    hint: "The profile shows only busy slots; blank = 2 or the highest busy slot" },
];

const FORGE: FieldDef[] = [
  { path: ["forge", "quickForgeReduction"], label: "Quick Forge (%)", kind: "pct", optional: true,
    hint: "As the HOTM tree shows it, up to 30%", profile: true },
  { path: ["forge", "moltenForge"], label: "Molten Forge (Cole, 25%)", kind: "select", options: [
    { value: "AUTO", label: "Auto (from the election)" },
    { value: "ON", label: "On" },
    { value: "OFF", label: "Off" },
  ] },
  { path: ["forge", "nextSessionIn"], label: "Default next session", kind: "duration", hint: "How long until you are back online, like 8h or 4h 30m" },
];

const TRADING: FieldDef[] = [
  { path: ["trading", "priceSource"], label: "Bazaar prices", kind: "select", hint: "Orders are cheaper but wait to fill", options: [
    { value: "ORDER", label: "Buy orders / sell offers" },
    { value: "INSTANT", label: "Insta-buy / insta-sell" },
  ] },
  { path: ["trading", "budget"], label: "Budget", kind: "coins", optional: true, hint: "For example 50m; blank = no limit" },
  { path: ["trading", "bazaarTaxRate"], label: "Bazaar tax (%)", kind: "pct", hint: "1.25% by default, lower with the Bazaar Flipper upgrade" },
];

const AUCTION: FieldDef[] = [
  { path: ["trading", "auctionFees", "smallRate"], label: "Listing fee, small (%)", kind: "pct" },
  { path: ["trading", "auctionFees", "mediumFrom"], label: "Medium fee from", kind: "coins" },
  { path: ["trading", "auctionFees", "mediumRate"], label: "Listing fee, medium (%)", kind: "pct" },
  { path: ["trading", "auctionFees", "largeFrom"], label: "Large fee from", kind: "coins" },
  { path: ["trading", "auctionFees", "largeRate"], label: "Listing fee, large (%)", kind: "pct" },
  { path: ["trading", "auctionFees", "claimTaxFrom"], label: "Claim tax above", kind: "coins" },
  { path: ["trading", "auctionFees", "claimTaxRate"], label: "Claim tax (%)", kind: "pct" },
];

const FLIPPERS: FieldDef[] = [
  { path: ["npc", "dailyLimit"], label: "NPC daily limit", kind: "int", hint: "Items of one kind per day" },
  { path: ["npc", "marketShare"], label: "NPC market share (%)", kind: "pct", hint: "Share of an item's daily volume you can sell" },
  { path: ["bazaar", "marketShare"], label: "Bazaar market share (%)", kind: "pct", hint: "Share of the slower side's volume; Flip Calc uses it too" },
  { path: ["bazaar", "thinBook"], label: "“Few orders” below", kind: "int", hint: "Orders on the book" },
];

const ALERTS: FieldDef[] = [
  { path: ["alerts", "minAuctionSales"], label: "“Few sales” below", kind: "int", hint: "Clean Auction House sales in 7 days" },
  { path: ["alerts", "wideSpread"], label: "“Wide spread” above (%)", kind: "pct", hint: "Instant price this much worse than the order" },
  { path: ["alerts", "manipulationRatio"], label: "“Manipulated?” above (× average)", kind: "number", hint: "Bazaar price over its 7-day average" },
];

const DISPLAY: FieldDef[] = [
  { path: ["display", "timeZone"], label: "Time zone", kind: "text",
    hint: `For forge end times, like Europe/Istanbul; blank = this computer's (${Intl.DateTimeFormat().resolvedOptions().timeZone})` },
];

const ALL_FIELDS = [...PLAYER, ...FORGE, ...TRADING, ...AUCTION, ...FLIPPERS, ...ALERTS, ...DISPLAY];

const KEY_LABEL: Record<ApiKeyState, string> = {
  MISSING: "Not set",
  UNKNOWN_AGE: "Set, issue date unknown",
  VALID: "Valid",
  EXPIRING_SOON: "Expires soon",
  EXPIRED: "Expired",
};

const fid = (f: FieldDef) => f.path.join(".");

function get(settings: Settings, path: string[]): unknown {
  return path.reduce<unknown>((o, k) => (o as Record<string, unknown> | null)?.[k], settings);
}

function toText(value: unknown, kind: Kind): string {
  if (value === null || value === undefined) return "";
  switch (kind) {
    case "pct":
      return String(Number(((value as number) * 100).toFixed(4)));
    case "duration": {
      const s = parseIsoDuration(String(value));
      return s === null ? String(value) : duration(s);
    }
    default:
      return String(value);
  }
}

/** The value for the patch, or an error message. */
export function fromText(text: string, f: Pick<FieldDef, "kind" | "optional">): { value: unknown } | { error: string } {
  const t = text.trim();
  if (t === "") return f.optional || f.kind === "text" ? { value: f.kind === "text" ? "" : null } : { error: "required" };
  switch (f.kind) {
    case "text":
    case "select":
      return { value: t };
    case "int":
      return /^\d+$/.test(t) ? { value: Number(t) } : { error: "a whole number" };
    case "number":
    case "pct": {
      const n = Number(t.replace(",", ".").replace(/[%×x]$/, ""));
      if (!Number.isFinite(n)) return { error: "a number" };
      return { value: f.kind === "pct" ? n / 100 : n };
    }
    case "coins": {
      const n = parseCoins(t);
      return n === null ? { error: "coins, for example 10m" } : { value: n };
    }
    case "duration": {
      const s = parseDuration(t);
      return s === null || s <= 0 ? { error: "a duration, for example 8h" } : { value: `PT${s}S` };
    }
  }
}

function setPath(patch: Record<string, unknown>, path: string[], value: unknown) {
  let o = patch;
  path.slice(0, -1).forEach((k) => {
    o[k] = (o[k] as Record<string, unknown> | undefined) ?? {};
    o = o[k] as Record<string, unknown>;
  });
  o[path[path.length - 1]!] = value;
}

function collectionsText(c: Record<string, number>): string {
  return Object.entries(c).map(([k, v]) => `${k}: ${v}`).join("\n");
}

export function parseCollections(text: string): Record<string, number> | string {
  const out: Record<string, number> = {};
  for (const line of text.split("\n")) {
    if (line.trim() === "") continue;
    const m = /^\s*([^:=]+?)\s*[:=]\s*(\d+)\s*$/.exec(line);
    if (!m) return `Cannot read “${line.trim()}”; write one collection per line, like MITHRIL_ORE: 9`;
    out[m[1]!] = Number(m[2]);
  }
  return out;
}

function Row({ label, hint, children }: { label: ReactNode; hint?: string; children: ReactNode }) {
  return (
    <div className="setting-row">
      <div>
        <div className="setting-label">{label}</div>
        {hint && <div className="muted small">{hint}</div>}
      </div>
      <div className="setting-control">{children}</div>
    </div>
  );
}

interface Draft {
  fields: Record<string, string>;
  username: string;
  profile: string;
  coopMember: string;
  collections: string;
}

function draftOf(v: SettingsView): Draft {
  const fields: Record<string, string> = {};
  for (const f of ALL_FIELDS) fields[fid(f)] = toText(get(v.settings, f.path), f.kind);
  return {
    fields,
    username: v.settings.player.username,
    profile: v.settings.player.profile,
    coopMember: v.settings.player.coopMember,
    collections: collectionsText(v.settings.player.collections),
  };
}

export function SettingsScreen({ theme, onTheme, onChanged }: { theme: ThemeChoice; onTheme: (t: ThemeChoice) => void; onChanged: () => void }) {
  const api = useApi();
  const toast = useToast();
  const { profile, reload: reloadProfile } = useContext(ProfileContext);
  const [section, setSection] = useStored<Section>("settings.section", "Profile");
  const view = usePolling(() => api.settings(), 0, [api]);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [saving, setSaving] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);
  useEffect(() => {
    if (view.data) setDraft(draftOf(view.data));
  }, [view.data]);

  const changed = () => {
    view.reload();
    reloadProfile();
    onChanged();
  };
  const dirty = !!draft && !!view.data && JSON.stringify(draft) !== JSON.stringify(draftOf(view.data));

  const save = async () => {
    if (!draft) return;
    const patch: Record<string, unknown> = {};
    const errs: string[] = [];
    for (const f of ALL_FIELDS) {
      const r = fromText(draft.fields[fid(f)] ?? "", f);
      if ("error" in r) errs.push(`${f.label}: ${r.error}`);
      else setPath(patch, f.path, r.value);
    }
    setPath(patch, ["player", "username"], draft.username.trim());
    setPath(patch, ["player", "profile"], draft.profile.trim());
    setPath(patch, ["player", "coopMember"], draft.coopMember.trim());
    const c = parseCollections(draft.collections);
    if (typeof c === "string") errs.push(c);
    // The server replaces the collections map as a whole, so removed lines go away.
    else setPath(patch, ["player", "collections"], c);
    setErrors(errs);
    if (errs.length > 0) return;
    setSaving(true);
    try {
      await api.updateSettings(patch as SettingsPatch);
      toast("Saved. The flippers use the new settings now.");
      changed();
    } catch (e) {
      setErrors([e instanceof Error ? e.message : String(e)]);
    } finally {
      setSaving(false);
    }
  };
  const reset = async () => {
    if (!window.confirm("Drop the settings saved in the app and go back to config.yaml?")) return;
    try {
      await api.resetSettings();
      toast("Back to config.yaml");
      changed();
    } catch (e) {
      toast(String(e), "error");
    }
  };

  if (view.error) return <div className="page"><ErrorLine text={`Settings unavailable: ${view.error}`} onRetry={view.reload} /></div>;
  if (!view.data || !draft) return <div className="page muted">Loading settings…</div>;
  const linked = profile?.link.linked ?? null;

  const field = (f: FieldDef) => {
    const value = draft.fields[fid(f)] ?? "";
    const set = (v: string) => setDraft({ ...draft, fields: { ...draft.fields, [fid(f)]: v } });
    const bad = "error" in fromText(value, f);
    return (
      <Row key={fid(f)} label={f.label} hint={f.hint}>
        {f.profile && <FromProfile on={!!linked} />}
        {f.kind === "select" ? (
          <select value={value} onChange={(e) => set(e.target.value)} aria-label={f.label}>
            {f.options!.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        ) : (
          <input value={value} placeholder={f.optional ? "not set" : undefined} onChange={(e) => set(e.target.value)}
            aria-label={f.label} className={bad ? "bad" : ""} style={{ width: f.kind === "text" ? 200 : 110 }} />
        )}
      </Row>
    );
  };

  return (
    <div className="settings-layout">
      <nav className="settings-nav">
        {SECTIONS.map((s) => (
          <button key={s} className={section === s ? "on" : ""} onClick={() => setSection(s)}>{s}</button>
        ))}
        <div className="muted small settings-source">
          {view.data.source === "APP" ? "Saved in the app." : "From config.yaml until you save here."}
        </div>
      </nav>
      <div className="page settings-page">
        {section === "Profile" && (
          <>
            <div className="card">
              <h3>Minecraft account</h3>
              <Link onChanged={changed} />
              <Row label="Minecraft name" hint="Only the name is needed; nothing is ever sent to the game">
                <input value={draft.username} onChange={(e) => setDraft({ ...draft, username: e.target.value })} aria-label="Minecraft name" />
              </Row>
              <Row label="SkyBlock profile" hint="Blank = the active one">
                <input list="profile-names" value={draft.profile} placeholder="active profile" aria-label="SkyBlock profile"
                  onChange={(e) => setDraft({ ...draft, profile: e.target.value })} />
                <datalist id="profile-names">{linked?.profileNames.map((n) => <option key={n} value={n} />)}</datalist>
              </Row>
              <Row label="Co-op member" hint="Whose HOTM and forge are read; blank = you">
                <input value={draft.coopMember} placeholder="you" aria-label="Co-op member"
                  onChange={(e) => setDraft({ ...draft, coopMember: e.target.value })} />
              </Row>
              <ApiKey state={view.data.apiKey.state} source={view.data.apiKey.source} expiresAt={view.data.apiKey.expiresAt} onChanged={changed} />
            </div>
            <div className="card">
              <h3>Player</h3>
              {PLAYER.map(field)}
              <Row label={<>Collection tiers <FromProfile on={!!linked} /></>}
                hint={`One per line, by ID or name. Tiers from the linked profile take precedence${linked ? ` (${Object.keys(linked.collections).length} read)` : ""}. Hypixel does not share Lotus, Chili Pepper, Helix Log, Honeycomb, Moonflower, Wild Rose, Sunflower or Ruby Veilshroom: type those here, like Lotus: 10.`}>
                <textarea value={draft.collections} placeholder={"MITHRIL_ORE: 9\nGemstone: 7"} rows={4} aria-label="Collection tiers"
                  onChange={(e) => setDraft({ ...draft, collections: e.target.value })} />
              </Row>
            </div>
            <div className="card">
              <h3>Appearance</h3>
              <Row label="Theme" hint="Remembered in this window">
                <div className="seg">
                  {(["system", "dark", "light"] as ThemeChoice[]).map((t) => (
                    <button key={t} className={theme === t ? "on" : ""} onClick={() => onTheme(t)}>
                      {t === "system" ? "Like the system" : t === "dark" ? "Dark" : "Light"}
                    </button>
                  ))}
                </div>
              </Row>
              <CompactRows />
            </div>
            <div className="card">
              <h3>Time</h3>
              {DISPLAY.map(field)}
            </div>
          </>
        )}
        {section === "Forge" && (
          <div className="card">
            <h3>Forge</h3>
            {FORGE.map(field)}
            <Row label="Speed calibration" hint="Fitted from your own timings">
              <span className="muted">
                {profile ? (profile.forgeSpeed.calibration === "NONE" ? "none" : profile.forgeSpeed.calibration === "PERCENTAGE"
                  ? `× ${profile.forgeSpeed.calibrationFactor.toFixed(3)}` : profile.forgeSpeed.calibration === "FIXED"
                  ? `− ${duration(profile.forgeSpeed.calibrationFixedSeconds)}` : "inconsistent") : "—"}
                {" "}· Forge Manager › Calibration
              </span>
            </Row>
          </div>
        )}
        {section === "Trading & fees" && (
          <>
            <div className="card">
              <h3>Trading & fees</h3>
              {TRADING.map(field)}
            </div>
            <div className="card">
              <h3>Auction House fees</h3>
              {AUCTION.map(field)}
            </div>
          </>
        )}
        {section === "Flippers" && (
          <div className="card">
            <h3>Flippers</h3>
            {FLIPPERS.map(field)}
          </div>
        )}
        {section === "Alerts" && (
          <div className="card">
            <h3>Alerts</h3>
            <p className="muted small">When a flip gets a warning tag. Hide tagged rows with “Hide alerts” in each list.</p>
            {ALERTS.map(field)}
          </div>
        )}
        {section === "Item icons" && <IconsSection />}
        {section === "Market history" && <DataSection onChanged={changed} />}

        {(dirty || errors.length > 0) && (
          <div className="save-bar" role="region" aria-label="Unsaved changes">
            <span className="muted">{errors.length > 0 ? "" : "Unsaved changes"}</span>
            {errors.length > 0 && <span className="loss small">{errors.join("; ")}</span>}
            <span className="row-gap">
              <button onClick={() => { setDraft(draftOf(view.data!)); setErrors([]); }} disabled={saving}>Discard</button>
              <button className="primary" onClick={() => void save()} disabled={saving}>{saving ? "Saving…" : "Save"}</button>
            </span>
          </div>
        )}
        {view.data.source === "APP" && !dirty && (
          <p className="muted small">
            <button className="ghost small" onClick={() => void reset()}>Back to config.yaml</button>
          </p>
        )}
      </div>
    </div>
  );
}

function CompactRows() {
  const [compact, setCompact] = useStored("compact", false);
  useEffect(() => {
    document.documentElement.classList.toggle("compact", compact);
  }, [compact]);
  return (
    <Row label="Compact rows" hint="More rows on screen in the lists">
      <input type="checkbox" checked={compact} onChange={(e) => setCompact(e.target.checked)} aria-label="Compact rows" />
    </Row>
  );
}

function ApiKey({ state, source, expiresAt, onChanged }: { state: ApiKeyState; source?: string; expiresAt: string | null; onChanged: () => void }) {
  const api = useApi();
  const toast = useToast();
  const [key, setKey] = useState("");
  const run = async (action: () => Promise<unknown>, done: string) => {
    try {
      await action();
      setKey("");
      toast(done);
      onChanged();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error");
    }
  };
  const tone = state === "VALID" ? "gain" : state === "MISSING" || state === "EXPIRED" ? "loss" : "warn-text";
  return (
    <Row label={<>Hypixel API key · <span className={tone}>{KEY_LABEL[state]}</span>{source === "CONFIG" ? <span className="muted small"> (config.yaml)</span> : null}</>}
      hint={`From developer.hypixel.net; personal keys expire after 3 days${expiresAt ? ` (this one ${new Date(expiresAt).toLocaleString()})` : ""}. Stored on this computer only, never shown again.`}>
      <input type="password" autoComplete="off" value={key} placeholder="paste a new key" aria-label="Hypixel API key" onChange={(e) => setKey(e.target.value)} />
      <button disabled={key.trim() === ""} onClick={() => void run(() => api.setApiKey(key.trim()), "API key saved")}>Save key</button>
      {source === "APP" && <button className="ghost small" onClick={() => void run(() => api.clearApiKey(), "API key removed")}>Remove</button>}
    </Row>
  );
}

function Link({ onChanged }: { onChanged: () => void }) {
  const api = useApi();
  const toast = useToast();
  const { profile } = useContext(ProfileContext);
  const [busy, setBusy] = useState(false);
  if (!profile) return null;
  const { link } = profile;
  const l = link.linked;
  const sync = async () => {
    setBusy(true);
    try {
      const r = await api.linkProfile();
      const f = r.forge;
      toast(`Read ${r.profile.cuteName}.` +
        (f.added + f.collected > 0 ? ` Forge: ${f.added} added, ${f.collected} collected.` : "") +
        (f.unknownRecipes.length > 0 ? ` Unknown forge items: ${f.unknownRecipes.join(", ")}.` : ""));
      onChanged();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      {l ? (
        <div className="profile-card">
          <PixelIcon name="player" size={40} />
          <div>
            <div className="strong">{l.username}</div>
            <div className="muted small">
              SkyBlock profile {l.cuteName} · HOTM {l.hotmLevel ?? "?"} · Quick Forge{" "}
              {l.quickForgeReduction === null ? "not unlocked" : `−${pct(l.quickForgeReduction)}`} · read {ago(l.fetchedAt)}, again every 10 min
            </div>
          </div>
          <button disabled={busy} onClick={() => void sync()}>{busy ? "Reading…" : "Sync now"}</button>
          <button className="ghost" onClick={() => void api.unlinkProfile().then(() => { toast("Unlinked"); onChanged(); })}>Unlink</button>
        </div>
      ) : (
        <div className="profile-card">
          <PixelIcon name="player" size={40} />
          <div className="muted small">
            Not linked. With a name and an API key, the app reads HOTM, Quick Forge, collections, purse and your forge slots
            from your SkyBlock profile.
          </div>
          <button className="primary" disabled={busy || !link.configured} onClick={() => void sync()}
            data-tip={link.configured ? "Read the profile now" : "Save a Minecraft name and an API key first."}>
            {busy ? "Reading…" : "Link profile"}
          </button>
        </div>
      )}
      {link.lastError && <ErrorLine text={`Last sync failed: ${link.lastError}`} />}
    </>
  );
}

const RANGES = [
  { value: "all", label: "All history", days: null },
  { value: "7", label: "Last 7 days", days: 7 },
  { value: "30", label: "Last 30 days", days: 30 },
];

const PREVIEW = ["REFINED_MITHRIL", "MITHRIL_PLATE", "ENCHANTED_LAVA_BUCKET", "HOT_POTATO_BOOK", "BOOSTER_COOKIE",
  "RECOMBOBULATOR_3000", "DIVAN_DRILL", "ASPECT_OF_THE_END"];

function IconsSection() {
  const api = useApi();
  const toast = useToast();
  const { atlas, reload } = useContext(IconContext);
  const index = usePolling(() => api.icons(), 0, [api]);
  const [found, setFound] = useState<PackCandidate[] | null>(null);
  const [path, setPath] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const loaded = index.data ?? null;

  const run = async (label: string, what: () => Promise<string>) => {
    setBusy(label);
    try {
      toast(await what());
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error");
    } finally {
      setBusy(null);
      index.reload();
      reload();
    }
  };
  const done = (r: IconIndex) => `Loaded ${r.items ? Object.keys(r.items).length.toLocaleString("en-US") : 0} item icons`;
  const find = () => run("Searching…", async () => {
    const list = await api.iconCandidates();
    setFound(list);
    return list.length === 0 ? "No SkyBlock pack found; join Hypixel once with resource packs on, or choose the file"
      : `Found ${list.length} SkyBlock pack${list.length === 1 ? "" : "s"}`;
  });
  const load = (p: string) => run("Reading the pack…", async () => done(await api.importPack(p)));
  const upload = (f: File) => run("Reading the pack…", async () => done(await api.uploadPack(f)));
  const remove = () => run("Removing…", async () => {
    await api.clearIcons();
    return "Icons removed; items show their letter again";
  });

  return (
    <div className="card">
      <h3>Item icons</h3>
      <p className="muted small">
        Icons come from the official Hypixel SkyBlock resource pack on this computer. Items Hypixel draws as plain
        Minecraft items (enchanted bone, booster cookie, enchanted books, blocks) use the textures of your own
        Minecraft 1.21.4+ installation. HyFlip only reads both here and never shares them; the art belongs to
        Hypixel and Mojang. Player heads (talismans, essences, shards) keep their letter in their rarity colour.
      </p>
      <div className="icons-preview">
        {PREVIEW.map((i) => <Slot key={i} item={i} size={40} />)}
      </div>
      {loaded ? (
        <Row label="Loaded" hint={`Built ${ago(loaded.builtAt)}`}>
          <span className="small">
            {Object.keys(loaded.items).length.toLocaleString("en-US")} items: {loaded.fromPack.toLocaleString("en-US")} from{" "}
            <code className="path">{loaded.source}</code>
            {loaded.client
              ? <>, the rest from <code className="path">{loaded.client}</code></>
              : <>; no Minecraft 1.21.4+ found for vanilla items</>}
          </span>
          <button className="ghost small" disabled={busy !== null} onClick={() => void remove()}>Remove</button>
        </Row>
      ) : (
        <p className="small">No pack loaded{atlas ? "" : "; items show their letter"}.</p>
      )}
      <Row label="Find it" hint="The game keeps the pack Hypixel sends in its downloads folder">
        <button disabled={busy !== null} onClick={() => void find()}>Search this computer</button>
      </Row>
      {found && found.length > 0 && (
        <table className="flips compact">
          <thead><tr><th>Pack</th><th className="num">Size</th><th>Downloaded</th><th /></tr></thead>
          <tbody>
            {found.map((c, i) => (
              <tr key={c.path}>
                <td><code className="path" title={c.path}>{c.path}</code>{i === 0 && <span className="chip from-profile newest-chip">Newest</span>}</td>
                <td className="num">{bytes(c.size)}</td>
                <td className="muted">{ago(c.modifiedAt)}</td>
                <td>
                  <button className="ghost small nowrap" disabled={busy !== null || c.path === loaded?.source}
                    onClick={() => void load(c.path)}>{c.path === loaded?.source ? "In use" : "Use"}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Row label="From a path" hint="A resource pack .zip, or a file from .minecraft/downloads">
        <input value={path} placeholder={"C:\\Users\\you\\Downloads\\Hypixel SkyBlock.zip"} onChange={(e) => setPath(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && path.trim()) void load(path.trim()); }} />
        <button disabled={busy !== null || !path.trim()} onClick={() => void load(path.trim())}>Load</button>
      </Row>
      <Row label="Or choose" hint="Copied into HyFlip's data folder">
        <label className="file-btn">
          Choose file…
          <input type="file" disabled={busy !== null}
            onChange={(e) => {
              const f = e.target.files?.[0];
              e.target.value = "";
              if (f) void upload(f);
            }} />
        </label>
      </Row>
      {busy && <p className="muted">{busy}</p>}
      <p className="muted small">
        When Hypixel updates the pack, the game downloads a new file; HyFlip notices it and rebuilds the icons.
      </p>
    </div>
  );
}

function DataSection({ onChanged }: { onChanged: () => void }) {
  const api = useApi();
  const toast = useToast();
  const [range, setRange] = useState("all");
  const [busy, setBusy] = useState(false);
  const exports = usePolling(() => api.exports(), 0, [api]);
  const run = async (what: () => Promise<string>) => {
    setBusy(true);
    try {
      toast(await what());
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error");
    } finally {
      setBusy(false);
      exports.reload();
    }
  };
  const exportNow = () => run(async () => {
    const days = RANGES.find((r) => r.value === range)?.days ?? null;
    const r = await api.exportDatabase(days === null ? {} : { from: Date.now() - days * 86_400_000 });
    return `Exported ${bytes(r.bytes)} to ${r.path}`;
  });
  const download = (name: string) => run(async () => {
    const blob = await api.downloadExport(name);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
    return `Saving ${name}`;
  });
  const importFile = (file: File) => run(async () => {
    const r = await api.importDatabase(file);
    onChanged();
    const total = Object.entries(r.rows).filter(([t]) => t !== "item").reduce((sum, [, n]) => sum + n, 0);
    return `Imported ${total.toLocaleString("en-US")} new rows from ${file.name}`;
  });
  return (
    <div className="card">
      <h3>Market history</h3>
      <p className="muted small">
        Export a copy of the collected prices to share or keep, or import someone else's so the flippers start with
        history. Exports never contain your settings, API key, profile or forge jobs.
      </p>
      <Row label="Export" hint="Writes a file next to the database">
        <select value={range} onChange={(e) => setRange(e.target.value)} aria-label="Export range">
          {RANGES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
        </select>
        <button disabled={busy} onClick={() => void exportNow()}>Export</button>
      </Row>
      <Row label="Import" hint="Adds rows you do not have; keeps yours">
        <label className="file-btn">
          Choose file…
          <input type="file" accept=".db,.sqlite,application/octet-stream" disabled={busy}
            onChange={(e) => {
              const f = e.target.files?.[0];
              e.target.value = "";
              if (f) void importFile(f);
            }} />
        </label>
      </Row>
      {busy && <p className="muted">Working…</p>}
      {exports.data && exports.data.length > 0 && (
        <table className="flips compact">
          <thead><tr><th>Export</th><th className="num">Size</th><th>Made</th><th /></tr></thead>
          <tbody>
            {exports.data.map((f) => (
              <tr key={f.name}>
                <td>{f.name}</td>
                <td className="num">{bytes(f.bytes)}</td>
                <td className="muted">{ago(f.modifiedAt)}</td>
                <td><button className="ghost small" onClick={() => void download(f.name)}>Download</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
