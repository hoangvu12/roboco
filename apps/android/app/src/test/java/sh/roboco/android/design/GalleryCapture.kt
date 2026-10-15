package sh.roboco.android.design

import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.RuntimeEnvironment
import sh.roboco.android.design.glass.Glass

/**
 * The gallery Roborazzi helper (android-ui-parity #22): captures one section
 * frame in light and dark at the section's `screenshotHeightDp`, at `xhdpi`
 * (2× — small enough to commit for ~30 sections, still resolving 1-px
 * hairlines).
 *
 * Paths are relative to `roborazzi { outputDir }`
 * (`app/src/test/screenshots`, with
 * `roborazzi.record.filePathStrategy=relativePathFromRoborazziContextOutputDirectory`),
 * so the goldens land under `src/test/screenshots/design/gallery/`.
 *
 * The capture is the section frame only — no top bar, so the API-level text
 * never enters a golden. Reduce motion is forced on during capture: static
 * glyphs and no running loops keep the goldens byte-stable. Glass runs its
 * non-blur fallback for the same reason (ticket 07's rule): the goldens
 * never depend on how Robolectric reports hardware acceleration.
 */
object GalleryCapture {

    fun path(section: GallerySection, dark: Boolean): String =
        "design/gallery/gallery_${section.slug}_${if (dark) "dark" else "light"}.png"

    fun capture(section: GallerySection) {
        for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("w411dp-h${section.screenshotHeightDp}dp-xhdpi")
            // Static glyphs, no running loops: stable goldens.
            ReduceMotion.debugOverride = true
            Glass.forceFallback = true
            try {
                captureRoboImage(path(section, dark)) {
                    GalleryProviders(if (dark) Appearance.Dark else Appearance.Light, capture = true) {
                        GallerySectionFrame(section)
                    }
                }
            } finally {
                ReduceMotion.debugOverride = null
                Glass.forceFallback = false
            }
        }
    }
}
