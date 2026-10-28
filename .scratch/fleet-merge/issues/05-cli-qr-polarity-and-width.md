# 05 — CLI QR codes: dark on light, and a width hint

**What to build:** Every `--qr` output (`roboco engine pairing create --qr`, `tailcat invite --qr`, `room invite --qr`, and the headless startup QR):

- Print dark modules on a light background with explicit ANSI colours: black foreground on white around the half-block glyphs, plus the quiet zone. A dark terminal theme then can't invert the code. With no colour support (`NO_COLOR`, or not a TTY), fall back to today's rendering plus a note that the code may need a light background.
- Before printing, compare the code's width with the terminal width. If it's wider, print one line first: "Widen the terminal to N columns (or copy the link below)". The link is always printed under the code, as today.
- Invites use error-correction level L (same as 04).

**Blocked by:** none

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-merge/spec.md`

- [ ] Unit test: the rendered output, with the ANSI colours interpreted, decodes with `rqrr` for a 4-member invite and a Tailcat link
- [ ] The width hint shows when the terminal is narrower than the code (test with an injected width)
- [ ] `pairing_cli`/`tailcat_invite_cli`/`room_cli` tests still pass; no secret printed beyond what `--qr` already prints

## Comments
