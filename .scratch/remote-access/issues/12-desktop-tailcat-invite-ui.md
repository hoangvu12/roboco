# 12 — Desktop Tailcat invite UI + Devices copy

**What to build:** The desktop Remote access page gets a "Create Tailcat invite"
action beside "Create pairing link": it calls `CREATE_TAILCAT_INVITE` with
`ttlSeconds: 300` and renders the returned `roboco-tailcat:…` invite with a Copy
button and the same single-use/expiry framing the pairing link card uses. The
Devices page paste box learns Tailcat exists: placeholder and hint mention
`roboco-tailcat:…` invites, matching the web's wording
(`web/packages/app/src/routes/settings-devices.tsx`). No engine or proto changes.

**Blocked by:** none (engine RPC, adapter, and registry consumer already shipped).

**Status:** resolved

**Parent:** `.scratch/remote-access/spec.md`

- [x] Remote access page offers "Create Tailcat invite" when remote access is enabled
- [x] The minted invite renders with Copy and an expiry hint; failures land in the error strip
- [x] Invites are not logged; the invite string appears only in the UI card
- [x] Devices paste box placeholder/hint mention `roboco-tailcat:…` invites
- [x] `cargo fmt`, `cargo clippy`, and the existing `remote_settings` / `tailcat_invite_cli` tests stay green
- [x] `RemoteAccessStatus` transport badge is **out of scope** (proto change + web codegen gate)

## Comments

- Scope agreed 2026-10-15: minimal producer surface on desktop only; the web
  parity page stays pairing-link-only for now (its header already claims desktop
  parity, so a follow-up ticket would cover it if wanted).
- The engine already returns actionable errors when Tailcat is disabled or the
  adapter is missing ("Tailcat route unavailable…"); the button may be shown
  whenever remote access is enabled and let errors explain, rather than gating
  on transport state the snapshot does not carry.

## Implementation and validation

The Remote access page's "Pairing link" section header now carries both minting
actions side by side: "Create pairing link" and "Create Tailcat invite" (the
latter shown whenever remote access is enabled, per the scope note — the engine's
clean errors explain when Tailcat is off). The invite action reuses the page's
existing `request()` path with `{"ttlSeconds": 300}`, so the busy-gate covers
both buttons and a second click while one is in flight is ignored, never
stacked. The reply (`{id, url, expiresAt}`) is deserialized by a page-local
`TailcatInviteLink` (proto untouched); only `url` is kept, in a new `invite`
field, and it renders as a second card with the pairing link's exact pattern —
mono `url_fragment`, "Use once within five minutes." meta line, and a Copy ghost
action. Failures and the post-mint snapshot refresh land in the existing error
strip / snapshot flow unchanged. Nothing logs the invite: it exists only in the
card and the clipboard, same discipline as the pairing link. The Devices paste
box placeholder now reads "Paste a pairing URL or roboco-tailcat:… invite"
(matching the web's phrasing) and its hint mentions minting a
`roboco-tailcat:…` invite in the engine's Remote access settings.

Validation (2026-10-15): `rustfmt` applied to both touched files (the repo
carries pre-existing fmt drift elsewhere on `main`, so only the touched files
were formatted — `cargo fmt --check` is clean for both). `cargo clippy -p
roboco --all-targets` passes with no errors; `cargo clippy -p roboco-ui
--all-targets` emits no diagnostics for either touched file (the run's one
error is a pre-existing `clippy::invisible_characters` deny in
`crates/ui/src/files/preview.rs`, untouched and already on `main`; CI does not
run clippy). `cargo test -p roboco-engine --test remote_settings`,
`cargo test -p roboco --test tailcat_invite_cli`, and `cargo test -p roboco-ui`
(pass) all green, and `cargo build -p roboco` compiles.
