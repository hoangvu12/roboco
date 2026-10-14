# iOS reference images (the parity gate's inputs, android-app#30)

Upstream (zeronsh/zeron) is MIT-licensed; keep this attribution with the
copies. Do not edit or re-encode the PNGs.

| Path | Size (px) | Origin | What it shows |
|---|---|---|---|
| `pr874/1-off.png` | 460×1000 | `https://raw.githubusercontent.com/zeronsh/zeron/pr-assets/group-by-project/1-off.png` (branch `pr-assets`) | Sessions tab, Group by Project **off**, light, demo data — the post-rewrite capture (PR #874, merged as `edb23db4`, screenshots kept in the PR body) |
| `pr874/2-settings.png` | 460×1000 | same branch/folder | Settings tab, light — the G3 metric table's source |
| `pr874/3-on.png` | 460×1000 | same branch/folder | Sessions tab, Group by Project **on**, light |
| `mobile-polish/home-glass.png` | 1206×2622 | `git show upstream/main:docs/screenshots/mobile-polish/home-glass.png` | Home glass (wallpaper hero) — **tone-only** reference (pre-rewrite) |
| `mobile-polish/tool-activity.png` | 1206×2622 | `git show upstream/main:docs/screenshots/mobile-polish/tool-activity.png` | Tool activity in the transcript — **tone-only** |
| `mobile-polish/send-runway.png` | 1206×2622 | `git show upstream/main:docs/screenshots/mobile-polish/send-runway.png` | Send runway / streaming veil — **tone-only** |
| `mobile-tool-disclosure/tool-group-expanded.png` | 1206×2622 | `git show upstream/main:docs/screenshots/mobile-tool-disclosure/tool-group-expanded.png` | Expanded tool group — **tone-only** |
| `appshots/appshots-ios-queue-actions.png` | 1206×2622 | `git show upstream/main:docs/screenshots/appshots/appshots-ios-queue-actions.png` | Queue actions sheet — **tone-only** |
| `mobile-composer-polish/after-chip.png` | 600×298 | `git show 06e94f5a:docs/screenshots/mobile-composer-polish/after-chip.png` | Composer chips (#593's crop) — **tone-only** |
| `mobile-composer-polish/after-draft.png` | 600×219 | `git show 06e94f5a:docs/screenshots/mobile-composer-polish/after-draft.png` | Composer draft resting (#593's crop) — **tone-only** |
| `mobile-composer-polish/after-menu.png` | 600×447 | `git show 06e94f5a:docs/screenshots/mobile-composer-polish/after-menu.png` | Composer menu (#593's crop) — **tone-only** |

`pr874/*` are byte-identical to `apps/android/app/src/test/resources/ios-reference/`
(ui-parity #23's copies; SHA-256 pins in that folder's README). The
`mobile-*`, `mobile-tool-disclosure/*`, `appshots/*` and
`mobile-composer-polish/*` files are dated 2026-09-06…13, before the
PR-#570 rewrite — they are **tone-only** references (colour, type,
bubble, rail): the Swift code is authoritative for structure and metrics
(research 01 §0.2).
