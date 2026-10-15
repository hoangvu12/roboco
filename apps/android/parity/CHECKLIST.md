# The reviewer's walk — iOS parity checklist (android-app#30)

This is the human half of the parity gate. `ParityManifestTest` (G2)
fails unless every committed golden's SHA-256 is pinned here in
`manifest.json` with a reviewer, a date, and the checklist item ids that
were walked — so skipping this walk is impossible once a golden lands.

## How to review

1. Open the `parityReport` artifact (`build/parity/index.html`, uploaded
   by the `android-screenshots` CI job on **every** run) next to the iOS
   reference for the same golden; same mode, same scroll position.
2. Walk the golden's `checklistItems` (from `manifest.json`) in the
   sections below. Every item cites its Swift `file:line` (read with
   `git show upstream/main:apps/ios/Zeron/<path>`) or a measured value.
3. **Tone-only references** (marked so in `apps/android/parity/ios/SOURCES.md`)
   are judged on colour, type, bubble and rail only — structure and
   metrics come from the Swift source.
4. Record the pass: fill `reviewedSha256` (the recorded PNG's SHA-256),
   `reviewer`, `date` and any notes in `manifest.json`, in the same PR —
   `./gradlew :app:parityManifest --sign=<goldens> --reviewer=… --date=…`
   pins all three from the files (see `README.md` here).
   For the SSIM entries, also record `ssimFloor` = first score − 0.02 (by hand).

## Sessions list (`sessions-list`, `sessions-group-by-project`)

References: `pr874/1-off.png`, `pr874/3-on.png`; structure from
`ios:Threads/SessionCell.swift` and `ios:Shell/MainTabController.swift`.

- [ ] `SL-01` — Large title "Sessions" collapses to the inline
      SemiBold 17 title under the scroll (the reference's top band;
      `MainTabController.swift:229-356`).
- [ ] `SL-02` — Row horizontal margins 20 ± 1 dp
      (`SessionCell.swift`; research 01 §2.3).
- [ ] `SL-03` — Harness mark 20 dp, gap to title 14 dp (#594).
- [ ] `SL-04` — Section header "P0" SemiBold 13.5 `secondary`, count
      Medium 13.5 `tertiary` (research 01 §2.2).
- [ ] `SL-05` — Status/accessory column: the "✓ Done + PR #77 closed"
      trailing (danger tone) and "● Failed" shapes from the parity
      fixture table (§2 of the ticket).
- [ ] `SL-06` — Rows have no separators, no gaps (the reference's
      rhythm; `SessionCell.swift`).
- [ ] `SL-07` — Accessory capsule: plus plate 34 Ø `accentSoft`, label
      "New session" Medium 16, summary "1 needs you" Medium 13
      `secondary` (`MainTabController.swift:229-356`).
- [ ] `SL-08` — Group by Project on: groups "zeron 1", "Home ·
      MacBook Pro 1", "edge 1", "blog 1" in reference order, tile
      letters Z/H/E/B (`3-on.png`).

## Wallpaper hero (`sessions-wallpaper`)

Reference: `mobile-polish/home-glass.png` (tone-only).

- [ ] `WP-01` — Hero height 629 dp at scroll 0, fading to 0 at
      377.568 dp (ui-parity #21's measured table).
- [ ] `WP-02` — Glass tab bar over the artwork: the reference's tint
      and blur recipe (tone-only).
- [ ] `WP-03` — Scroll fade: rows slide under the hero's bottom fade,
      not over it.

## Folder — archived (`folder-archived`)

Structure: `ios:Threads/SessionListController.swift:422-460`.

- [ ] `FA-01` — "Archived" plain header (no fold chevron), rows with
      the Unarchive trailing action.
- [ ] `FA-02` — Empty state copy verbatim ("No archived sessions.").
- [ ] `FA-03` — Back returns to the front page at the same scroll.

## Transcript fixture (`session-transcript-fixture`)

Reference: `mobile-polish/tool-activity.png` (tone-only); metrics from
`mob:layout/rows.rs:124-138`.

- [ ] `TR-01` — `MARGIN_X` 18 dp, user bubble radius 20 dp
      (`rows.rs:124-138`).
- [ ] `TR-02` — Reading column ≤ 768 dp centred (the 320 and 768 dp
      goldens re-measure it).
- [ ] `TR-03` — The fixture's user turn reads
      "How does the layout engine avoid measuring text on the main thread?"
      and the reply is `layoutFixtureMarkdown()` (the core's fixture,
      `mob:layout/mod.rs:282-286`).
- [ ] `TR-04` — Tool groups render like desktop (badge, inline stats,
      the expanded disclosure) — tone judged against
      `mobile-tool-disclosure/tool-group-expanded.png`.
- [ ] `TR-05` — Mono/code: keywords, strings, comments in the core's
      palette (tone-only against the reference).

## Streaming (`session-streaming`)

Reference: `mobile-polish/send-runway.png` (tone-only).

- [ ] `ST-01` — Veil over the streaming row (frozen at 50% in the
      golden), `working = true` trailer glyph.
- [ ] `ST-02` — The runway: the prompt at the top, reply streaming
      into the reserved space below.
- [ ] `ST-03` — No "Uploading"-style banners; the send button is Stop.

## Settings (`settings`)

Reference: `pr874/2-settings.png`; the G3 metric table (all ± 2 dp /
ΔE ≤ 3 unless noted).

- [ ] `S-01` — Page background `#F3F3F5`, card fill `#FFFFFF`.
- [ ] `S-02` — Card left/right inset 16 ± 1 dp.
- [ ] `S-03` — Row height: title-only 51, +1 subtitle line 67, +2
      lines 83 (± 2).
- [ ] `S-04` — Icon leading 16 ± 1 dp from the card edge; text leading
      56 ± 2 dp.
- [ ] `S-05` — Toggle 62×27 ± 2 dp, ON colour `#5B43E8`.
- [ ] `S-06` — Checkmark colour `#5B43E8`.
- [ ] `S-07` — Sections: the engine "MacBook Pro" Connected; the
      notification master off; Group by Project on; no wallpaper row
      (mirrors the reference's list).

## Known deviations (deliberate, each with a rationale)

| Deviation | Rationale |
|---|---|
| SF Symbols → the desktop's SVG line-icon set (`zi_*`) | The shared icon pipeline (android-app#13); sizes matched at the 20 dp grid |
| Android status/navigation bars vs iOS's inset handling | Edge-to-edge with transparent bars (ui-parity #35); Robolectric draws no system chrome, the device pass covers the real bars |
| Predictive back instead of iOS's edge-swipe pop | The platform's back contract; the cancelled-swipe flow test ports the gesture shape |
| Roboco-adapted copy ("engine" for "computer", the pairing root's wording from the web client) | ADR 0003/0004: no cloud, no accounts; copy cited per-screen in the owning tickets |
| Glass blur falls back to the tint fill on API 31–32 and under Robolectric | Haze's non-blur fallback (ui-parity #07); judged on device (see the device pass) |

## Device pass (a physical device; tick with device + build noted)

- [ ] `DP-01` — Live Update chip (API 36) animates in the notification.
- [ ] `DP-02` — Glass refraction on API 33+ (the blur recipe, not the
      fallback).
- [ ] `DP-03` — 120 Hz scrolling: the transcript hitch ratio
      (`TranscriptBenchTest`, `hitchRatioMsPerS < 5`).
- [ ] `DP-04` — Haptics feel (the `design/HapticsGallerySection`
      walk of research 01 §4.2's table).

## Adding a screen

A new golden gets a manifest entry (G2 bites the moment the PNG lands),
a section here with stable ids, and — where an iOS capture exists — an
`iosRef` plus an `ssimFloor` after the first sign-off. Pre-rewrite
references are tone-only; say so in the entry's `notes`.
