//! Locates a Java runtime that can run the server (Java 17 or newer).
//!
//! Lookup order: `HYFLIP_JAVA`, `JAVA_HOME`, `java` on `PATH`, then (Windows) the JDKs and JREs the
//! installers record in the registry. The registry is always current, while the environment of a
//! process started before Java was installed (an installer's "Run" box, an old terminal) is not.
//! The Minecraft launcher
//! ships a private runtime that is usually not on `PATH`, so a system install may be missing even
//! for active players; the UI reports that case instead of failing silently.

use std::path::PathBuf;
use std::process::Command;

pub const MIN_JAVA_MAJOR: u32 = 17;

pub fn find_java() -> Result<PathBuf, String> {
    let exe = if cfg!(windows) { "java.exe" } else { "java" };

    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Some(path) = std::env::var_os("HYFLIP_JAVA") {
        candidates.push(PathBuf::from(path));
    }
    if let Some(home) = std::env::var_os("JAVA_HOME") {
        candidates.push(PathBuf::from(home).join("bin").join(exe));
    }
    candidates.push(PathBuf::from(exe));
    candidates.extend(registry_homes().into_iter().map(|home| home.join("bin").join(exe)));

    let mut found_old = None;
    for candidate in candidates {
        match java_major_version(&candidate) {
            Some(major) if major >= MIN_JAVA_MAJOR => return Ok(candidate),
            Some(major) => found_old = Some((candidate, major)),
            None => {}
        }
    }

    Err(match found_old {
        Some((path, major)) => format!(
            "Java {major} found at {}, but HyFlip needs Java {MIN_JAVA_MAJOR} or newer.",
            path.display()
        ),
        None => format!(
            "No Java runtime found. Install Java {MIN_JAVA_MAJOR}+ or set HYFLIP_JAVA to a java executable."
        ),
    })
}

/// Keeps `java.exe` (and `reg.exe`) from opening a console window next to the GUI app on Windows.
pub fn no_console_window(command: &mut Command) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    #[cfg(not(windows))]
    let _ = command;
}

/// Registry keys where Java installers record their home folder, and the value that holds it.
#[cfg(windows)]
const REGISTRY_KEYS: [(&str, &str); 5] = [
    (r"HKLM\SOFTWARE\JavaSoft\JDK", "JavaHome"),
    (r"HKLM\SOFTWARE\JavaSoft\JRE", "JavaHome"),
    (r"HKLM\SOFTWARE\Eclipse Adoptium\JDK", "Path"),
    (r"HKLM\SOFTWARE\Eclipse Adoptium\JRE", "Path"),
    (r"HKLM\SOFTWARE\Microsoft\JDK", "Path"),
];

/// Java homes recorded in the Windows registry, newest-looking first; empty elsewhere.
fn registry_homes() -> Vec<PathBuf> {
    #[cfg(windows)]
    {
        let mut homes: Vec<String> = Vec::new();
        for (key, value) in REGISTRY_KEYS {
            let mut command = Command::new("reg");
            no_console_window(&mut command);
            if let Ok(out) = command.args(["query", key, "/s", "/v", value, "/reg:64"]).output() {
                homes.extend(parse_reg_values(&String::from_utf8_lossy(&out.stdout)));
            }
        }
        homes.sort();
        homes.dedup();
        homes.reverse();
        homes.into_iter().map(PathBuf::from).collect()
    }
    #[cfg(not(windows))]
    Vec::new()
}

/// String values from `reg query` output: lines like `    JavaHome    REG_SZ    C:\Program Files\...`.
#[cfg_attr(not(windows), allow(dead_code))]
fn parse_reg_values(text: &str) -> Vec<String> {
    text.lines()
        .filter_map(|line| {
            let (_, rest) = line.split_once("REG_SZ").or_else(|| line.split_once("REG_EXPAND_SZ"))?;
            let value = rest.trim().trim_end_matches(['\\', '/']);
            (!value.is_empty()).then(|| value.to_string())
        })
        .collect()
}

/// Runs `java -version` and parses the major version from its output (printed to stderr).
fn java_major_version(java: &PathBuf) -> Option<u32> {
    let mut command = Command::new(java);
    no_console_window(&mut command);
    let output = command.arg("-version").output().ok()?;
    let text = String::from_utf8_lossy(&output.stderr);
    parse_major_version(&text)
}

/// Parses `version "17.0.2"`, `version "21"` and legacy `version "1.8.0_392"`.
fn parse_major_version(text: &str) -> Option<u32> {
    let start = text.find("version \"")? + "version \"".len();
    let rest = &text[start..];
    let version = &rest[..rest.find('"')?];
    let mut parts = version.split(['.', '_', '-', '+']);
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 {
        parts.next()?.parse().ok()
    } else {
        Some(first)
    }
}

#[cfg(test)]
mod tests {
    use super::{parse_major_version, parse_reg_values};

    #[test]
    fn parses_modern_and_legacy_versions() {
        assert_eq!(parse_major_version(r#"openjdk version "17.0.20.1" 2026-08-18"#), Some(17));
        assert_eq!(parse_major_version(r#"openjdk version "21" 2023-09-19"#), Some(21));
        assert_eq!(parse_major_version(r#"java version "1.8.0_392""#), Some(8));
        assert_eq!(parse_major_version("garbage"), None);
    }

    #[test]
    fn reads_java_homes_from_reg_query_output() {
        let out = "\r\nHKEY_LOCAL_MACHINE\\SOFTWARE\\JavaSoft\\JDK\\17\r\n    JavaHome    REG_SZ    C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.9.9-hotspot\\\r\n\r\n\
                   HKEY_LOCAL_MACHINE\\SOFTWARE\\JavaSoft\\JDK\\21\r\n    JavaHome    REG_SZ    C:\\Java\\jdk-21\r\n\r\nEnd of search: 2 match(es) found.\r\n";
        assert_eq!(
            parse_reg_values(out),
            vec![r"C:\Program Files\Eclipse Adoptium\jdk-17.0.9.9-hotspot", r"C:\Java\jdk-21"]
        );
        assert!(parse_reg_values("ERROR: The system was unable to find the specified registry key or value.").is_empty());
    }
}
