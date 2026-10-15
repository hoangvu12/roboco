package sh.roboco.android.newsession

import uniffi.roboco_core.PreparedPath

/**
 * Debug-only fake of the New Project write seam (android-ui-parity #111)
 * — the gallery's, the previews' and 110's screen test's stand-in for
 * [CoreProjectWriteApi], like [FakeHostPaths] stands in for the Rust
 * host-path helpers. Every call is recorded (a readable one-line op, so
 * assertions and the gallery fixture page can share them); probes answer
 * `exists = false`, creates answer `exists = true`, and project creates
 * return a synthetic scoped id.
 *
 * Main code must never reference it (the debug source set is absent from
 * release builds; it is visible to `testDebugUnitTest`, which is why it
 * lives here).
 */
class FakeProjectWriteApi : ProjectWriteApi {

    /** One line per FFI call, in order. */
    val calls = mutableListOf<String>()

    override suspend fun createProject(deviceId: String, path: String, gitDetected: Boolean): String {
        calls += "createProject $deviceId $path git=$gitDetected"
        return "gallery-space"
    }

    override suspend fun prepareSpacePath(
        deviceId: String,
        path: String,
        createIfMissing: Boolean,
    ): PreparedPath {
        calls += "prepare $deviceId create=$createIfMissing $path"
        return PreparedPath(path = path, exists = createIfMissing, gitDetected = false)
    }
}
