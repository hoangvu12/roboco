package sh.roboco.android.composer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem

/** One row of the attach menu (android-ui-parity #87). */
enum class AttachSource { Photos, Camera, Files, Paste }

/**
 * The attach menu's pure builder (android-ui-parity #87), the port of
 * `AttachmentPicker.menu(host:limit:completion:)`
 * (`ios:Composer/AttachmentPicker.swift:17-38`): one group, no title, no
 * separators, rows in the fixed order **Photo Library, [Take Photo],
 * Choose File, [Paste Image]**.
 *
 * - [hasCamera] is `UIImagePickerController.isSourceTypeAvailable(.camera)`,
 *   evaluated by the caller **at open time** — this function is called
 *   inside the `+` tap handler, never during composition, so the row set
 *   is rebuilt on every open (iOS `UIDeferredMenuElement.uncached` + a
 *   synchronous `done`, `ComposerBar.swift:156-159`);
 * - [clipHasImage] is the pasteboard **type** check: it reads the clip
 *   *description* only, never the clip data, so no Android 12+ "pasted
 *   from your clipboard" toast is shown at menu build (iOS
 *   `UIPasteboard.hasImages` reads no data either). The data itself is
 *   read only inside the Paste action;
 * - [onPick] receives the row's source; the pickers (photo picker,
 *   camera, document picker, paste) are [AttachPicker]'s job.
 *
 * No haptics: iOS fires none on `+` or on menu picks.
 */
fun attachMenu(hasCamera: Boolean, clipHasImage: Boolean, onPick: (AttachSource) -> Unit): Menu = Menu(
    children = buildList {
        add(
            MenuItem(
                title = "Photo Library",
                icon = MenuIcon.Symbol(RobocoIcons.PhotoOnRectangle),
                testTag = "attach-photos",
            ) { onPick(AttachSource.Photos) },
        )
        if (hasCamera) {
            add(
                MenuItem(
                    title = "Take Photo",
                    icon = MenuIcon.Symbol(RobocoIcons.Camera),
                    testTag = "attach-camera",
                ) { onPick(AttachSource.Camera) },
            )
        }
        add(
            MenuItem(
                title = "Choose File",
                icon = MenuIcon.Symbol(RobocoIcons.Folder),
                testTag = "attach-files",
            ) { onPick(AttachSource.Files) },
        )
        if (clipHasImage) {
            add(
                MenuItem(
                    title = "Paste Image",
                    icon = MenuIcon.Symbol(RobocoIcons.DocOnClipboard),
                    testTag = "attach-paste",
                ) { onPick(AttachSource.Paste) },
            )
        }
    },
)

/**
 * The photo-picker entry (android-ui-parity #87): the system photo picker
 * (androidx `PickVisualMedia`), images only, with the remaining free
 * slots as the **launch input** — `PickMultipleVisualMedia(maxItems)`
 * when the limit is ≥ 2 (the contract throws for `maxItems ≤ 1`),
 * `PickVisualMedia` below that, matching iOS's
 * `selectionLimit = max(1, limit)`.
 *
 * The limit travels as the input, not the contract instance: registering
 * `PickMultipleVisualMedia(limit)` directly would re-register the
 * launcher on every limit change, because `rememberLauncherForActivityResult`
 * keys on the contract instance. One launcher then serves every limit.
 * The multiple-pick `parseResult` reads both `data` and `clipData`, so it
 * parses a single pick too. The document-UI fallback on devices without
 * the photo picker does not enforce the limit — the staging cap does.
 */
internal class PickImages : ActivityResultContract<Int, List<Uri>>() {

    override fun createIntent(context: Context, input: Int): Intent {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        return if (input >= 2) {
            ActivityResultContracts.PickMultipleVisualMedia(input).createIntent(context, request)
        } else {
            ActivityResultContracts.PickVisualMedia().createIntent(context, request)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
        ActivityResultContracts.PickMultipleVisualMedia().parseResult(resultCode, intent)
}

/**
 * The attach menu's pickers and delivery (android-ui-parity #87): the
 * port of `AttachmentPicker`'s four presenters
 * (`ios:Composer/AttachmentPicker.swift:40-108`) onto Android activity
 * result contracts, plus the staging → `addImages` delivery that iOS
 * wraps in `completion`.
 *
 * Created and remembered by [rememberAttachPicker]; the launcher fields
 * are wired by that composable in a `SideEffect` (the launchers must be
 * registered before the instance can exist, but their callbacks need the
 * instance — the same wiring [rememberComposerMorphState] uses). By the
 * time [menu] or [run] can fire (a user tap), composition has applied.
 *
 * Every source calls the completion **once** with the whole batch
 * (one `addImages` call), failures are skipped silently, cancel does
 * nothing, and a launch that finds no activity is logged and dropped —
 * **no error UI anywhere** (`compactMap` / `guard … else { return nil }`
 * on iOS).
 */
@Stable
class AttachPicker internal constructor(
    private val context: Context,
    private val clipboard: ClipboardManager?,
    /** Roboco: the manifest's `CAMERA` permission decides the runtime grant step (138). */
    private val cameraDeclared: Boolean,
    private val scope: CoroutineScope,
    private val resolver: ContentResolver,
    /** The pending camera file's path; survives activity death (Roboco). */
    internal val pendingCamera: MutableState<String?>,
    private val limit: State<() -> Int>,
    private val deliver: State<(List<StagedImage>) -> Unit>,
    private val stager: State<(suspend (List<PickedImage>, Int) -> List<StagedImage>)?>,
) {

    /** The four launchers, wired by [rememberAttachPicker]'s SideEffect. */
    internal var pickImages: ActivityResultLauncher<Int>? = null
    internal var openDocs: ActivityResultLauncher<Array<String>>? = null
    internal var takePicture: ActivityResultLauncher<Uri>? = null
    internal var requestCamera: ActivityResultLauncher<String>? = null

    /** The default stager: the real pipeline over this context's resolver. */
    private val defaultStager: suspend (List<PickedImage>, Int) -> List<StagedImage> =
        { picks, free -> ImageStaging.stageAll(resolver, picks, free) }

    /**
     * Builds the menu **now**: the camera feature and the clip description
     * are read at this moment (inside the `+` tap handler), never during
     * composition. Reads no clip data.
     */
    fun menu(): Menu = attachMenu(
        hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
        clipHasImage = clipboard?.let(::clipHasImage) == true,
        onPick = ::run,
    )

    /**
     * One row's action (`AttachmentPicker.swift:40-108`). `limit` is read
     * when the action runs, floored at 0 (iOS `8 − images.count` at open;
     * both read the same `addImages` cap). Each `launch` is guarded
     * against [android.content.ActivityNotFoundException] — a missing
     * picker is logged and dropped, nothing is shown.
     */
    fun run(source: AttachSource) {
        when (source) {
            AttachSource.Photos -> {
                val free = max(0, limit.value())
                try {
                    val launcher = pickImages
                    if (launcher == null) {
                        Log.w(TAG, "photo launcher not wired")
                    } else {
                        launcher.launch(free)
                    }
                } catch (failure: ActivityNotFoundException) {
                    Log.w(TAG, "no photo picker on this device", failure)
                }
            }
            AttachSource.Camera -> {
                // Android needs the runtime grant when the merged manifest
                // declares CAMERA (ticket 138); a denial (or "don't ask
                // again") makes the row do nothing — iOS's system camera UI
                // handles its own permission.
                if (cameraDeclared &&
                    context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        requestCamera?.launch(Manifest.permission.CAMERA)
                    } catch (failure: ActivityNotFoundException) {
                        Log.w(TAG, "no permission dialog for CAMERA", failure)
                    }
                } else {
                    launchCamera()
                }
            }
            AttachSource.Files -> {
                try {
                    val launcher = openDocs
                    if (launcher == null) {
                        Log.w(TAG, "document launcher not wired")
                    } else {
                        launcher.launch(arrayOf("image/*"))
                    }
                } catch (failure: ActivityNotFoundException) {
                    Log.w(TAG, "no document picker on this device", failure)
                }
            }
            AttachSource.Paste -> {
                // The one place the clip data is read: Android 12+ shows the
                // system paste toast here, as iOS's paste banner does. Only
                // items with a URI are taken, in item order; plain text and
                // intents are skipped.
                val clip: ClipData = clipboard?.primaryClip ?: return
                handle(producer = {
                    (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.map { PickedImage(it) }
                })
            }
        }
    }

    /**
     * The delivery pipeline: produce the picks (a picker result — the
     * document picker's `DISPLAY_NAME` query runs on IO inside the
     * producer, never on main), stage them with the stager (which does
     * its own IO switch), then deliver the whole batch **once** on main.
     *
     * An empty producer result delivers nothing (a cancelled picker); the
     * `deliver` lambda is the UI's gate — it drops the batch if a queued
     * edit began meanwhile (`attachEnabled == false`, ticket 83's rule).
     * If the user leaves the screen while staging, the scope is cancelled
     * and the batch is lost (iOS drops it too, `[weak self]`).
     */
    internal fun handle(producer: suspend () -> List<PickedImage>, cleanup: () -> Unit = {}) {
        scope.launch {
            try {
                val picks = producer()
                if (picks.isEmpty()) return@launch
                val free = max(0, limit.value())
                val stage = stager.value ?: defaultStager
                val staged = stage(picks, free)
                if (staged.isNotEmpty()) deliver.value(staged)
            } finally {
                cleanup()
            }
        }
    }

    /**
     * The camera presenter (`:51-57`): a fresh cache file under
     * `cacheDir/camera/`, its `FileProvider` URI (the manifest's
     * `${applicationId}.fileprovider`), the path kept in [pendingCamera]
     * so the result survives activity recreation while the camera app is
     * in front, then `TakePicture`. Cancel (`false`) leaves the file
     * deleted and nothing staged; a `true` with an empty file (some
     * camera apps) fails to decode and is silently dropped.
     */
    internal fun launchCamera() {
        val dir = File(context.cacheDir, CAMERA_DIR)
        dir.mkdirs()
        val file = File(dir, "capture-${UUID.randomUUID()}.jpg")
        pendingCamera.value = file.path
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val launcher = takePicture
            if (launcher == null) {
                Log.w(TAG, "camera launcher not wired")
            } else {
                launcher.launch(uri)
            }
        } catch (failure: ActivityNotFoundException) {
            Log.w(TAG, "no camera app for ACTION_IMAGE_CAPTURE", failure)
            file.delete()
            pendingCamera.value = null
        } catch (failure: SecurityException) {
            // ACTION_IMAGE_CAPTURE throws when the manifest declares CAMERA
            // without the runtime grant (ticket 138 will declare it).
            Log.w(TAG, "camera permission missing for ACTION_IMAGE_CAPTURE", failure)
            file.delete()
            pendingCamera.value = null
        }
    }

    /** Photo Library result: the URIs in the returned order, no names. */
    internal fun onPhotos(uris: List<Uri>) {
        handle(producer = { uris.map { PickedImage(it) } })
    }

    /**
     * Choose File result (`:99-106`): the URIs in order, each named from
     * its `DISPLAY_NAME` (without the extension) — the query runs on IO,
     * inside the producer. No persistable permissions are taken (iOS
     * `asCopy: true`; the batch is staged at once, never re-opened).
     */
    internal fun onFiles(uris: List<Uri>) {
        handle(producer = {
            withContext(Dispatchers.IO) {
                uris.map { PickedImage(it, ImageStaging.baseName(queryDisplayName(resolver, it))) }
            }
        })
    }

    /** Camera result: `true` stages the file, `false`/cancel just deletes it. */
    internal fun onCamera(took: Boolean) {
        val path = pendingCamera.value ?: return
        val file = File(path)
        pendingCamera.value = null
        if (!took) {
            file.delete()
            return
        }
        handle(
            producer = { listOf(PickedImage(Uri.fromFile(file))) },
            cleanup = { file.delete() },
        )
    }

    /** The CAMERA runtime grant's result: continue only on grant. */
    internal fun onPermission(granted: Boolean) {
        if (granted) launchCamera()
    }

    private companion object {
        const val TAG = "AttachMenu"
        const val CAMERA_DIR = "camera"
    }
}

/**
 * [AttachPicker] in composition (android-ui-parity #87): the context, the
 * composition's own scope, the clipboard, the camera-permission probe and
 * the saveable camera path are remembered here; [limit] / [deliver] /
 * [stager] go through `rememberUpdatedState` so a caller that recreates
 * its lambdas per recomposition is still current at action time.
 *
 * The four launchers are registered in composition order and handed to
 * the picker in a `SideEffect` (they must exist before the picker can
 * launch, but their callbacks dispatch into the picker — the wiring
 * `rememberComposerMorphState` uses for the focus manager). Results
 * arrive on main; [AttachPicker.handle] moves the work off main itself.
 *
 * [pendingCamera] is `rememberSaveable`, so the pending camera file
 * survives activity recreation while the camera app is in front (the
 * photo picker's URI grants last for the process — those are never
 * stored, only staged at once).
 *
 * @param limit `{ ComposerController.MaxImages - controller.images.size }`
 *   — read when an action **runs**, floored at 0
 * @param deliver the guarded `addImages`: `{ if (controller.attachEnabled)
 *   controller.addImages(it) }` — the late gate for a queued edit (83)
 * @param stager `null` = the real [ImageStaging.stageAll] over this
 *   context's resolver; tests inject a fake
 */
@Composable
fun rememberAttachPicker(
    limit: () -> Int,
    deliver: (List<StagedImage>) -> Unit,
    stager: (suspend (List<PickedImage>, Int) -> List<StagedImage>)? = null,
): AttachPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolver = remember(context) { context.contentResolver }
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val cameraDeclared = remember(context) { cameraPermissionDeclared(context) }
    val pendingCamera = rememberSaveable { mutableStateOf<String?>(null) }
    val limitState = rememberUpdatedState(limit)
    val deliverState = rememberUpdatedState(deliver)
    val stagerState = rememberUpdatedState(stager)

    val picker = remember(context, scope, pendingCamera, limitState, deliverState, stagerState) {
        AttachPicker(
            context = context,
            clipboard = clipboard,
            cameraDeclared = cameraDeclared,
            scope = scope,
            resolver = resolver,
            pendingCamera = pendingCamera,
            limit = limitState,
            deliver = deliverState,
            stager = stagerState,
        )
    }

    val pickImages = rememberLauncherForActivityResult(PickImages()) { uris -> picker.onPhotos(uris) }
    val openDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        picker.onFiles(uris)
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { took ->
        picker.onCamera(took)
    }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        picker.onPermission(granted)
    }
    SideEffect {
        picker.pickImages = pickImages
        picker.openDocs = openDocs
        picker.takePicture = takePicture
        picker.requestCamera = requestCamera
    }
    return picker
}

/**
 * iOS `UIPasteboard.hasImages` (`:33`): the pasteboard **type** check —
 * reads the clip description only, never the clip data, so menu builds
 * show no Android 12+ paste toast. The data itself is read only inside
 * the Paste action.
 */
fun clipHasImage(clipboard: ClipboardManager): Boolean =
    clipboard.primaryClipDescription?.hasMimeType("image/*") == true

/**
 * True when the **merged** manifest requests `android.permission.CAMERA`.
 * Roboco-only: `ACTION_IMAGE_CAPTURE` throws `SecurityException` without
 * the runtime grant when the permission is declared (ticket 138's QR
 * scanner will declare it); when it is not declared, no grant is needed.
 * iOS's system camera UI handles its own permission.
 */
fun cameraPermissionDeclared(context: Context): Boolean {
    val info = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        return false
    }
    return info.requestedPermissions?.contains(Manifest.permission.CAMERA) == true
}

/**
 * The Choose File name source: the row's `OpenableColumns.DISPLAY_NAME`,
 * queried off main. `null` when the provider answers nothing — the pick
 * is then staged with the default `image-<8 hex>` name.
 */
internal fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else {
            null
        }
    }
