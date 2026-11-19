//! Starts, tracks and stops the local Java server.
//!
//! * Release builds run the bundled `server/hyflip-server.jar` with the system Java on a free
//!   localhost port, passing a fresh auth token, the data directory and the config path.
//! * Debug builds do not spawn anything: run the server yourself (`./mvnw spring-boot:run`) and
//!   the shell connects to `HYFLIP_SERVER_URL` (default `http://127.0.0.1:8787`) with
//!   `HYFLIP_SERVER_TOKEN` (default empty, matching the server's dev default).

use serde::Serialize;
use std::net::TcpListener;
use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use tauri::path::BaseDirectory;
use tauri::{AppHandle, Manager};

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerConnection {
    pub url: String,
    pub token: String,
    /// Where the server's output goes, shown when it does not answer. None in debug builds.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub log_file: Option<String>,
}

pub struct ServerState {
    connection: Result<ServerConnection, String>,
    child: Mutex<Option<Child>>,
}

impl ServerState {
    pub fn start(app: &AppHandle) -> Self {
        if cfg!(debug_assertions) {
            return Self {
                connection: Ok(ServerConnection {
                    url: std::env::var("HYFLIP_SERVER_URL")
                        .unwrap_or_else(|_| "http://127.0.0.1:8787".into()),
                    token: std::env::var("HYFLIP_SERVER_TOKEN").unwrap_or_default(),
                    log_file: None,
                }),
                child: Mutex::new(None),
            };
        }

        match spawn(app) {
            Ok((connection, child)) => Self {
                connection: Ok(connection),
                child: Mutex::new(Some(child)),
            },
            Err(message) => {
                log::error!("could not start server: {message}");
                Self {
                    connection: Err(message),
                    child: Mutex::new(None),
                }
            }
        }
    }

    pub fn connection(&self) -> Result<ServerConnection, String> {
        self.connection.clone()
    }

    pub fn stop(&self) {
        if let Some(mut child) = self.child.lock().ok().and_then(|mut c| c.take()) {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

fn spawn(app: &AppHandle) -> Result<(ServerConnection, Child), String> {
    let java = crate::java::find_java()?;
    let jar = app
        .path()
        .resolve("server/hyflip-server.jar", BaseDirectory::Resource)
        .map_err(|e| format!("server jar not found: {e}"))?;
    // Windows resolves resources to `\\?\C:\...`, which `java -jar` cannot open.
    let jar = dunce::simplified(&jar).to_path_buf();
    let data_dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("no data directory: {e}"))?;
    let config_file = app
        .path()
        .app_config_dir()
        .map_err(|e| format!("no config directory: {e}"))?
        .join("config.yaml");

    let port = free_port()?;
    let token = uuid::Uuid::new_v4().to_string();
    let log_file = open_log(&data_dir)?;
    let log_path = log_file.1.display().to_string();
    let stderr = log_file.0.try_clone().map_err(|e| format!("cannot open the server log: {e}"))?;

    let mut command = Command::new(&java);
    crate::java::no_console_window(&mut command);
    let child = command
        // A release app has no console: without this the server's output, and any error Java prints
        // before Spring starts, would be lost.
        .stdout(Stdio::from(log_file.0))
        .stderr(Stdio::from(stderr))
        .stdin(Stdio::null())
        .arg("-jar")
        .arg(&jar)
        .arg(format!("--server.port={port}"))
        .arg(format!("--hyflip.data-dir={}", data_dir.display()))
        .env("HYFLIP_CONFIG", &config_file)
        // Passed through the environment so it does not show up in process listings.
        .env("HYFLIP_AUTH_TOKEN", &token)
        // The server stops itself once this process is gone (crash, kill, logout skip RunEvent::Exit).
        .env("HYFLIP_PARENT_PID", std::process::id().to_string())
        .spawn()
        .map_err(|e| format!("failed to run {}: {e}", java.display()))?;

    Ok((
        ServerConnection {
            url: format!("http://127.0.0.1:{port}"),
            token,
            log_file: Some(log_path),
        },
        child,
    ))
}

/// Opens `<data-dir>/logs/server.log` for this run; the previous run's log is kept as `server.1.log`.
fn open_log(data_dir: &Path) -> Result<(File, PathBuf), String> {
    let dir = data_dir.join("logs");
    fs::create_dir_all(&dir).map_err(|e| format!("cannot create {}: {e}", dir.display()))?;
    let path = dir.join("server.log");
    if path.exists() {
        let _ = fs::rename(&path, dir.join("server.1.log"));
    }
    let file = File::create(&path).map_err(|e| format!("cannot create {}: {e}", path.display()))?;
    Ok((file, path))
}

/// Asks the OS for a free port. Another process could take it before the server binds; the UI
/// then reports the server as unavailable.
fn free_port() -> Result<u16, String> {
    TcpListener::bind("127.0.0.1:0")
        .and_then(|l| l.local_addr())
        .map(|a| a.port())
        .map_err(|e| format!("no free port: {e}"))
}
