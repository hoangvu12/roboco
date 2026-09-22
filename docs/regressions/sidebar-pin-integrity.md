# Sidebar pin integrity

Roboco's port of upstream zeron 68306a17's sidebar-pin integrity notes,
re-homed for the engine-local model (the cross-device registry sync layer —
751a210b, ed04b43a, d4329257 — is deliberately NOT ported).

## Storage and scope

Every profile keeps its pins in `UiSettings.sidebar_pinned_session_ids_by_profile`,
device-local and presentation-only. There are no registry pin rows, no
migration, and no remote snapshots: the local store is the only authority,
so a profile's pins can never be judged by another profile's watch stream.
Cleanup runs against the profile's own first complete chat frame; archived
chats keep their pins, and deleted chats are pruned individually.

## Per-item intents

Menu and drop paths validate profile identity, uniqueness, and capacity
before changing anything. Rejected drops animate back; successful drops do
not replay the activity-sort animation.

Each accepted interaction queues ONE pin, unpin, or move intent
(`crates/ui/src/shell/sidebar_pins.rs::SidebarPinChange`), anchored to
neighbor session ids — never a replacement list. A surviving right anchor
takes precedence, otherwise the left anchor; if both disappear, append.
A stale move never resurrects an unpinned item. Moving a visible pin
preserves every other pin's relative order, including hidden and archived
pins (`reorder_visible_pins` moves only the dragged id).

The intent queue (`PendingSidebarPins`) orders and dedups rapid drops and
is scoped to the active profile; a profile switch discards the burst.
Locally every intent lands in `UiSettings` synchronously — the queue is a
ledger for the sidebar-sections acknowledgement flow (ticket 20), not a
transport. `finish_sidebar_pin_write` pops the front intent; popping the
last one removes the overlay, revealing the committed bucket. An
unconfirmed burst cancels queued intents and blocks new ones; a failure
notice never rolls back a newer drop.

## Capacity

The maximum of 200 pins is an admission limit for NEW pins, including
hidden and archived pins. Simultaneous additions can exceed it; all
existing pins remain visible, reorderable and removable. Cleanup never
truncates overflow; further additions are rejected until capacity is
available.

## Exclusions from the upstream document

The upstream notes' registry-row model, fractional hexadecimal ordering
keys (`pin_order_key_between`), per-field logical clocks, durable pending
outbox, engine acknowledgement revisions, and iOS/edge parity sections all
belong to the excluded sync layer. Roboco's ordering keys are the
neighbor-anchored intents above, persisted as list positions in
`UiSettings`.

## Regression commands

```sh
cargo nextest run --locked -p roboco-ui --lib -E 'test(pin) or test(session)'
```

Web parity suites: `pnpm --filter "@roboco/app" exec vitest run
tests/sidebar-pins.test.ts tests/sidebar-store.test.ts`.
