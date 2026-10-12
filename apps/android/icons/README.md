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
| `roboco-glyph.svg` | Hand-drawn for Roboco (monochrome mark for the Pair page and the status-bar icon) | Roboco |

The light file/folder icons and the line icons are **not** imported: they
already live in `crates/ui/assets/{icons,file-icons}/` and the generator
reads them there. Dark-variant provenance and the vscode-symbols license
live in `crates/ui/assets/file-icons/LICENSE.symbols`, copied into the APK
at `assets/licenses/vscode-symbols-LICENSE.txt`.
