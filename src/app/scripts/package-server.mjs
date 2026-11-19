// Packages the server jar with the repository's Maven wrapper, so no Maven install is needed (Windows included).
import { spawnSync } from "node:child_process";
import { utimesSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const server = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../server");
const windows = process.platform === "win32";
const wrapper = path.join(server, windows ? "mvnw.cmd" : "mvnw");
const result = spawnSync(wrapper, ["-B", "-q", "-DskipTests", "package"], {
  cwd: server,
  stdio: "inherit",
  // .cmd files need a shell on Windows.
  shell: windows,
});
if (result.status === 0) {
  // Maven stamps the jar with a fixed date (reproducible builds), so the Tauri build would think the
  // bundled copy is current and ship the previous jar. A fresh date makes it copy the new one.
  const now = new Date();
  utimesSync(path.join(server, "target", "hyflip-server.jar"), now, now);
}
process.exit(result.status ?? 1);
