package sh.roboco.android.transcript

import kotlin.math.floor

/**
 * The virtualization constants of the transcript host (ticket 65).
 * Overscan and the band step are pure numbers: iOS realizes rows this far
 * beyond the viewport (`ios:Transcript/TranscriptListView.swift:26`,
 * `overscan = 700`), and Compose quantizes the query to 256 dp steps
 * (#609's `BAND`) so scrolling never recomposes per pixel.
 */
object TranscriptHostDefaults {

    /** Rows realize this far beyond the viewport (dp). */
    const val OverscanDp = 700f

    /** The realized band moves in these steps (dp). */
    const val BandStepDp = 256f
}

/**
 * The band's start for a scroll offset: the 256 dp step containing
 * `offset − 700` (`floor((offset − 700) / 256) · 256`). Always ≤ `offset −
 * 700` and > `offset − 956`, so [bandEnd] always covers iOS's
 * `[offset − 700, offset + viewport + 700)`.
 */
fun bandStart(offsetDp: Float): Float =
    floor((offsetDp - TranscriptHostDefaults.OverscanDp) / TranscriptHostDefaults.BandStepDp) *
        TranscriptHostDefaults.BandStepDp

/**
 * The band's end: the start plus the viewport and both overscans plus one
 * step — `viewport + 2·700 + 256` dp wide in total, a strict superset of
 * the iOS band.
 */
fun bandEnd(start: Float, viewportDp: Float): Float =
    start + viewportDp + 2f * TranscriptHostDefaults.OverscanDp + TranscriptHostDefaults.BandStepDp
