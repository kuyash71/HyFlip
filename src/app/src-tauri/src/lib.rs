//! HyFlip desktop shell.
//!
//! The shell owns the lifecycle of the local Java server: it starts the server when the app
//! opens, hands the UI the server URL and per-launch token, and stops the server on exit.

mod java;
mod legacy;
mod server;

use server::{ServerConnection, ServerState};
use tauri::{Manager, RunEvent, State};

/// Returns where the local server listens and the token it expects.
#[tauri::command]
fn server_connection(state: State<'_, ServerState>) -> Result<ServerConnection, String> {
    state.connection()
}

pub fn run() {
    let context = tauri::generate_context!();
    legacy::migrate(&context.config().identifier);
    tauri::Builder::default()
        .setup(|app| {
            let state = ServerState::start(app.handle());
            app.manage(state);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![server_connection])
        .build(context)
        .expect("error while building HyFlip")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                if let Some(state) = app.try_state::<ServerState>() {
                    state.stop();
                }
            }
        });
}
