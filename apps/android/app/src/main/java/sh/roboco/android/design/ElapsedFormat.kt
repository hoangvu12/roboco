package sh.roboco.android.design

/**
 * The elapsed-time label of the status pill's Working banner and the
 * transcript's Working tail row — the port of iOS `StatusPill.elapsed`
 * (`ios:Session/SessionViewController.swift:658-660`):
 *
 * ```swift
 * static func elapsed(_ s: Int) -> String {
 *     s < 60 ? "\(s)s" : s < 3600 ? "\(s / 60)m \(s % 60)s" : "\(s / 3600)h \(s / 60 % 60)m"
 * }
 * ```
 *
 * Shared by tickets 73 (the pill) and 123 (the transcript Working row);
 * whichever landed first created this file and the other reuses it
 * unchanged. Integer division truncates, exactly like Swift. **No days
 * branch** — upstream PR #621 added `"Nd Nh"` and iOS has nothing like it:
 * hours keep growing (`25h 0m`, see `ElapsedFormatTest`).
 */
fun formatElapsed(seconds: Int): String =
    if (seconds < 60) "${seconds}s"
    else if (seconds < 3600) "${seconds / 60}m ${seconds % 60}s"
    else "${seconds / 3600}h ${seconds / 60 % 60}m"
