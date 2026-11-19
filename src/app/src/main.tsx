import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import "@fontsource/jersey-20/latin-400.css";
import "@fontsource/jetbrains-mono/latin-400.css";
import "@fontsource/jetbrains-mono/latin-700.css";
import "./styles.css";

// Before the rename to HyFlip (2026-11) the app's saved choices used the "skypayna." prefix: keep them.
try {
  for (const key of Object.keys(localStorage)) {
    if (key.startsWith("skypayna.")) {
      const renamed = "hyflip." + key.slice("skypayna.".length);
      if (localStorage.getItem(renamed) === null) localStorage.setItem(renamed, localStorage.getItem(key)!);
      localStorage.removeItem(key);
    }
  }
} catch {
  // Storage unavailable.
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
