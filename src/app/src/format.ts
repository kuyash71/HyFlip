/** Formats a byte count as B, KB, MB or GB. */
export function formatBytes(bytes: number): string {
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

/** "12 s ago", "5 min ago", "3 h ago", "2 d ago" relative to `now`. */
export function formatAgo(epochMs: number | null, now: number = Date.now()): string {
  if (epochMs === null) return "never";
  const seconds = Math.max(0, Math.round((now - epochMs) / 1000));
  if (seconds < 60) return `${seconds} s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours} h ago`;
  return `${Math.round(hours / 24)} d ago`;
}

/** Interval in seconds as "60 s", "30 min", "24 h". */
export function formatInterval(seconds: number): string {
  if (seconds < 120) return `${seconds} s`;
  if (seconds < 7200) return `${Math.round(seconds / 60)} min`;
  return `${Math.round(seconds / 3600)} h`;
}

/** Coins in short form: 950, 12.3k, 4.56M, 1.2B. */
export function formatCoins(coins: number): string {
  const abs = Math.abs(coins);
  const sign = coins < 0 ? "-" : "";
  const short = (v: number) => {
    const digits = v >= 100 ? 0 : v >= 10 ? 1 : 2;
    return String(Number(v.toFixed(digits)));
  };
  if (abs >= 1e9) return `${sign}${short(abs / 1e9)}B`;
  if (abs >= 1e6) return `${sign}${short(abs / 1e6)}M`;
  if (abs >= 1e3) return `${sign}${short(abs / 1e3)}k`;
  return `${sign}${Math.round(abs)}`;
}

/** Seconds as "45 s", "30 min", "4 h 30 min", "3 d 2 h". */
export function formatDuration(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)} s`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const restMinutes = minutes % 60;
  if (hours < 48) return restMinutes === 0 ? `${hours} h` : `${hours} h ${restMinutes} min`;
  const days = Math.floor(hours / 24);
  const restHours = hours % 24;
  return restHours === 0 ? `${days} d` : `${days} d ${restHours} h`;
}

/** Countdown "1:02:03" (or "3d 1:02:03"). */
export function formatCountdown(seconds: number): string {
  const s = Math.max(0, Math.ceil(seconds));
  const days = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const clock = `${h}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
  return days > 0 ? `${days}d ${clock}` : clock;
}

/** Fraction as a percentage: 0.199 → "19.9%". */
export function formatPercent(fraction: number | null): string {
  if (fraction === null || !Number.isFinite(fraction)) return "—";
  return `${(fraction * 100).toFixed(Math.abs(fraction) >= 1 ? 0 : 1)}%`;
}

/**
 * Parses what a player types for a duration: "4h 30m", "1d2h", "45m", "90s", "1:30:00" or a plain
 * number of minutes. Returns seconds, or null if it cannot be read.
 */
export function parseDuration(input: string): number | null {
  const text = input.trim().toLowerCase();
  if (text === "") return null;
  if (/^\d+(\.\d+)?$/.test(text)) return Math.round(Number(text) * 60);
  const clock = /^(\d+):([0-5]?\d)(?::([0-5]?\d))?$/.exec(text);
  if (clock) {
    const [, a, b, c] = clock;
    return c === undefined ? Number(a) * 3600 + Number(b) * 60 : Number(a) * 3600 + Number(b) * 60 + Number(c);
  }
  const units: Record<string, number> = { d: 86400, h: 3600, m: 60, min: 60, s: 1 };
  const parts = text.replace(/\s+/g, "").match(/\d+(\.\d+)?(d|h|min|m|s)/g);
  if (!parts || parts.join("") !== text.replace(/\s+/g, "")) return null;
  let total = 0;
  for (const part of parts) {
    const [, value, unit] = /^(\d+(?:\.\d+)?)(d|h|min|m|s)$/.exec(part)!;
    total += Number(value) * units[unit!]!;
  }
  return Math.round(total);
}

/** "50m", "1.5b", "200k", "1000000" → coins; "" → null. */
export function parseCoins(input: string): number | null {
  // Thousands separators are allowed: "1,200,000" and "1 200 000" read like "1.2m".
  const m = /^(\d+(?:\.\d+)?)([kmb])?$/i.exec(input.replace(/[,_\s]/g, ""));
  if (!m) return null;
  const factor = { k: 1e3, m: 1e6, b: 1e9 }[(m[2] ?? "").toLowerCase() as "k" | "m" | "b"] ?? 1;
  return Number(m[1]) * factor;
}

/** Item quantities; fractional when a recipe yields several units (half a craft of 16 → 8). */
export function formatQuantity(q: number): string {
  return Number.isInteger(q) ? q.toLocaleString("en-US") : q.toLocaleString("en-US", { maximumFractionDigits: 2 });
}

/** Runs per day: whole runs from 10 up, one decimal below (0.8 a day is not 0). */
export function formatRuns(runs: number): string {
  return runs >= 10 ? formatQuantity(Math.floor(runs)) : formatQuantity(Math.floor(runs * 10) / 10);
}

/** ISO-8601 duration as the server sends it ("PT8H", "PT1H30M", "P1DT2H", "PT28800S") → seconds. */
export function parseIsoDuration(iso: string): number | null {
  const m = /^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$/.exec(iso);
  if (!m || iso === "P" || iso.endsWith("T")) return null;
  const [, d, h, min, s] = m;
  return Math.round(Number(d ?? 0) * 86400 + Number(h ?? 0) * 3600 + Number(min ?? 0) * 60 + Number(s ?? 0));
}

/** Calendar day of an instant in a zone, as YYYY-MM-DD. */
function dayIn(epochMs: number, timeZone: string | undefined): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).format(epochMs);
}

/**
 * Clock time of an instant in a zone, with the day when it is not today:
 * "18:40", "18:40 tomorrow", "18:40 Thu 20 Nov". An unknown zone falls back to the local one.
 */
export function formatClock(epochMs: number, timeZone?: string, now: number = Date.now()): string {
  let zone = timeZone && timeZone.trim() !== "" ? timeZone : undefined;
  try {
    new Intl.DateTimeFormat("en-GB", { timeZone: zone });
  } catch {
    zone = undefined;
  }
  const time = new Intl.DateTimeFormat("en-GB", { timeZone: zone, hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
    .format(epochMs);
  const day = dayIn(epochMs, zone);
  if (day === dayIn(now, zone)) return time;
  if (day === dayIn(now + 86_400_000, zone)) return `${time} tomorrow`;
  if (day === dayIn(now - 86_400_000, zone)) return `${time} yesterday`;
  const date = new Intl.DateTimeFormat("en-GB", { timeZone: zone, weekday: "short", day: "numeric", month: "short" })
    .format(epochMs);
  return `${time} ${date}`;
}
