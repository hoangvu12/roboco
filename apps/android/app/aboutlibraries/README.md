# AboutLibraries config overrides

`aboutLibraries { collect { configPath = file("aboutlibraries") } }` in
`../build.gradle.kts` points the plugin here (its documented layout:
`libraries/*.json`, `licenses/*.json`; the file name is the licence id).

- `licenses/44d1a25c593283261968a15c8cc213cd.json` — the hash id the plugin
  gives `androidx.camera:camera-core`'s second POM licence, "BSD License"
  for its bundled libyuv (`README.chromium`), which it cannot map offline
  (`offlineMode = true`). The plugin matches the override by its `name` and
  `hash` FIELDS (its build log: "Please provide manually with `name`: `BSD
  License` and `hash`: `44d1a25c…`"), not by the file name — round 31's file
  without `hash` was ignored. Mapped to `BSD-3-Clause`; `content` is the text of
  `apps/android/licenses/spdx/BSD-3-Clause.txt` (keep the two in sync), so
  the licences sheet never falls back to name + URL
  (`AndroidLicenceTextsTest`).

If a camera-core update changes that licence's name or URL, its hash id
changes too: the test then names the new id.
