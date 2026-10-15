# Imported Android icon sources

Files in this directory are **committed sources** for the
`roboco-android-assets` generator (`cargo run --locked -p
roboco-android-assets`); outputs land under `app/build/generated/` and never
in git (except the launcher mipmaps, which
`scripts/generate-app-icons.py` commits).

| Path | Origin | License |
|---|---|---|
| `tab-chat.svg`, `tab-settings.svg` | iOS tab glyphs, `apps/ios/Zeron/Assets.xcassets/TabIcons/` at upstream `zeronsh/zeron` sha `edb23db4b409df035808e38c44180d1c7a2d2d7e` | MIT (upstream) |
| `file-icons-dark/*-dark.svg` (198) | iOS dark-appearance variants, `apps/ios/Zeron/Assets.xcassets/FileIcons/` at the same sha | MIT (upstream); art from vscode-symbols |
| `roboco-bow.svg` | Drawn for Roboco (android-next #03): the ribbon-bow silhouette for the notification icon and the themed launcher icon | Roboco |

The light file/folder icons and the line icons are **not** imported: they
already live in `crates/ui/assets/{icons,file-icons}/` and the generator
reads them there. Dark-variant provenance and the vscode-symbols license
live in `crates/ui/assets/file-icons/LICENSE.symbols`, copied into the APK
at `assets/licenses/vscode-symbols-LICENSE.txt`.

## Solar Icons

`tray`, `tray-arrow-up`, `tray-arrow-up-fill`, `archivebox-fill`,
`folder-fill`, `folder-badge-plus`, `folder-badge-gear`, `clipboard`,
`check-circle`, `check-circle-fill`, `check-square-fill`,
`arrow-up-circle-fill`, `xmark-circle-fill`, `stop-circle`,
`exclamation-circle`, `speedometer`, `gallery`, `gallery-stack`, `camera`,
`link`, `layers`, `share`, `line-3-horizontal` are [Solar
Icons](https://www.figma.com/community/file/1166834396438034778) by 480
Design (CC BY 4.0,
https://creativecommons.org/licenses/by/4.0/), unmodified except for
removing `width`/`height`. `pin-fill`, `pin-slash`, `square`, `circle-half`,
`text-cursor`, `arrow-turn-down-right`, `arrow-turn-left-up` are drawn for
Roboco in the Solar Linear style (24 grid, stroke 1.5, round caps).
