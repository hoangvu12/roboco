# The iOS parity gate — goldens, manifest and sign-off (android-app#30)

`app/src/test/screenshots/` holds the Roborazzi goldens; this directory
holds what pins them:

- `manifest.json` — one entry per committed golden: its source (`iosRef`
  under `ios/`, or an `adaptation` note), the `CHECKLIST.md` items walked,
  and the human sign-off (`reviewedSha256`, `reviewer`, `date`). The three
  SSIM ratchets also carry an `ssimFloor`.
- `CHECKLIST.md` — the reviewer's walk.
- `ios/` — the iOS references (`ios/SOURCES.md` keeps the attribution).

The gate: G1 `verifyRoborazziDebug` (the pixels), G2 `ParityManifestTest`
(every golden has an entry, every entry is signed off against the exact
PNG), G3 `IosParityMetricsTest` (measured metrics), G4
`IosParitySimilarityTest` (the SSIM floors), G5 `:app:parityReport` (the
side-by-side board, the `android-parity` CI artifact).

## Recording the goldens (CI only)

Ubuntu is the source of truth — font rasterisation differs across hosts,
so goldens are never recorded locally. Dispatch the Android workflow with
`record_goldens`:

> **2026-10-26:** the `android-screenshots` CI job (and its `record_goldens`
> dispatch) was removed from `.github/workflows/android.yml` — it failed on
> every run with no committed goldens and slowed CI. The parity flow below is
> still valid; run the capture and verify steps locally until the job returns
> (see `docs/agents`-style decision in the CI trim PR).

```
gh workflow run android.yml -R hoangvu12/roboco --ref <branch> -f record_goldens=true
```

Only the `android-screenshots` job runs (the other Android jobs are skipped
on such a dispatch). It records the capturing classes, re-verifies
them in the same job (any diff = a non-deterministic capture: the run
fails and nothing is uploaded), writes the board, and uploads
`android-goldens` (the recorded `src/test/screenshots` tree) plus
`android-parity` (the board). Push and PR runs keep verifying, non-blocking
until the first signed-off set lands.

Bring the set into the tree:

```
gh run download <run-id> -R hoangvu12/roboco -n android-goldens -D apps/android/app/src/test/screenshots
```

Only `*.png` files belong there (no `*_actual` / `*_compare` images).

## The manifest: stubs and sign-off (`:app:parityManifest`)

G2 requires an entry for **every** PNG under `src/test/screenshots`, not
only the iOS-referenced ones. After bringing a recorded set in:

```
./gradlew :app:parityManifest
```

appends a stub for every golden without an entry (`adaptation`:
"regression golden (no iOS reference)", no checklist items, sign-off
null) in path order; existing entries are never reordered, rewritten or
removed, and entries whose PNG is missing are listed as a warning. A run
with nothing to add leaves the file byte-for-byte unchanged.

Walk the board (`android-parity` → `index.html`) against `CHECKLIST.md`,
then pin what you reviewed:

```
./gradlew :app:parityManifest --sign=pending --reviewer="Your Name" --date=2026-10-14
./gradlew :app:parityManifest --sign=sessions/sessions_root_scrolled_light.png,settings/settings_screen_top_light.png --reviewer="Your Name"
```

`--sign` takes comma-separated golden paths (as written in the manifest),
`pending` (every recorded entry not signed yet) or `all` (every recorded
entry — after a re-record, re-pins changed hashes; only after re-walking
them). It fills `reviewedSha256` from the file, `reviewer`, and `date`
(default: today). Naming an entry without a recorded PNG, or a path with
no entry, fails the task. `ssimFloor` stays a hand edit: the board's SSIM
score − 0.02 for the three ratchets.

Commit the PNGs and the manifest together. A later golden change makes
G2 fail on the hash until it is re-walked and re-signed — by design.
