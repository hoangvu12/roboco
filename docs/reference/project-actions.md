# Project Actions

Project Actions are named shell commands attached to a space. They can be run from the selected chat's title bar, and one saved Action can optionally run when Roboco creates a new worktree.

## Trust and storage

Actions are private configuration on the device that owns the space. Roboco stores them in the owning engine profile's store root (`project-actions.json`); they are not written to the workspace registry, session documents, or any synced structure.

Opening a repository never authorizes a command. A repository may offer Actions through `roboco.json`, but each candidate must be explicitly imported before it can be run or selected as setup.

For a remote space, list, edit, delete, and run requests go over the paired connection to the owning engine. The client resolves that connection by the engine-scoped chat and space ids; `targetDeviceId` is a client-side routing hint that is decoded and stripped at the socket, and the engine's fail-closed entry check asserts the request targeted the right connection. The viewing device never resolves the remote path or falls back to running the command locally when the owner is offline.

## `roboco.json`

Place `roboco.json` at the exact project root to offer version-controlled imports:

```json
{
  "actions": [
    {
      "name": "Dev server",
      "command": "pnpm dev",
      "icon": "play",
      "runOnWorktreeCreate": false
    }
  ]
}
```

The supported icons are `play`, `test`, `lint`, `configure`, `build`, and `debug`. Unknown fields, malformed JSON, invalid Actions, or more than 50 entries invalidate the whole file. An import is hidden when a saved Action has the same exact command or the same case-insensitive name.

## Limits and normalization

A project can store at most 50 Actions. Names are trimmed, must be non-empty, and may contain at most 80 Unicode characters. Commands are trimmed, must be non-empty, and may contain at most 16 KiB of UTF-8 data.

The owning engine generates a stable slug id of at most 96 bytes and adds a deterministic numeric suffix on collision. Saving an Action with `runOnWorktreeCreate` enabled atomically disables that flag on every other Action in the project.

## Execution

Every invocation opens a fresh managed terminal. On Unix, Roboco stores the exact saved command in a private temporary script on the owning device and sends a short instruction to source it in the user's interactive login shell. This preserves long lines and multiline commands across shell initialization without passing them through the terminal's limited input-line buffer. The script is removed when the shell exits or the terminal is closed. Windows retains direct command submission to its native terminal, which does not use the Unix canonical line buffer. Roboco does not wrap the command in `sh -c` or reuse a terminal whose foreground state is unknown.

The owning engine validates that the Space, Chat, and checkout belong to the same local project before opening the PTY. A manual run uses the chat checkout as its working directory and injects:

```text
ROBOCO_PROJECT_ROOT=<canonical project root>
ROBOCO_WORKTREE_PATH=<canonical chat worktree, only outside the main checkout>
```

The terminal output is replayable, so output produced before the desktop subscribes is still displayed. Subscribe, resize, write, and close requests stay on the terminal's owning connection.

The main title-bar segment remembers the last successfully started Action for that project in viewport-local UI settings. If it no longer exists, Roboco chooses the first non-setup Action and then the first Action as a final fallback.

## Worktree setup

Only creation of a new worktree can start the setup Action. Reusing an existing worktree and using the main checkout do not run setup again.

For desktop sends, the worktree directive rides the durable queued `Run` command. The owning engine creates the worktree and starts setup while draining that command, before dispatching the first agent turn. Setup runs with the new worktree as its cwd and always receives both `ROBOCO_PROJECT_ROOT` and `ROBOCO_WORKTREE_PATH`.

The queue reply is not held open while the host creates the worktree. Desktop polls a short-lived, command-scoped handoff to attach the already-open setup terminal when it becomes available, so a lost reply cannot leave the composer stuck while the agent runs remotely.

Direct `CreateWorktree` callers retain the same host-side setup behavior and receive the optional setup terminal in the RPC outcome.

Desktop attaches the returned terminal to the bottom terminal drawer of the newly minted chat, even if another chat becomes selected while the request is in flight. Manual Actions use the same bottom drawer.

A failure to open or initialize the setup terminal is returned as `setupError`. The worktree remains available and the first agent turn continues; desktop presents the error as a non-blocking notice.

`CreateWorktree` and `RunRequest.worktree` remain additive and wire-compatible across versions. Callers that omit `spaceId` receive worktree creation without setup, legacy clients ignore optional fields, and desktop silently skips the handoff when connected to an older host.

## Out of scope

Project Actions do not define global keybindings. Preview URLs and automatic browser opening are also intentionally absent.
