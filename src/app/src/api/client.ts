import type { ServerConnection } from "./connection";

export const TOKEN_HEADER = "X-HyFlip-Token";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/** Builds an absolute URL for an API path such as `/health`. */
export function apiUrl(connection: ServerConnection, path: string): string {
  const base = connection.url.replace(/\/+$/, "");
  const suffix = path.startsWith("/") ? path : `/${path}`;
  return `${base}/api/v1${suffix}`;
}

/** Thin JSON client for the local HyFlip server. */
export function createClient(connection: ServerConnection, fetchImpl: typeof fetch = fetch) {
  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers);
    if (connection.token) headers.set(TOKEN_HEADER, connection.token);
    if (typeof init.body === "string") headers.set("Content-Type", "application/json");

    const response = await fetchImpl(apiUrl(connection, path), { ...init, headers });
    if (!response.ok) {
      throw new ApiError(response.status, await errorMessage(response, `${init.method ?? "GET"} ${path}`));
    }
    if (response.status === 204 || response.status === 202) return undefined as T;
    const text = await response.text();
    return (text === "" ? undefined : JSON.parse(text)) as T;
  }

  const post = <T>(path: string, body?: unknown) =>
    request<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) });
  const put = <T>(path: string, body: unknown) => request<T>(path, { method: "PUT", body: JSON.stringify(body) });
  const del = <T = void>(path: string) => request<T>(path, { method: "DELETE" });

  return {
    health: () => request<Health>("/health"),
    status: () => request<Status>("/status"),
    profile: () => request<Profile>("/profile"),
    flips: (type: FlipType, query: FlipQuery = {}) => request<FlipList>(`/flips/${type}${queryString(query)}`),
    catalog: () => request<Catalog>("/items/catalog"),
    collectorRuns: (hours = 24) => request<Record<string, RunPoint[]>>(`/status/runs?hours=${hours}`),
    runCollector: (name: string) => post<void>(`/status/collectors/${encodeURIComponent(name)}/run`),
    flip: (type: FlipType, id: string, query: Pick<FlipQuery, "nextSessionIn"> = {}) =>
      request<OpportunityDetail>(`/flips/${type}/${encodeURIComponent(id)}${queryString(query)}`),
    forgeSlots: () => request<ForgeSlots>("/forge/slots"),
    forgeJobs: (limit = 50) => request<ForgeJob[]>(`/forge/jobs?limit=${limit}`),
    startForgeJob: (job: StartForgeJob) => post<ForgeJob>("/forge/jobs", job),
    collectForgeJob: (id: number) => post<ForgeJob>(`/forge/jobs/${id}/collect`),
    deleteForgeJob: (id: number) => del(`/forge/jobs/${id}`),
    /** What the player actually received for the output; null clears it. */
    recordForgeSale: (id: number, value: number | null) => put<ForgeJob>(`/forge/jobs/${id}/sale`, { value }),
    calibration: () => request<CalibrationOverview>("/forge/calibration"),
    addCalibrationSample: (recipeId: string, observedSeconds: number) =>
      post<CalibrationSample>("/forge/calibration", { recipeId, observedSeconds }),
    deleteCalibrationSample: (id: number) => del(`/forge/calibration/${id}`),
    settings: () => request<SettingsView>("/settings"),
    /** JSON merge patch: only the fields that change; null clears an optional value. */
    updateSettings: (patch: SettingsPatch) =>
      request<SettingsView>("/settings", { method: "PATCH", body: JSON.stringify(patch) }),
    resetSettings: () => del<SettingsView>("/settings"),
    setApiKey: (key: string) => put<ApiKeyStatus>("/settings/api-key", { key }),
    clearApiKey: () => del<ApiKeyStatus>("/settings/api-key"),
    linkProfile: () => post<ProfileSync>("/profile/link"),
    unlinkProfile: () => del("/profile/link"),
    exportDatabase: (range: { from?: number; to?: number } = {}) => post<ExportResult>("/database/export", range),
    exports: () => request<ExportFile[]>("/database/exports"),
    /** The export file's bytes, fetched with the token so the browser can save it. */
    downloadExport: async (name: string): Promise<Blob> => {
      const headers = new Headers();
      if (connection.token) headers.set(TOKEN_HEADER, connection.token);
      const response = await fetchImpl(apiUrl(connection, `/database/exports/${encodeURIComponent(name)}`), { headers });
      if (!response.ok) throw new ApiError(response.status, await errorMessage(response, `download ${name}`));
      return response.blob();
    },
    importDatabase: (file: Blob) => {
      const form = new FormData();
      form.append("file", file);
      return request<ImportResult>("/database/import", { method: "POST", body: form });
    },
    /** Null when no resource pack is loaded. */
    icons: async (): Promise<IconIndex | null> => (await request<IconIndex | undefined>("/icons")) ?? null,
    /** The atlas image, fetched with the token; `builtAt` busts the cache after a new import. */
    iconAtlas: async (builtAt: number): Promise<Blob> => {
      const headers = new Headers();
      if (connection.token) headers.set(TOKEN_HEADER, connection.token);
      const response = await fetchImpl(apiUrl(connection, `/icons/atlas.png?v=${builtAt}`), { headers });
      if (!response.ok) throw new ApiError(response.status, await errorMessage(response, "icon atlas"));
      return response.blob();
    },
    iconCandidates: () => request<PackCandidate[]>("/icons/candidates"),
    importPack: (path: string) => post<IconIndex>("/icons/import", { path }),
    uploadPack: (file: Blob) => {
      const form = new FormData();
      form.append("file", file);
      return request<IconIndex>("/icons/import", { method: "POST", body: form });
    },
    clearIcons: () => del("/icons"),
    trades: (type?: FlipType, limit = 100) =>
      request<Trade[]>(`/trades${queryString({ type, limit })}`),
    tradeSummary: () => request<TradeSummary>("/trades/summary"),
    recordTrade: (trade: { type: FlipType; id: string; runs?: number; note?: string }) => post<Trade>("/trades", trade),
    tradeResult: (id: number, result: TradeResult) => put<Trade>(`/trades/${id}/result`, result),
    deleteTrade: (id: number) => del(`/trades/${id}`),
    backtest: (type: FlipType, query: BacktestQuery = {}) =>
      request<BacktestResult>(`/backtest/${type}${queryString(query)}`),
    forgePlan: (query: { budget?: number; nextSessionIn?: string; slots?: number } = {}) =>
      request<ForgePlan>(`/forge/plan${queryString(query)}`),
    item: (id: string) => request<ItemInfo>(`/items/${encodeURIComponent(id)}`),
    itemHistory: (id: string, query: { from?: number; to?: number; bucket?: string } = {}) =>
      request<ItemHistory>(`/items/${encodeURIComponent(id)}/history${queryString(query)}`),
  };
}

/** Uses the `detail` (or every `problems` entry) of a problem-details body when there is one. */
async function errorMessage(response: Response, what: string): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: unknown; problems?: unknown };
    if (Array.isArray(body.problems) && body.problems.length > 0) return body.problems.join("; ");
    if (typeof body.detail === "string" && body.detail !== "") return body.detail;
  } catch {
    // not JSON
  }
  return `${what} failed: ${response.status}`;
}

/** `?a=1&b=x` from the defined values, or "". */
export function queryString(query: object): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  }
  const s = params.toString();
  return s === "" ? "" : `?${s}`;
}

export type ApiClient = ReturnType<typeof createClient>;

export interface Health {
  status: "UP";
  version: string;
}

export interface CollectorRun {
  collector: string;
  startedAt: number;
  finishedAt: number | null;
  status: "RUNNING" | "OK" | "FAILED";
  rowsWritten: number | null;
  error: string | null;
}

export interface CollectorStatus {
  name: string;
  intervalSeconds: number;
  lastRun: CollectorRun | null;
  lastSuccessAt: number | null;
  consecutiveFailures: number;
}

export interface Gap {
  collector: string;
  gapStart: number;
  gapEnd: number;
  backfilled: boolean;
}

export type ApiKeyState = "MISSING" | "UNKNOWN_AGE" | "VALID" | "EXPIRING_SOON" | "EXPIRED";

export interface Status {
  collectors: CollectorStatus[];
  gaps: Gap[];
  database: { sizeBytes: number; items: number; bazaarSnapshots: number; auctionSales: number };
  neu: { loaded: boolean; commit: string | null; loadedAt: number | null; items: number; recipes: Record<string, number> };
  unmapped: { count: number; sample: { source: string; sourceId: string; lastSeen: number; seen: number }[] };
  apiKey: ApiKeyStatus;
}

export interface ApiKeyStatus {
  state: ApiKeyState;
  expiresAt: string | null;
  source?: "NONE" | "CONFIG" | "APP";
}

export interface AuctionFees {
  smallRate: number;
  mediumRate: number;
  largeRate: number;
  mediumFrom: number;
  largeFrom: number;
  claimTaxRate: number;
  claimTaxFrom: number;
}

/** Settings editable in the app; same shape as the config.yaml sections. */
export interface Settings {
  player: {
    username: string;
    profile: string;
    coopMember: string;
    hotmLevel: number | null;
    forgeSlots: number | null;
    collections: Record<string, number>;
  };
  trading: { budget: number | null; bazaarTaxRate: number; priceSource: PriceSource; auctionFees: AuctionFees };
  forge: { quickForgeReduction: number | null; moltenForge: "AUTO" | "ON" | "OFF"; nextSessionIn: string };
  npc: { dailyLimit: number; marketShare: number };
  bazaar: { marketShare: number; thinBook: number };
  alerts: { minAuctionSales: number; wideSpread: number; manipulationRatio: number };
  /** IANA zone such as "Europe/Istanbul"; empty uses the computer's zone. */
  display: { timeZone: string };
}

type DeepPartial<T> = { [K in keyof T]?: T[K] extends object | null ? DeepPartial<NonNullable<T[K]>> | null : T[K] };
export type SettingsPatch = DeepPartial<Settings>;

export interface SettingsView {
  settings: Settings;
  /** CONFIG until settings are saved in the app, then APP. */
  source: "CONFIG" | "APP";
  apiKey: ApiKeyStatus;
}

/** Every NEU item: ID → [display name, rarity]. */
export interface Catalog {
  commit: string;
  items: Record<string, [string | null, string | null]>;
}

export interface RunPoint {
  collector: string;
  startedAt: number;
  finishedAt: number | null;
  status: "RUNNING" | "OK" | "FAILED";
}

/** Display extras of one flip. */
export interface FlipExtras {
  rarity: string | null;
  /** "HOTM 2", "Diamond VIII"; empty if none. */
  requirements: string[];
  /** Output price now ÷ its 7-day level − 1; null if unknown. */
  trend: number | null;
  /** Daily price swing (standard deviation ÷ price); null if unknown. */
  volatility: number | null;
}

/** How one step is made: a crafting grid (row by row, null = empty) or a forge recipe. */
export interface RecipeView {
  type: "CRAFTING" | "FORGE" | "NPC_SHOP";
  grid: ({ item: string; count: number } | null)[] | null;
  outputCount: number;
  durationSeconds: number | null;
  coins: number;
}

export interface LinkedProfile {
  username: string;
  uuid: string;
  profileId: string;
  cuteName: string;
  profileNames: string[];
  hotmLevel: number | null;
  hotmExperience: number;
  quickForgeLevel: number | null;
  quickForgeReduction: number | null;
  collections: Record<string, number>;
  forge: { slot: number; itemId: string; startedAt: number }[];
  forgeKnown: boolean;
  fetchedAt: number;
  /** Coins in the purse, null if unknown. */
  purse: number | null;
  bank: number | null;
}

export interface ProfileSync {
  profile: LinkedProfile;
  forge: { added: number; collected: number; unknownRecipes: string[] };
}

export interface ExportResult {
  name: string;
  path: string;
  bytes: number;
  rows: Record<string, number>;
}

export interface ExportFile {
  name: string;
  bytes: number;
  modifiedAt: number;
}

/** Item icons built from the Hypixel SkyBlock resource pack. */
export interface IconIndex {
  version: number;
  /** Atlas cell size in px. */
  cell: number;
  columns: number;
  /** Item ID → cell index. */
  items: Record<string, number>;
  source: string;
  sourceSize: number;
  sourceModified: number;
  /** The pack's own description. */
  pack: string;
  /** Item definitions in the pack. */
  models: number;
  builtAt: number;
  /** Items drawn with the enchantment glint. */
  glint: string[];
  /** The Minecraft client jar vanilla textures came from; null if none was found. */
  client: string | null;
  /** Items whose icon comes from the pack; the rest use vanilla textures. */
  fromPack: number;
}

/** A SkyBlock resource pack found on this computer. */
export interface PackCandidate {
  path: string;
  size: number;
  modifiedAt: number;
}

export interface ImportResult {
  path: string;
  schemaVersion: number;
  rows: Record<string, number>;
}

export interface Profile {
  context: {
    hotmLevel: number | null;
    forgeSlots: number;
    forgeSlotsKnown: boolean;
    collections: Record<string, number>;
    quickForgeReduction: number | null;
    moltenForge: boolean;
    moltenForgeSource: "ELECTION" | "CONFIG" | "UNKNOWN";
    budget: number | null;
    nextSessionIn: string;
    priceSource: PriceSource;
    /** PROFILE (linked profile), APP (saved in the app) or CONFIG (config.yaml). */
    source: string;
  };
  forgeSpeed: ForgeSpeedInfo;
  collectionsKnown: boolean;
  link: { linked: LinkedProfile | null; configured: boolean; lastError: string | null; lastAttemptAt: number | null };
}

export type FlipType = "forge" | "craft" | "npc" | "bazaar";
export type FlipSort = "profitPerSession" | "profitPerHour" | "profitPerDay" | "netProfit" | "roi";
export type PriceSource = "ORDER" | "INSTANT";
export type Venue = "BAZAAR" | "AUCTION" | "NPC";
export type UnlockState = "MET" | "LOCKED" | "UNKNOWN";
export type LiquidityLevel = "HIGH" | "MEDIUM" | "LOW" | "UNKNOWN";
export type AlertType =
  | "MISSING_PRICE"
  | "ESTIMATED_PRICE"
  | "STALE_PRICE"
  | "FEW_SALES"
  | "WIDE_SPREAD"
  | "MANIPULATION"
  | "RECENT_CHANGE"
  | "THIN_BOOK"
  | "LOW_VOLUME"
  | "UNLOCK_UNKNOWN"
  | "FORGE_FINISHED";

export interface FlipQuery {
  budget?: number;
  /** ISO-8601 (`PT8H`) or config style (`8h`). */
  nextSessionIn?: string;
  sort?: FlipSort;
  minVolume?: number;
  includeLocked?: boolean;
  /** Also return opportunities with a missing price. */
  includeIncomplete?: boolean;
  limit?: number;
  offset?: number;
  /** Comma-separated alert types; rows carrying any of them are hidden. */
  hideAlerts?: string;
  /** Only names containing this text. */
  search?: string;
  /** Check HOTM requirements against this level instead of the profile. */
  hotm?: number;
}

export interface Alert {
  type: AlertType;
  message: string;
  itemId: string | null;
}

export interface ForgeSpeedInfo {
  knownReduction: number;
  quickForgeReduction: number | null;
  moltenForge: boolean;
  calibration: "NONE" | "PERCENTAGE" | "FIXED" | "INCONSISTENT";
  calibrationFactor: number;
  calibrationFixedSeconds: number;
  provisional: boolean;
}

export interface Opportunity {
  id: string;
  type: string;
  name: string;
  outputId: string;
  outputCount: number;
  cost: number;
  value: number;
  fees: number;
  netProfit: number;
  roi: number | null;
  baseDurationSeconds: number;
  durationSeconds: number;
  /** Null for instant flips (crafting). */
  profitPerHour: number | null;
  sessions: number;
  profitPerSession: number;
  sellVenue: Venue | null;
  /** Where the inputs are bought, without repeats. */
  buyVenues: Venue[];
  liquidity: { level: LiquidityLevel; dailyVolume: number | null };
  unlock: UnlockState;
  complete: boolean;
  alerts: Alert[];
  /** NPC Flipper: realistic runs a day (daily shop limit, market share); null elsewhere. */
  runsPerDay: number | null;
  profitPerDay: number | null;
  /** Orders mode: about how long one run's orders take to fill at current volume; null if nothing waits. */
  fillSeconds: number | null;
}

export interface FlipList {
  total: number;
  generatedAt: number;
  settings: {
    priceSource: PriceSource;
    nextSessionInSeconds: number;
    budget: number | null;
    sort: FlipSort;
    forgeSpeed: ForgeSpeedInfo | null;
  };
  items: Opportunity[];
  /** By opportunity ID. */
  extras: Record<string, FlipExtras>;
}

export interface Quote {
  itemId: string;
  quantity: number;
  venue: Venue;
  unitPrice: number;
  gross: number;
  fees: number;
  priceAt: number | null;
  estimated: boolean;
}

/** How an ingredient is obtained: bought, crafted from cheaper parts, or no known way. */
export interface Plan {
  itemId: string;
  quantity: number;
  method: "BUY" | "CRAFT" | "MISSING";
  cost: number;
  /** BUY: the purchase; CRAFT: what buying would cost instead. */
  quote: Quote | null;
  crafts: number;
  coins: number;
  inputs: Plan[];
}

export interface OpportunityDetail {
  summary: Opportunity;
  sheet: {
    inputs: { itemId: string; quantity: number; quote: Quote | null; plan: Plan }[];
    coins: number;
    cost: number;
    output: Quote | null;
    value: number;
    fees: number;
    netProfit: number;
    missingPrices: string[];
  };
  unlock: {
    state: UnlockState;
    checks: { requirement: string; required: number; player: number | null; collectionId: string | null; state: UnlockState }[];
  };
  guide: { steps: string[]; logic: string };
  names: Record<string, string>;
  /** By output ID: the flip's own recipe and those of crafted ingredients. */
  recipes: Record<string, RecipeView>;
  rarities: Record<string, string>;
  extras: FlipExtras;
}

export interface ForgeJob {
  id: number;
  slot: number;
  recipeId: string;
  name: string;
  startedAt: number;
  expectedEndAt: number;
  remainingSeconds: number;
  finished: boolean;
  baseSeconds: number | null;
  expectedCost: number | null;
  expectedValue: number | null;
  expectedProfit: number | null;
  realisedValue: number | null;
  realisedAt: number | null;
  valueChange: number | null;
  collectedAt: number | null;
  source: "MANUAL" | "PROFILE_API";
  /** Coins the player entered as received for the output. */
  soldValue: number | null;
  soldProfit: number | null;
}

export interface ForgeSlots {
  slotCount: number;
  slotCountKnown: boolean;
  now: number;
  slots: { slot: number; job: ForgeJob | null }[];
  unassigned: ForgeJob[];
  alerts: Alert[];
  /** IANA zone for clock times (settings, else the server computer's). */
  timeZone: string;
}

export interface StartForgeJob {
  slot: number;
  recipeId: string;
  startedAt?: number;
  remainingSeconds?: number;
}

export interface CalibrationSample {
  id: number;
  recipeId: string;
  name: string;
  baseSeconds: number;
  knownReduction: number;
  calculatedSeconds: number;
  observedSeconds: number;
  ratio: number;
  recordedAt: number;
}

export interface CalibrationOverview {
  speed: ForgeSpeedInfo;
  fit: {
    kind: ForgeSpeedInfo["calibration"];
    factor: number;
    fixedSeconds: number;
    samples: number;
    provisional: boolean;
  };
  samples: CalibrationSample[];
  suggestions: { recipeId: string; name: string; baseSeconds: number; calculatedSeconds: number }[];
}

export type TradeType = "FORGE" | "CRAFT" | "NPC" | "BAZAAR";

export interface Trade {
  /** Negative for Forge Tracker jobs counted in the summary. */
  id: number;
  type: TradeType;
  opportunityId: string;
  name: string;
  runs: number;
  startedAt: number;
  expectedCost: number;
  expectedValue: number;
  expectedProfit: number;
  alerts: AlertType[] | null;
  actualCost: number | null;
  actualRevenue: number | null;
  actualProfit: number | null;
  difference: number | null;
  finishedAt: number | null;
  note: string | null;
}

export interface TradeResult {
  actualCost?: number | null;
  actualRevenue?: number | null;
  finished?: boolean;
  note?: string | null;
}

export interface TradeStats {
  trades: number;
  withResult: number;
  expectedProfit: number;
  actualProfit: number;
  accuracy: number | null;
  profitable: number | null;
}

export interface TradeSummary {
  all: TradeStats;
  byType: Partial<Record<TradeType, TradeStats>>;
  byAlert: Partial<Record<AlertType, TradeStats>>;
  noAlerts: TradeStats;
}

export interface BacktestQuery {
  from?: number;
  to?: number;
  step?: string;
  horizon?: string;
  top?: number;
  hideAlerts?: AlertType[];
}

export interface BacktestStats {
  flips: number;
  predicted: number;
  realised: number;
  capture: number | null;
  profitable: number | null;
  rankCorrelation: number | null;
}

export interface BacktestResult {
  type: TradeType;
  from: number;
  to: number;
  stepSeconds: number;
  horizonSeconds: number;
  top: number;
  moments: number;
  skipped: number;
  overall: BacktestStats;
  byAlert: Partial<Record<AlertType, BacktestStats>>;
  noAlerts: BacktestStats;
  shortfall: { id: string; name: string; flips: number; meanPredicted: number; meanRealised: number }[];
  items: {
    at: number;
    rank: number;
    id: string;
    name: string;
    cost: number;
    predictedProfit: number;
    realisedProfit: number | null;
    soldAt: number;
    alerts: AlertType[];
  }[];
}

export interface ForgePlan {
  slots: number[];
  budget: number | null;
  nextSessionInSeconds: number;
  picks: { slot: number; opportunity: Opportunity }[];
  totalCost: number;
  totalProfitPerSession: number;
  limitedBy: "BUDGET" | "SLOTS" | "NO_CANDIDATES";
  candidates: number;
}

export interface ItemInfo {
  id: string;
  name: string;
  bazaar: {
    buyPrice: number;
    sellPrice: number;
    buyMovingWeek: number;
    sellMovingWeek: number;
    takenAt: number;
  } | null;
  auction: { lowestBin: number | null; medianSale: number | null; salesPerDay: number; sales: number } | null;
  npcSellPrice: number | null;
  average7d: { buyPrice: number; sellPrice: number; days: number } | null;
}

export interface ItemHistory {
  id: string;
  name: string;
  from: number;
  to: number;
  bucketSeconds: number;
  bazaar: { t: number; buyPrice: number; sellPrice: number }[];
  sales: { t: number; median: number; sales: number }[];
  lowestBin: { t: number; price: number }[];
}
