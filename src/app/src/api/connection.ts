import { invoke, isTauri } from "@tauri-apps/api/core";

/** Where the local server listens and the token it expects. */
export interface ServerConnection {
  url: string;
  token: string;
  /** The desktop app's server log, shown when the server does not answer. Absent in development. */
  logFile?: string;
}

/** Used when the UI runs in a plain browser (`pnpm dev`) against `mvnw spring-boot:run`. */
export const DEV_CONNECTION: ServerConnection = {
  url: import.meta.env.VITE_SERVER_URL ?? "http://127.0.0.1:8787",
  token: import.meta.env.VITE_SERVER_TOKEN ?? "",
};

/**
 * Asks the Tauri shell which port the server sidecar got and the per-launch token.
 * Outside Tauri the dev connection is used.
 */
export async function resolveConnection(): Promise<ServerConnection> {
  if (!isTauri()) return DEV_CONNECTION;
  return invoke<ServerConnection>("server_connection");
}
