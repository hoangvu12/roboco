# Canonical language is prescriptive; wire and on-disk names are frozen

CONTEXT.md is the canonical domain language: UI strings, docs, comments, and new code use its terms, and collisions get renamed toward it over time (e.g. `proto::Session` → `ChatStatus`, `SessionDoc` → `ChatDoc`, "Archived sessions" → "Archived chats"). Exception: names that cross the wire or live in persisted data — serde field names, RPC method names, SQLite tables, file names (`room_gen`, `chat_outbox`, `WatchSessions`) — keep their legacy spelling so old clients and old installs keep working; they get a `// legacy wire name` comment pointing at the glossary instead.

## Consequences

- Ghost names from the deleted cloud sync (ADR 0004) stay visible in wire and storage layers; the comment, not the name, marks them dead.
- Glossary `_Avoid_` words may not appear in new user-facing strings or new code identifiers.
