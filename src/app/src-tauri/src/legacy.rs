//! Carries the app's folders over from before it was renamed from SkyPAYNA to HyFlip (2026-11).
//!
//! The folders are named after the bundle identifier, which changed from `com.skypayna.app` to
//! `com.hyflip.app`. This runs before any window opens, so the web view (whose storage lives in
//! these folders) and the server find their data under the new name. A folder of the new name is
//! never replaced.

use std::path::{Path, PathBuf};

const OLD_ID: &str = "com.skypayna.app";

pub fn migrate(identifier: &str) {
    for base in base_dirs() {
        rename_if_new_missing(&base.join(OLD_ID), &base.join(identifier));
    }
}

fn rename_if_new_missing(old: &Path, new: &Path) {
    if old.is_dir() && !new.exists() {
        if let Err(e) = std::fs::rename(old, new) {
            eprintln!("could not move {} to {}: {e}", old.display(), new.display());
        }
    }
}

/// Where Tauri keeps app data, config and (on Windows) the web view's local data.
fn base_dirs() -> Vec<PathBuf> {
    let env = |k: &str| std::env::var_os(k).filter(|v| !v.is_empty()).map(PathBuf::from);
    let mut dirs = Vec::new();
    if cfg!(windows) {
        dirs.extend(env("APPDATA"));
        dirs.extend(env("LOCALAPPDATA"));
    } else if cfg!(target_os = "macos") {
        dirs.extend(env("HOME").map(|h| h.join("Library/Application Support")));
    } else {
        let home = env("HOME");
        dirs.extend(env("XDG_DATA_HOME").or_else(|| home.as_ref().map(|h| h.join(".local/share"))));
        dirs.extend(env("XDG_CONFIG_HOME").or_else(|| home.as_ref().map(|h| h.join(".config"))));
    }
    dirs.dedup();
    dirs
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn moves_the_old_folder_once_and_never_over_a_new_one() {
        let root = std::env::temp_dir().join(format!("hyflip-legacy-{}", std::process::id()));
        let (old, new) = (root.join("old"), root.join("new"));
        std::fs::create_dir_all(&old).unwrap();
        std::fs::write(old.join("a.db"), "x").unwrap();
        rename_if_new_missing(&old, &new);
        assert!(new.join("a.db").exists() && !old.exists());

        std::fs::create_dir_all(&old).unwrap();
        rename_if_new_missing(&old, &new);
        assert!(old.exists(), "a second old folder must not replace the new one");
        std::fs::remove_dir_all(&root).unwrap();
    }
}
