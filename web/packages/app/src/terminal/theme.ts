import type { ITheme } from "@xterm/xterm";
import type { TerminalPalette } from "@roboco/theme";

/**
 * The xterm theme derived from the theme artifact's terminal roles: ANSI
 * slots 0–7 normal / 8–15 bright map onto xterm's named colors, background /
 * foreground / selection ride the palette roles. The cursor inverts the
 * palette (block cursor = fg glyph on bg cell), matching the desktop's
 * emulator paint.
 */
export function xtermThemeFromPalette(palette: TerminalPalette): ITheme {
  const ansi = palette.ansi;
  return {
    background: palette.background,
    foreground: palette.foreground,
    cursor: palette.foreground,
    cursorAccent: palette.background,
    selectionBackground: palette.selection,
    black: ansi[0],
    red: ansi[1],
    green: ansi[2],
    yellow: ansi[3],
    blue: ansi[4],
    magenta: ansi[5],
    cyan: ansi[6],
    white: ansi[7],
    brightBlack: ansi[8],
    brightRed: ansi[9],
    brightGreen: ansi[10],
    brightYellow: ansi[11],
    brightBlue: ansi[12],
    brightMagenta: ansi[13],
    brightCyan: ansi[14],
    brightWhite: ansi[15],
  };
}

/**
 * Read the palette the installed theme variant exposes on `<html>` as
 * `--rb-term-*` custom properties (see `variantCssVars` in @roboco/theme).
 * Reading computed style (instead of the artifact) means appearance changes
 * from any source — including ticket 16's preferences — are picked up by
 * re-calling this and reassigning `terminal.options.theme`.
 */
export function currentTerminalTheme(root: HTMLElement = document.documentElement): ITheme | undefined {
  const style = getComputedStyle(root);
  const read = (name: string): string => style.getPropertyValue(name).trim();
  const background = read("--rb-term-bg");
  const foreground = read("--rb-term-fg");
  if (background.length === 0 || foreground.length === 0) {
    return undefined;
  }
  const ansi: string[] = [];
  for (let i = 0; i < 16; i++) {
    ansi.push(read(`--rb-term-ansi-${i}`));
  }
  return xtermThemeFromPalette({
    background,
    foreground,
    selection: read("--rb-term-selection"),
    ansi,
  });
}
