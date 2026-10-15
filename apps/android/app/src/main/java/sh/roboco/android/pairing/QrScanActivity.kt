package sh.roboco.android.pairing

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.R
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.ProvideFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.shell.applyRobocoEdgeToEdge

/**
 * The QR scanner (android-ui-parity #138): a full-screen, always-dark
 * camera stage that decodes a pairing link (or a
 * `roboco://pair#url=…` wrapper), fires the Confirm haptic and hands the
 * plain URL back to the Pair page's launcher, which fills the field and
 * submits at once (72's `acceptScanned`).
 *
 * A separate `exported = false` activity so CameraX, the permission dialog
 * and the trip to system Settings get a clean lifecycle (the camera stops
 * on `ON_STOP`, the grant is re-checked on every `ON_RESUME`); the URL
 * travels only in the result intent, never in a log, toast or
 * notification. Open/close are the 350/300 ms cover-vertical slides (see
 * `res/anim/qr_*.xml`), no animation at all under reduced motion. The
 * `configChanges` in the manifest keeps the camera session alive across
 * rotation, so the permission is asked once per session (`asked` in
 * `onSaveInstanceState`).
 *
 * Threading: all decode **and** the Rust `parsePairingUrl` run on the
 * single `qr-analysis` thread ([executor]); only state writes hop to
 * main. Accept exactly once: the [accepted] flag is flipped on the
 * analysis thread with `compareAndSet` before the main-thread hop, and
 * `isFinishing` guards the main side.
 */
class QrScanActivity : ComponentActivity() {

    private var phase by mutableStateOf(ScannerPhase.Requesting)
    private var showForeign by mutableStateOf(false)

    /** The 2 s reset of the foreign caption, cancelled on a new flash. */
    private var foreignJob: Job? = null

    /** 05's engine, captured from composition (the activity is its root). */
    private var feedback: Feedback? = null

    private val decoder = QrDecoder()
    private val debouncer = RejectDebouncer()

    /** Analysis-thread cache: (raw text → verdict); identical frames skip the parse. */
    private var lastScan: Pair<String, ScanVerdict>? = null

    private val accepted = AtomicBoolean(false)
    private var asked = false
    private var bound = false
    private var reduceMotion = false

    /** The Rust parse seam (run on [executor]); injectable for tests. */
    internal var isPairingUrl: (String) -> Boolean = { s ->
        runCatching { uniffi.roboco_core.parsePairingUrl(s) }.isSuccess
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "qr-analysis") }
    private val controller by lazy { LifecycleCameraController(this) }
    private lateinit var previewView: PreviewView

    /** CAMERA once per scanner session; the "don't ask again" auto-deny lands as Denied. */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            phase = ScannerPhase.Scanning
            bindCamera()
        } else {
            phase = ScannerPhase.Denied
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super, like MainActivity (35's helper): transparent bars
        // with light icons — the stage is always dark — and no contrast
        // scrims.
        applyRobocoEdgeToEdge(isDark = true)
        super.onCreate(savedInstanceState)
        reduceMotion = intent.getBooleanExtra(EXTRA_REDUCE_MOTION, false)
        asked = savedInstanceState?.getBoolean(KEY_ASKED) ?: false
        if (Build.VERSION.SDK_INT >= 34) {
            if (reduceMotion) {
                // Reduced motion: open and close with no animation (0, 0).
                overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
                overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                // Open: slide up 350 ms; the Pair page's activity holds
                // (alpha 1 → 1). Close: hold + slide down 300 ms.
                overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_OPEN,
                    R.anim.qr_scanner_enter,
                    R.anim.qr_hold,
                )
                overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_CLOSE,
                    R.anim.qr_hold,
                    R.anim.qr_scanner_exit,
                )
            }
        }
        previewView = PreviewView(this).apply {
            // Aspect-fill (scaled to cover, centre-cropped): a preview
            // with letterbox bars wastes the viewfinder (android-app#18).
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // PERFORMANCE = SurfaceView: Haze cannot sample it, so the ✕
            // stays outside any haze source and 07's GlassSurface picks
            // its fallback fill.
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        setContent {
            RobocoTheme(Appearance.Dark) {
                ProvideFeedback {
                    val current = LocalFeedback.current
                    SideEffect { this@QrScanActivity.feedback = current }
                    QrScannerContent(
                        state = QrScannerUiState(phase, showForeign),
                        onClose = ::close,
                        onOpenSettings = ::openSettings,
                    ) {
                        // The preview keeps running after an accept — only
                        // analysis stops, so the close slide shows live
                        // video, not a frozen frame.
                        AndroidView(
                            factory = { previewView },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        // The Settings return path: ON_RESUME re-checks the grant, but
        // never (re-)launches the request — a denied dialog resumes the
        // activity, and re-requesting would loop.
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_RESUME) recheck()
            }
        })
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            phase = ScannerPhase.Scanning
            bindCamera()
        } else if (!asked) {
            asked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ASKED, asked)
    }

    /** ON_RESUME: a return from Settings with the grant now on → Scanning. */
    private fun recheck() {
        if (phase != ScannerPhase.Denied && phase != ScannerPhase.Requesting) return
        // `Requesting` with the ask not yet made means the dialog is still
        // about to come up — nothing to re-check yet.
        if (phase == ScannerPhase.Requesting && !asked) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            phase = ScannerPhase.Scanning
            bindCamera()
        }
    }

    /**
     * Binds the camera once (idempotent through [bound]). Analysis only
     * (the preview is always on), KEEP_ONLY_LATEST, target 1280 × 720;
     * back camera, front if the back is missing, else Unavailable; a
     * CRITICAL CameraX error flips the phase to Unavailable too.
     */
    private fun bindCamera() {
        if (bound) return
        bound = true
        val controller = this.controller
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
        controller.initializationFuture.addListener(
            {
                if (isFinishing || isDestroyed) return@addListener
                try {
                    controller.initializationFuture.get()
                } catch (e: Exception) {
                    Log.w(TAG, "camera init failed: ${e::class.simpleName}")
                    phase = ScannerPhase.Unavailable
                    return@addListener
                }
                val back = runCatching {
                    controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                }.getOrDefault(false)
                val front = runCatching {
                    controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                }.getOrDefault(false)
                val selector = when {
                    back -> CameraSelector.DEFAULT_BACK_CAMERA
                    front -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> {
                        phase = ScannerPhase.Unavailable
                        return@addListener
                    }
                }
                controller.cameraSelector = selector
                controller.setImageAnalysisAnalyzer(executor) { image -> analyze(image) }
                controller.bindToLifecycle(this)
                previewView.controller = controller
                val info = controller.cameraInfo
                if (info == null) {
                    // cameraInfo is null until the camera is bound; if it
                    // still is, only the CRITICAL → Unavailable mapping is
                    // lost — the camera keeps working.
                    Log.w(TAG, "no camera info")
                } else {
                    info.cameraState.observe(this) { state ->
                        if (state.error?.type == CameraState.ErrorType.CRITICAL) {
                            phase = ScannerPhase.Unavailable
                        }
                    }
                }
            },
            mainExecutor,
        )
    }

    /**
     * The analyzer (the `qr-analysis` thread): decode → classify → hop to
     * main. The image is always closed — with KEEP_ONLY_LATEST a missed
     * close stalls the stream after one frame.
     */
    private fun analyze(image: ImageProxy) {
        try {
            if (accepted.get()) return
            val text = decoder.analyze(image) ?: return
            val cached = lastScan
            val verdict = if (cached != null && cached.first == text) {
                cached.second
            } else {
                classifyScan(text, isPairingUrl).also { lastScan = text to it }
            }
            when (verdict) {
                is ScanVerdict.Pairing ->
                    if (accepted.compareAndSet(false, true)) {
                        mainExecutor.execute { accept(verdict.url) }
                    }
                ScanVerdict.Foreign ->
                    if (debouncer.shouldShow(text, SystemClock.uptimeMillis())) {
                        mainExecutor.execute { showForeign() }
                    }
                ScanVerdict.Ignore -> Unit
            }
        } finally {
            image.close()
        }
    }

    /** Main: stop analysis, haptic, result, close. Never logs the URL. */
    private fun accept(url: String) {
        if (isFinishing) return
        controller.clearImageAnalysisAnalyzer()
        feedback?.haptic(Haptic.Confirm)
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_URL, url))
        close()
    }

    /** Main: the 2 s foreign flash (only while still scanning). */
    private fun showForeign() {
        if (accepted.get() || phase != ScannerPhase.Scanning) return
        showForeign = true
        foreignJob?.cancel()
        foreignJob = lifecycleScope.launch {
            delay(FOREIGN_CAPTION_MS)
            showForeign = false
        }
    }

    private fun openSettings() {
        openAppDetailsSettings(this)
    }

    /** ✕ / accept / system back: closes with the slide-down (no result on ✕). */
    private fun close() {
        finish()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(
                if (reduceMotion) 0 else R.anim.qr_hold,
                if (reduceMotion) 0 else R.anim.qr_scanner_exit,
            )
        }
    }

    override fun onDestroy() {
        if (bound) controller.clearImageAnalysisAnalyzer()
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        /** The decoded pairing URL, `exported = false` activity only. */
        const val EXTRA_URL = "sh.roboco.android.pairing.URL"

        /** The launcher's reduced-motion flag (04's [sh.roboco.android.design.LocalReduceMotion]). */
        const val EXTRA_REDUCE_MOTION = "sh.roboco.android.pairing.REDUCE_MOTION"

        private const val KEY_ASKED = "asked"
        private const val TAG = "QrScanner"
    }
}

/**
 * The Denied state's capsule action: the app's own system settings page
 * (`ACTION_APPLICATION_DETAILS_SETTINGS`). An `ActivityNotFoundException`
 * is logged and swallowed — the scanner stays open, and the activity's
 * ON_RESUME re-check picks the grant up when the user comes back by any
 * other route. Shared with the screen test so the intent itself is
 * covered.
 */
internal fun openAppDetailsSettings(context: Context): Boolean {
    return try {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("QrScanner", "app details settings activity missing")
        false
    }
}
