# The web client is a React rebuild sharing design tokens, not GPUI compiled to wasm

The zui fork ships `gpui_web` (GPUI on wasm32/WebGPU), so a literal compile-the-desktop-app path exists — and it was rejected. The desktop UI crate is desktop-bound (embeds the engine in-process, opens PTYs, takes appshots, enumerates OS fonts), the RPC WebSocket client would need a wasm transport rewrite, wasm load times are poor (observed on similar ports), and iteration economics for an agent-built, one-person project favor the web corpus. Instead: a React client whose fidelity comes from construction — the theme registry's resolved builtin variants, layout constants, and the motion catalog are generated at build time into the web bundle (themes serialize 1:1; the motion catalog is duration + CSS cubic-bezier curves — the stack has no springs), and the same Geist/Geist Mono fonts are embedded. Accepted residual gaps: bit-exact ClearType text, shader-drawn dashed borders, device-pixel snapping at fractional DPI, one image-mask effect.

## Consequences

- Visual parity is a tracked checklist (tokens by construction, feel ~95% then iterative), not a guarantee by shared code.
- Revisiting the wasm path later means rewriting this client; recorded so nobody re-litigates it without new facts (e.g. wasm startup becoming negligible).
