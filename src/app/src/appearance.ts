/** Theme and accent colour, per viewer (this browser or desktop window only). */
export type Theme = "system" | "light" | "dark";

export interface Appearance {
  theme: Theme;
  /** `#rrggbb`, or null for the theme's own accent. */
  accent: string | null;
}

const KEY = "hyflip.appearance";
export const DEFAULT_APPEARANCE: Appearance = { theme: "system", accent: null };

export function loadAppearance(): Appearance {
  try {
    const saved = localStorage.getItem(KEY);
    if (saved) {
      const a = JSON.parse(saved) as Partial<Appearance>;
      return {
        theme: a.theme === "light" || a.theme === "dark" ? a.theme : "system",
        accent: typeof a.accent === "string" && /^#[0-9a-f]{6}$/i.test(a.accent) ? a.accent : null,
      };
    }
  } catch {
    // storage unavailable or corrupt
  }
  return DEFAULT_APPEARANCE;
}

export function saveAppearance(a: Appearance): void {
  applyAppearance(a);
  try {
    localStorage.setItem(KEY, JSON.stringify(a));
  } catch {
    // applied for this session only
  }
}

export function applyAppearance(a: Appearance, root: HTMLElement = document.documentElement): void {
  if (a.theme === "system") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", a.theme);
  if (a.accent) root.style.setProperty("--accent", a.accent);
  else root.style.removeProperty("--accent");
}
