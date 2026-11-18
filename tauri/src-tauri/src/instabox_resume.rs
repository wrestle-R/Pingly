// SPDX-License-Identifier: GPL-3.0-or-later
use serde::Deserialize;
use std::{
    fs,
    os::unix::fs::PermissionsExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::Mutex,
    time::{Duration, Instant},
};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Config {
    phone_id: String,
    helper: PathBuf,
    status_helper: Option<PathBuf>,
}

pub struct ResumeRelay {
    config: Config,
    last_request: Mutex<Option<Instant>>,
    last_status: Mutex<Option<(Instant, bool)>>,
}

impl ResumeRelay {
    pub fn load(path: &Path) -> Option<Self> {
        let metadata = fs::symlink_metadata(path).ok()?;
        if !metadata.is_file()
            || metadata.len() > 4096
            || metadata.permissions().mode() & 0o077 != 0
        {
            return None;
        }
        let config: Config = serde_json::from_slice(&fs::read(path).ok()?).ok()?;
        if config.phone_id.is_empty()
            || config.phone_id.len() > 128
            || !config
                .phone_id
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
            || !Self::valid_helper(&config.helper)
            || config
                .status_helper
                .as_deref()
                .is_some_and(|path| !Self::valid_helper(path))
        {
            return None;
        }
        Some(Self {
            config,
            last_request: Mutex::new(None),
            last_status: Mutex::new(None),
        })
    }

    fn valid_helper(path: &Path) -> bool {
        path.is_absolute()
            && fs::metadata(path).is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
    }

    pub fn status_available(&self) -> bool {
        self.config.status_helper.is_some()
    }

    pub fn status(&self, peer: &str) -> &'static str {
        if !self.permits(peer) {
            return "denied";
        }
        self.probe(false)
    }

    fn probe(&self, fresh: bool) -> &'static str {
        let Some(helper) = &self.config.status_helper else {
            return "unavailable";
        };
        let Ok(mut cached) = self.last_status.lock() else {
            return "unknown";
        };
        if !fresh {
            if let Some((at, running)) = *cached {
                if at.elapsed() < Duration::from_secs(5) {
                    return if running { "running" } else { "unknown" };
                }
            }
        }
        let running = Command::new("/usr/bin/timeout")
            .arg("8s")
            .arg(helper)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .is_ok_and(|exit| exit.success());
        *cached = Some((Instant::now(), running));
        if running {
            "running"
        } else {
            "unknown"
        }
    }

    pub fn permits(&self, peer: &str) -> bool {
        self.config.phone_id == peer
    }

    pub fn request(&self, peer: &str) -> &'static str {
        if !self.permits(peer) {
            return "denied";
        }
        let Ok(mut last) = self.last_request.lock() else {
            return "failed";
        };
        if last.is_some_and(|at| at.elapsed() < Duration::from_secs(60)) {
            return "busy";
        }
        *last = Some(Instant::now());
        drop(last);

        if self.probe(true) == "running" {
            return "running";
        }

        // The enrolled peer cannot supply a command, argument, or machine ID.
        let output = Command::new("/usr/bin/timeout")
            .arg("50s")
            .arg(&self.config.helper)
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .output();
        let accepted = output
            .ok()
            .filter(|o| o.status.success() && o.stdout.len() <= 4096)
            .and_then(|o| serde_json::from_slice::<serde_json::Value>(&o.stdout).ok())
            .is_some_and(|body| {
                matches!(body["http_status"].as_str(), Some("200" | "202"))
                    && body["type"] == "resume"
                    && body["has_error"] == false
                    && !matches!(
                        body["state"].as_str(),
                        Some("failed" | "canceled" | "cancelled")
                    )
            });
        if accepted {
            if let Ok(mut cached) = self.last_status.lock() {
                *cached = None;
            }
            "requested"
        } else {
            "failed"
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_configured_phone_can_request_once_per_cooldown() {
        let dir = std::env::temp_dir().join(format!("pingora-wake-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let helper = dir.join("resume");
        fs::write(&helper, "#!/bin/sh\nprintf '%s' '{\"http_status\":\"202\",\"type\":\"resume\",\"has_error\":false}'").unwrap();
        fs::set_permissions(&helper, fs::Permissions::from_mode(0o700)).unwrap();
        let config = dir.join("resume.json");
        fs::write(
            &config,
            serde_json::json!({"phone_id":"owner-phone","helper":helper}).to_string(),
        )
        .unwrap();
        fs::set_permissions(&config, fs::Permissions::from_mode(0o644)).unwrap();
        assert!(ResumeRelay::load(&config).is_none());
        fs::set_permissions(&config, fs::Permissions::from_mode(0o600)).unwrap();
        let relay = ResumeRelay::load(&config).unwrap();
        assert_eq!(relay.request("other-phone"), "denied");
        assert_eq!(relay.request("owner-phone"), "requested");
        assert_eq!(relay.request("owner-phone"), "busy");
        let status = dir.join("status");
        fs::write(&status, "#!/bin/sh\nexit 0").unwrap();
        fs::set_permissions(&status, fs::Permissions::from_mode(0o700)).unwrap();
        fs::write(
            &config,
            serde_json::json!({"phone_id":"owner-phone","helper":helper,"status_helper":status})
                .to_string(),
        )
        .unwrap();
        let relay = ResumeRelay::load(&config).unwrap();
        assert_eq!(relay.status("other-phone"), "denied");
        assert_eq!(relay.status("owner-phone"), "running");
        assert_eq!(relay.request("owner-phone"), "running");
        fs::remove_dir_all(dir).unwrap();
    }
}
