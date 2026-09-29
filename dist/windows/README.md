# Windows icon

`roboco.ico` contains 16, 24, 32, 48, 64, 128, and 256 pixel PNG frames
converted from the existing `../macos/icon-1024.png` artwork with bicubic
resampling. It preserves the artwork's transparency.

`apps/roboco/build.rs` compiles `roboco.rc` into the Windows executable for
both debug and release builds. Resource ID 1 is required by GPUI's Windows
icon loader. No adjacent image file is needed at runtime; the installer
(`roboco.iss`) uses the same `roboco.ico` for its own window and the uninstall
entry.
