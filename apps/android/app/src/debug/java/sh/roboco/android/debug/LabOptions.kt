package sh.roboco.android.debug

import java.util.Locale
import sh.roboco.android.design.Appearance

/**
 * The intent-extra keys of the Transcript Lab (android-ui-parity #135),
 * shared by [LabOptions.fromExtras] and the gallery launcher: `turns`
 * (`--ei turns 300`), the boolean flags (`--ez autostream true`, …) and
 * `appearance` (`--es appearance dark`). Names mirror the iOS launch
 * arguments (`-turns`, `-autostream`, …) minus their dash.
 */
internal const val LAB_EXTRA_TURNS = "turns"
internal const val LAB_EXTRA_AUTOSTREAM = "autostream"
internal const val LAB_EXTRA_TOP = "top"
internal const val LAB_EXTRA_AUTOSCROLL = "autoscroll"
internal const val LAB_EXTRA_METER = "meter"
internal const val LAB_EXTRA_BENCH = "bench"
internal const val LAB_EXTRA_APPEARANCE = "appearance"
internal const val LAB_EXTRA_IMAGES = "images"

/**
 * The Transcript Lab's parsed launch extras (android-ui-parity #135) —
 * the Android form of the iOS `-lab` launch arguments
 * (`upstream/main:apps/ios/Zeron/Debug/TranscriptLabViewController.swift:56-77`):
 *
 *  - `turns` — the fixture turn count, `--ei` (Int) or `--es` (String)
 *    form; unparsable reads the iOS default 6, negative coerces to 0;
 *  - `autostream` / `top` / `autoscroll` — the three scripts;
 *  - `meter` / `bench` — parsed here, owned by ticket 136 (the hitch
 *    meter and the scroll benchmark);
 *  - `appearance` — Roboco's addition for screenshots in both themes
 *    without touching the persisted preference (`system`/`light`/`dark`,
 *    case-insensitive, `null` = follow the app's setting).
 */
data class LabOptions(
    val turns: Int = 6,
    val autostream: Boolean = false,
    val top: Boolean = false,
    val autoscroll: Boolean = false,
    /** Ticket 136 (hitch meter). Parsed here so the extra is stable. */
    val meter: Boolean = false,
    /** Ticket 136 (scroll benchmark). Parsed here so the extra is stable. */
    val bench: Boolean = false,
    /** Ticket 137 (inline markdown images): the image fixture + LabImageReader. */
    val images: Boolean = false,
    /** `null` = the app's persisted appearance (ticket 96), else System. */
    val appearance: Appearance? = null,
) {
    companion object {

        /** The iOS default (`?? 6`, `TranscriptLabViewController.swift:57`). */
        private const val DEFAULT_TURNS = 6

        /**
         * Parses the extras map the way `am start` delivers them: Ints
         * for `--ei`, Booleans for `--ez`, Strings for `--es` (and any
         * of them may arrive as a String).
         */
        fun fromExtras(extras: Map<String, Any?>): LabOptions = LabOptions(
            turns = (((extras[LAB_EXTRA_TURNS] as? Int)
                ?: (extras[LAB_EXTRA_TURNS] as? String)?.trim()?.toIntOrNull())
                ?: DEFAULT_TURNS).coerceAtLeast(0),
            autostream = flag(extras, LAB_EXTRA_AUTOSTREAM),
            top = flag(extras, LAB_EXTRA_TOP),
            autoscroll = flag(extras, LAB_EXTRA_AUTOSCROLL),
            meter = flag(extras, LAB_EXTRA_METER),
            bench = flag(extras, LAB_EXTRA_BENCH),
            images = flag(extras, LAB_EXTRA_IMAGES),
            appearance = when ((extras[LAB_EXTRA_APPEARANCE] as? String)?.trim()?.lowercase(Locale.ROOT)) {
                "system" -> Appearance.System
                "light" -> Appearance.Light
                "dark" -> Appearance.Dark
                else -> null
            },
        )

        /** `--ez name true` or `--es name true`; absent or `false` reads false. */
        private fun flag(extras: Map<String, Any?>, name: String): Boolean =
            extras[name] == true || (extras[name] as? String)?.equals("true", ignoreCase = true) == true
    }
}
