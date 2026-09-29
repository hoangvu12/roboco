//! The Roboco MCP bridge for pi runs.
//!
//! pi's first-party JSONL RPC has no MCP surface: no CLI flag, no settings
//! key, and no runtime command that attaches a server (the RPC command set
//! is closed over prompt/steer/model/session concerns). The one spawn-time
//! door pi leaves open is `--extension`, which loads a jiti-compiled
//! JavaScript module that may register tools through `pi.registerTool`.
//!
//! So the driver hands pi [`EXTENSION_SOURCE`] — the bridge that spawns the
//! engine's `roboco mcp` stdio server (the spec rides the
//! `ROBOCO_MCP_SERVER` env var: command, args, env, name) and re-exposes
//! every MCP tool as `mcp__roboco__<tool>`. That naming lets the event
//! normalizer decode the calls into `ToolCall::Mcp` chips, the same
//! rendering every other harness gives its Roboco tools.
//!
//! The file lives in a per-run [`ScratchDir`] held by the [`Session`] for
//! exactly the lifetime of the pi process: written before spawn, removed
//! after the child is shut down. A bridge that cannot be installed (temp
//! dir unwritable) degrades the run to no Roboco tools — never a failed
//! spawn, the same policy as a run with no served IPC port.

use roboco_proto::McpServer;

use crate::process::Command;
use crate::scratch::ScratchDir;

/// The bridge itself. TypeScript-free on purpose: jiti loads plain `.js`
/// without touching its TS pipeline, and the file stays loadable under any
/// Node/jiti version pi bundles.
const EXTENSION_SOURCE: &str = include_str!("mcp_extension.js");

/// The env var carrying the whole [`McpServer`] spec to the bridge.
const SERVER_SPEC_ENV: &str = "ROBOCO_MCP_SERVER";

/// Install the bridge onto one pi spawn: write the extension file into a
/// fresh scratch dir, point `--extension` at it, and stamp the server spec
/// into the environment. The returned dir must outlive the pi process —
/// the [`Session`](super::Session) holds it — or the file vanishes mid-run.
pub(crate) fn install(cmd: &mut Command, mcp: &McpServer) -> std::io::Result<ScratchDir> {
    let dir = ScratchDir::new("pi-mcp")?;
    let path = dir.path().join("roboco-mcp-bridge.js");
    std::fs::write(&path, EXTENSION_SOURCE)?;
    cmd.arg("--extension").arg(&path);
    let spec = serde_json::to_string(mcp)
        .expect("McpServer is a plain string map and always serializes");
    cmd.env(SERVER_SPEC_ENV, spec);
    Ok(dir)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The bridge source is the one the docs describe: MCP framing on
    /// stdio, tools re-registered under `mcp__roboco__*` from
    /// `session_start`, cleaned up on `session_shutdown`. Guards against
    /// an accidental edit shipping a dead bridge.
    #[test]
    fn embedded_bridge_is_the_mcp_shim() {
        assert!(EXTENSION_SOURCE.contains("tools/call"));
        assert!(EXTENSION_SOURCE.contains("tools/list"));
        assert!(EXTENSION_SOURCE.contains("registerTool"));
        assert!(EXTENSION_SOURCE.contains("ROBOCO_MCP_SERVER"));
        assert!(EXTENSION_SOURCE.contains("\"session_start\""));
        assert!(EXTENSION_SOURCE.contains("\"session_shutdown\""));
        assert!(EXTENSION_SOURCE.contains("mcp__${"));
    }

    #[test]
    fn install_points_the_extension_flag_at_a_written_file() {
        let mut command = Command::new("pi");
        let mcp = McpServer {
            name: "roboco".into(),
            command: "/bin/roboco".into(),
            args: vec!["mcp".into()],
            env: [("ROBOCO_IPC_PORT".to_owned(), "27654".to_owned())]
                .into_iter()
                .collect(),
        };
        let dir = install(&mut command, &mcp).expect("bridge installs");

        let (program, args) = {
            let std = command.as_std_mut();
            let args = std
                .get_args()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>();
            (std.get_program().to_string_lossy().into_owned(), args)
        };
        assert_eq!(program, "pi");
        let extension = args
            .windows(2)
            .find(|pair| pair[0] == "--extension")
            .map(|pair| pair[1].clone())
            .expect("--extension is passed");
        assert!(extension.ends_with("roboco-mcp-bridge.js"));
        assert!(std::path::Path::new(&extension).is_file());
        let env = command
            .as_std_mut()
            .get_envs()
            .find(|(key, _)| key.to_string_lossy() == SERVER_SPEC_ENV)
            .and_then(|(_, value)| value.map(|v| v.to_string_lossy().into_owned()))
            .expect("server spec env is set");
        let spec: serde_json::Value = serde_json::from_str(&env).expect("spec is JSON");
        assert_eq!(spec["name"], "roboco");
        assert_eq!(spec["command"], "/bin/roboco");
        assert_eq!(spec["args"][0], "mcp");
        assert_eq!(spec["env"]["ROBOCO_IPC_PORT"], "27654");
        drop(dir);
        assert!(!std::path::Path::new(&extension).exists());
    }
}
