# 0003. Tauri shell with React + TypeScript, server run on the user's Java

- Status: Accepted
- Date: 2026-11-17

## Context

The UI is a web app packaged as a desktop app for Linux and Windows. Options were Electron and Tauri. The server is a Java process that must start and stop with the app. The audience plays Minecraft: Java Edition.

## Decision

- **Tauri 2** as the shell, **React 19 + TypeScript** (Vite) as the UI. Electron stays the fallback if Tauri blocks something.
- The shell starts the server as a child process: `java -jar <resources>/server/hyflip-server.jar`, on a free localhost port, with a random token, the data directory and the config path. It kills the process on exit, and the server also stops itself within a few seconds once the shell's process is gone (`HYFLIP_PARENT_PID`, checked every 2 s; shutdown waits at most 5 s per phase).
- On Windows the resource path comes back as `\\?\C:\...`, which `java -jar` cannot open; the shell simplifies it first. Java runs without a console window (`CREATE_NO_WINDOW`).
- The server's stdout and stderr go to `<data-dir>/logs/server.log` (the previous run is kept as `server.1.log`), so errors Java prints before Spring starts are kept too. The UI shows this path when the server does not answer.
- The server jar is bundled; the **Java runtime is not**. The shell looks for Java 17+ in `HYFLIP_JAVA`, `JAVA_HOME`, `PATH`, then (Windows) the Java homes installers record in the registry (`HKLM\SOFTWARE\JavaSoft`, `Eclipse Adoptium`, `Microsoft\JDK`), and shows a clear error if none is found. The registry step covers an app started from a process older than the Java install (an installer's "Run" box, an old terminal), whose `PATH` is stale.
- Debug builds do not spawn the server; developers run it with Maven.

## Consequences

- Small installers and a light shell compared with Electron.
- Rust is needed to build the shell, but it only holds process lifecycle code.
- Java Edition players do not necessarily have a **system** Java: the official Minecraft launcher ships a private runtime that is usually not on `PATH`, and players on 1.8.9 clients often use Java 8. Some users will need to install Java 17+. Follow-ups if this hurts adoption:
  - also search the Minecraft launcher's bundled runtimes and common install folders;
  - ship a `jlink` runtime with the installer (adds roughly 40–60 MB).
