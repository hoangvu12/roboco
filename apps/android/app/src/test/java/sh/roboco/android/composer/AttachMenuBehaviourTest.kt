package sh.roboco.android.composer

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.uiStyle

/**
 * The attach menu's behaviour (android-ui-parity #87) through the real
 * [ComposerAttachMenuButton] inside ticket 15's [GlassMenuHost] —
 * Robolectric + compose-ui-test at **mdpi** (`w390dp-h340dp-mdpi`, 1 dp =
 * 1 px, no insets): the PR #714 rules (open without focus, morph or
 * movement, from the resting capsule and from the focused card), the
 * rebuild-on-open (clipboard description + camera feature), the four
 * pickers through a fake [ActivityResultRegistry]
 * (`LocalActivityResultRegistryOwner`), the delivery pipeline through an
 * injected fake stager, the late-batch gate, and the camera's
 * `FileProvider` file.
 *
 * The harness is 55's [ComposerSurface] with a real `BasicTextField`
 * (57's slot: `composerInput`), 56's real morph (`composerMorphHost`,
 * `composerControl`) and this ticket's `+`; the controller is ticket 54's
 * real [ComposerController]. `Glass.forceFallback` is on for the class
 * (the golden convention; nothing here depends on blur).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h340dp-mdpi")
class AttachMenuBehaviourTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val registry = RecordingRegistry()
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = registry
    }

    private val stagerCalls = mutableListOf<Pair<List<PickedImage>, Int>>()
    private var stagerResult: List<StagedImage> = emptyList()
    private var stagerGate: CompletableDeferred<Unit>? = null

    /** The injected fake stager: records the call, waits for a gate, replays a result. */
    private val fakeStager: suspend (List<PickedImage>, Int) -> List<StagedImage> =
        { picks, limit ->
            stagerCalls += picks to limit
            stagerGate?.await()
            stagerResult
        }

    private lateinit var controller: ComposerController
    private lateinit var morph: ComposerMorphState

    private val clipboard: ClipboardManager
        get() = compose.activity.getSystemService(ClipboardManager::class.java)

    @Before
    fun setUp() {
        Glass.forceFallback = true
        // Deterministic camera row: the default Robolectric feature set is
        // whatever the shadow says; pin it (the rebuild test flips it).
        shadowOf(compose.activity.packageManager)
            .setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        // #138 declares CAMERA in the manifest, so the attach menu asks for it
        // before TakePicture; Robolectric denies runtime permissions by default.
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
        clipboard.clearPrimaryClip()
        DisplayNameProvider.displayName = null
        resetFileProvider()
    }

    /**
     * androidx `FileProvider` caches its parsed roots per authority in a
     * static map (`sCache`) for the life of the class loader. Robolectric
     * reuses the sandbox loader across tests but hands every test a fresh
     * `robolectric-<test>` data dir, so the second test to take a camera
     * shot resolved its file against the FIRST test's cacheDir ("Failed to
     * find configured root"). Clearing it makes each test parse
     * `file_paths.xml` against its own cacheDir.
     *
     * Windows JVM only: `SimplePathStrategy.belongsToRoot` matches canonical
     * paths with `startsWith(root + '/')` — Android's separator, hard-coded
     * — so on a `\` file system no file ever resolves. There the cache is
     * seeded with a wrapper over the REAL parsed strategy (the roots
     * `file_paths.xml` declares, against this test's cacheDir) that does
     * the containment check with java.nio paths and builds the URI as
     * `SimplePathStrategy` does (`content://<authority>/<name>/<relative>`).
     */
    private fun resetFileProvider() {
        @Suppress("UNCHECKED_CAST")
        val cache = FileProvider::class.java.getDeclaredField("sCache")
            .apply { isAccessible = true }
            .get(null) as java.util.HashMap<String, Any>
        synchronized(cache) { cache.clear() }
        if (File.separatorChar == '/') return

        val authority = "${context.packageName}.fileprovider"
        val real = FileProvider::class.java
            .getDeclaredMethod("getPathStrategy", Context::class.java, String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(null, context, authority, 0)!!
        @Suppress("UNCHECKED_CAST")
        val roots = real.javaClass.getDeclaredField("mRoots")
            .apply { isAccessible = true }
            .get(real) as Map<String, File>
        val strategyType = Class.forName(
            "androidx.core.content.FileProvider\$PathStrategy",
            false,
            FileProvider::class.java.classLoader,
        )
        val wrapper = Proxy.newProxyInstance(strategyType.classLoader, arrayOf(strategyType)) { _, method, args ->
            if (method.name == "getUriForFile") {
                val file = (args!![0] as File).canonicalFile.toPath()
                val root = roots.entries.firstOrNull { file.startsWith(it.value.toPath()) }
                    ?: throw IllegalArgumentException("Failed to find configured root that contains $file")
                val relative = root.value.toPath().relativize(file).joinToString("/")
                Uri.Builder()
                    .scheme("content")
                    .encodedAuthority(authority)
                    .encodedPath(Uri.encode(root.key) + '/' + Uri.encode(relative, "/"))
                    .build()
            } else {
                method.isAccessible = true
                try {
                    method.invoke(real, *(args ?: emptyArray<Any?>()))
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            }
        }
        synchronized(cache) { cache[authority] = wrapper }
    }

    @After
    fun tearDown() {
        Glass.forceFallback = false
        clipboard.clearPrimaryClip()
        DisplayNameProvider.displayName = null
    }

    // -------------------------------------------------------------- harness

    /**
     * The ticket's fake registry: every `launch` is recorded and answered
     * with [nextResult] (default: cancelled — empty lists for the pickers,
     * `false` for camera and permission, so no callback crashes).
     */
    private class RecordingRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Pair<ActivityResultContract<*, *>, Any?>>()
        var nextResult: (ActivityResultContract<*, *>, Any?) -> Any? = { contract, _ ->
            when (contract) {
                is ActivityResultContracts.TakePicture -> false
                is ActivityResultContracts.RequestPermission -> false
                else -> emptyList<Uri>()
            }
        }

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches += contract to input
            dispatchResult(requestCode, nextResult(contract, input))
        }
    }

    @Composable
    private fun Fixture(controller: ComposerController, attach: (ComposerMorphState) -> Unit) {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost {
                    Box(Modifier.fillMaxSize()) {
                        val m = rememberComposerMorphState(
                            alwaysCard = false,
                            hasImages = { controller.images.isNotEmpty() },
                        )
                        attach(m)
                        ComposerSurface(
                            progress = { m.progress },
                            chipsInteractive = m.isCard,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                                .width(366.dp)
                                .composerMorphHost(m),
                            text = {
                                val palette = RobocoTheme.palette
                                val typeScale = LocalTypeScale.current
                                BasicTextField(
                                    value = controller.value,
                                    onValueChange = { controller.value = it },
                                    textStyle = uiStyle(Face.Sans, typeScale.fixed(16.5f), palette.text),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("composer-input")
                                        .composerInput(m),
                                )
                            },
                            placeholder = {
                                BasicText(
                                    controller.placeholder,
                                    style = uiStyle(
                                        Face.Sans,
                                        LocalTypeScale.current.fixed(16.5f),
                                        RobocoTheme.palette.tertiary,
                                    ),
                                    maxLines = 1,
                                )
                            },
                            // The real button with the fake stager injected
                            // through the internal seam.
                            attach = { ComposerAttachMenuButton(controller, m, stager = fakeStager) },
                            action = { AttachAction(m) },
                        )
                    }
                }
            }
        }
    }

    /** 58's action stand-in: a 34 dp accent circle carrying the marker. */
    @Composable
    private fun AttachAction(morph: ComposerMorphState) {
        val palette = RobocoTheme.palette
        Box(
            Modifier
                .size(34.dp)
                .clip(CapsuleShape)
                .background(palette.accent)
                .composerControl(morph),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                RobocoIcons.ArrowUp,
                tint = Color.White,
                sizePt = 15f,
                weight = SymbolWeight.Bold,
            )
        }
    }

    private fun mount(initialImages: Int = 0, initialText: String = "") {
        controller = ComposerController(CoroutineScope(UnconfinedTestDispatcher()), NoFeedback, initialText)
        compose.setContent { Fixture(controller) { morph = it } }
        compose.waitForIdle()
        if (initialImages > 0) {
            compose.runOnIdle { controller.images = (0 until initialImages).map { stubImage("stub-$it") } }
        }
        compose.waitForIdle()
    }

    private fun stubImage(id: String): StagedImage =
        StagedImage(id, "$id.jpg", ByteArray(1), Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap())

    private fun tapAttach() {
        compose.onNodeWithTag("composer-attach").performClick()
        compose.waitForIdle()
    }

    private fun openMenu() = tapAttach()

    /** Taps a menu row; the pick itself closes the menu. */
    private fun pickFromMenu(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(250) // past the 150 ms close fade
        compose.waitForIdle()
    }

    private fun closeMenu() {
        compose.onNodeWithTag("glass-menu-scrim").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(250)
        compose.waitForIdle()
    }

    private fun inputBounds(): DpRect = compose.onNodeWithTag("composer-input").getBoundsInRoot()

    private fun attachBounds(): DpRect = compose.onNodeWithTag("composer-attach").getBoundsInRoot()

    private fun inputText(): String =
        compose.onNodeWithTag("composer-input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    // ------------------------------------------------- the #714 rules (open)

    @Test
    fun openingTheMenuShowsTheRowsWithoutFocusingMorphingOrMovingTheComposer() {
        mount()
        val inputBefore = inputBounds()
        val attachBefore = attachBounds()
        assertFalse(morph.isFocused)
        assertFalse(morph.isCard)

        openMenu()
        compose.onNodeWithTag("attach-photos").assertExists()
        compose.onNodeWithTag("attach-camera").assertExists()
        compose.onNodeWithTag("attach-files").assertExists()
        compose.onNodeWithTag("attach-paste").assertDoesNotExist() // empty clipboard
        assertFalse("the tap did not focus the field", morph.isFocused)
        assertFalse("the capsule did not morph", morph.isCard)
        assertEquals("the text field did not move", inputBefore, inputBounds())
        assertEquals("the + button did not move", attachBefore, attachBounds())

        closeMenu()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        assertFalse("closing does not focus either", morph.isFocused)
        assertFalse("closing does not morph", morph.isCard)
        assertEquals(inputBefore, inputBounds())
        assertEquals(attachBefore, attachBounds())
    }

    @Test
    fun thePr714LoopKeepsTheDraftTextAndBoundsAcrossReopens() {
        mount()
        compose.runOnIdle { controller.text = "Draft" }
        compose.waitForIdle()
        assertFalse("an unfocused draft rests as the capsule", morph.isFocused)
        assertFalse(morph.isCard)
        val inputBefore = inputBounds()
        val attachBefore = attachBounds()

        repeat(2) {
            openMenu()
            compose.onNodeWithTag("attach-photos").assertExists()
            compose.onNodeWithTag("attach-files").assertExists()
            assertFalse("still unfocused after the open", morph.isFocused)
            assertEquals("the draft survives", "Draft", inputText())
            assertEquals("the input frame is unchanged", inputBefore, inputBounds())
            assertEquals("the + frame is unchanged", attachBefore, attachBounds())
            closeMenu()
            // `+` is still hittable, the draft and frames are unchanged.
            compose.onNodeWithTag("composer-attach").assertExists()
            assertEquals("Draft", inputText())
            assertEquals(inputBefore, inputBounds())
        }
    }

    @Test
    fun theMenuOpensFromTheFocusedCardAndKeepsFocusAndTheCard() {
        mount()
        compose.onNodeWithTag("composer-input").performClick()
        compose.onNodeWithTag("composer-input").performTextInput("Draft")
        compose.waitForIdle()
        assertTrue("the field is focused", morph.isFocused)
        assertTrue("the composer is the card", morph.isCard)
        val inputBefore = inputBounds()

        openMenu()
        compose.onNodeWithTag("attach-photos").assertExists()
        assertTrue("the field keeps focus while the menu is open", morph.isFocused)
        assertTrue("the card stays", morph.isCard)
        assertEquals("the card's field frame is unchanged", inputBefore, inputBounds())

        closeMenu()
        assertTrue("the dismissing tap does not blur the field", morph.isFocused)
        assertTrue(morph.isCard)
        assertEquals("Draft", inputText())
    }

    @Test
    fun aDisabledAttachButtonOpensNothing() {
        mount()
        compose.runOnIdle { controller.attachEnabled = false }
        compose.waitForIdle()
        // A raw touch (the node has no click action while disabled).
        compose.onNodeWithTag("composer-attach").performTouchInput { click(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(250)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        assertTrue("no picker was launched", registry.launches.isEmpty())
        assertFalse("no focus either", morph.isFocused)
    }

    // ------------------------------------------------------- rebuild on open

    @Test
    fun theMenuIsRebuiltOnEveryOpenFromTheClipboardAndTheCameraFeature() {
        mount()
        openMenu()
        compose.onNodeWithTag("attach-camera").assertExists()
        compose.onNodeWithTag("attach-paste").assertDoesNotExist()
        closeMenu()

        // A clip whose description carries an image mime appears on the
        // next open — only the description was read, never the data.
        val uri = Uri.parse("content://sh.roboco.test.clipboard/img")
        clipboard.setPrimaryClip(ClipData(ClipDescription("img", arrayOf("image/png")), ClipData.Item(uri)))
        openMenu()
        compose.onNodeWithTag("attach-paste").assertExists()
        closeMenu()

        // A camera-less device drops the camera row on the next open.
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
        try {
            openMenu()
            compose.onNodeWithTag("attach-camera").assertDoesNotExist()
            compose.onNodeWithTag("attach-photos").assertExists()
            closeMenu()
        } finally {
            shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        }
    }

    // --------------------------------------------------------- the pickers

    @Test
    fun photoLibraryLaunchesWithTheRemainingSlotsAndTheRightIntent() {
        mount(initialImages = 5)
        openMenu()
        pickFromMenu("attach-photos")
        val first = registry.launches.single { it.first is PickImages }
        assertEquals("5 staged -> 3 free slots as the launch input", 3, first.second)
        assertMultiplePickIntent(3)

        compose.runOnIdle { controller.images = (0 until 7).map { stubImage("seven-$it") } }
        openMenu()
        pickFromMenu("attach-photos")
        assertEquals("7 staged -> 1", 1, registry.launches[1].second)
        assertSinglePickIntent(1)

        compose.runOnIdle { controller.images = (0 until 8).map { stubImage("eight-$it") } }
        openMenu()
        pickFromMenu("attach-photos")
        assertEquals("8 staged -> 0 (floored)", 0, registry.launches[2].second)
        assertSinglePickIntent(0)
    }

    /** `PickImages().createIntent(context, n)` for n ≥ 2: the multiple pick. */
    private fun assertMultiplePickIntent(input: Int) {
        val intent = PickImages().createIntent(context, input)
        assertEquals(MediaStore.ACTION_PICK_IMAGES, intent.action)
        assertEquals(input, intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 1))
    }

    /** `PickImages().createIntent(context, n)` for n ≤ 1: the single pick. */
    private fun assertSinglePickIntent(input: Int) {
        val intent = PickImages().createIntent(context, input)
        assertEquals(MediaStore.ACTION_PICK_IMAGES, intent.action)
        assertEquals("no multiple-pick max extra", 1, intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 1))
    }

    @Test
    fun aPhotoBatchIsStagedOnceAndDeliveredWholeInOrder() {
        mount(initialImages = 5)
        val uris = listOf("one", "two", "three", "four").map {
            Uri.parse("content://sh.roboco.test.photos/$it")
        }
        registry.nextResult = { _, _ -> uris }
        stagerResult = listOf(stubImage("new-0"), stubImage("new-1"), stubImage("new-2"))

        openMenu()
        pickFromMenu("attach-photos")
        compose.waitUntil { stagerCalls.size == 1 }

        val call = stagerCalls.single()
        assertEquals("the four picks, in the returned order", uris, call.first.map { it.uri })
        assertTrue("photo picks carry no name", call.first.all { it.baseName == null })
        assertEquals("limit = the free slots at run time", 3, call.second)

        compose.waitUntil { controller.images.size == 8 }
        assertEquals("the batch was delivered in one addImages call", 5 + 3, controller.images.size)
        assertEquals(
            "the three new images are last, in order",
            listOf("new-0", "new-1", "new-2"),
            controller.images.drop(5).map { it.id },
        )
    }

    @Test
    fun anEmptyPhotoResultDeliversNothing() {
        mount()
        registry.nextResult = { _, _ -> emptyList<Uri>() }
        openMenu()
        pickFromMenu("attach-photos")
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertTrue("no staging call for an empty result", stagerCalls.isEmpty())
        assertTrue(controller.images.isEmpty())
    }

    @Test
    fun chooseFileLaunchesImagesOnlyAndCarriesDisplayNames() {
        mount()
        Robolectric.setupContentProvider(DisplayNameProvider::class.java, FILES_AUTHORITY)
        DisplayNameProvider.displayName = "IMG_1.HEIC"
        val uris = listOf(Uri.parse("content://$FILES_AUTHORITY/img1"))
        registry.nextResult = { _, _ -> uris }
        stagerResult = listOf(stubImage("file-0"))

        openMenu()
        pickFromMenu("attach-files")
        compose.waitUntil { stagerCalls.size == 1 }

        val launch = registry.launches.single { it.first is ActivityResultContracts.OpenMultipleDocuments }
        val input = launch.second as Array<*>
        assertEquals(listOf("image/*"), input.toList())

        val call = stagerCalls.single()
        assertEquals("IMG_1.HEIC -> IMG_1 (no extension)", "IMG_1", call.first.single().baseName)
        compose.waitUntil { controller.images.size == 1 }
    }

    @Test
    fun aFileWithoutADisplayNameStagesWithTheDefaultName() {
        mount()
        Robolectric.setupContentProvider(DisplayNameProvider::class.java, FILES_AUTHORITY)
        DisplayNameProvider.displayName = null // no row
        val uris = listOf(Uri.parse("content://$FILES_AUTHORITY/img1"))
        registry.nextResult = { _, _ -> uris }

        openMenu()
        pickFromMenu("attach-files")
        compose.waitUntil { stagerCalls.size == 1 }
        assertNull("no DISPLAY_NAME -> the pick carries no base name", stagerCalls.single().first.single().baseName)
    }

    @Test
    fun pasteStagesEveryUriItemInOrderAndSkipsTextItems() {
        mount()
        val uri1 = Uri.parse("content://sh.roboco.test.clip/a")
        val uri2 = Uri.parse("content://sh.roboco.test.clip/b")
        val clip = ClipData(ClipDescription("imgs", arrayOf("image/png")), ClipData.Item(uri1)).apply {
            addItem(ClipData.Item(uri2))
            addItem(ClipData.Item("plain text"))
        }
        clipboard.setPrimaryClip(clip)
        stagerResult = listOf(stubImage("paste-0"))

        openMenu()
        pickFromMenu("attach-paste")
        compose.waitUntil { stagerCalls.size == 1 }

        val call = stagerCalls.single()
        assertEquals("the two image URIs in item order", listOf(uri1, uri2), call.first.map { it.uri })
        assertTrue("paste picks carry no name", call.first.all { it.baseName == null })
        assertEquals("a full clipboard slots the batch", 8, call.second)
        compose.waitUntil { controller.images.size == 1 }
    }

    @Test
    fun aLateBatchIsDroppedWhenAQueuedEditBeganMeanwhile() {
        mount()
        val gate = CompletableDeferred<Unit>()
        stagerGate = gate
        stagerResult = listOf(stubImage("late-0"))
        val uris = listOf(Uri.parse("content://sh.roboco.test.photos/late"))
        registry.nextResult = { _, _ -> uris }

        openMenu()
        pickFromMenu("attach-photos")
        compose.waitUntil { stagerCalls.size == 1 } // the stager is now suspended

        compose.runOnIdle { controller.attachEnabled = false } // a queued edit began
        gate.complete(Unit)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertTrue("the late batch was dropped by the gate", controller.images.isEmpty())
    }

    @Test
    fun theCameraShotWritesIntoTheFileProviderCacheAndStagesThenCleansUp() {
        mount()
        registry.nextResult = { contract, _ ->
            if (contract is ActivityResultContracts.TakePicture) true else emptyList<Uri>()
        }
        stagerResult = listOf(stubImage("cam-0"))

        openMenu()
        pickFromMenu("attach-camera")
        compose.waitUntil { stagerCalls.size == 1 }

        val launch = registry.launches.single { it.first is ActivityResultContracts.TakePicture }
        val uri = launch.second as Uri
        assertEquals("the FileProvider authority", "${context.packageName}.fileprovider", uri.authority)
        assertTrue("the URI path is under camera/: ${uri.path}", uri.path!!.contains("camera/"))

        val picked = stagerCalls.single().first.single()
        assertEquals("the camera file is staged from a file:// URI", "file", picked.uri.scheme)
        // Uri.fromFile keeps the JVM's native separators ('\' on Windows);
        // the content URI's path always uses '/'.
        assertTrue("same file the URI names", picked.uri.path!!.replace('\\', '/').endsWith(uri.path!!))
        assertFalse("the file is deleted after staging", File(picked.uri.path!!).exists())
        compose.waitUntil { controller.images.size == 1 }
    }

    @Test
    fun aCancelledCameraShotDeletesTheFileAndStagesNothing() {
        mount()
        registry.nextResult = { contract, _ ->
            if (contract is ActivityResultContracts.TakePicture) false else emptyList<Uri>()
        }

        openMenu()
        pickFromMenu("attach-camera")
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()

        val cameraDir = File(context.cacheDir, "camera")
        assertTrue("the camera dir exists", cameraDir.isDirectory)
        assertEquals("the cancelled shot's file is deleted", 0, cameraDir.listFiles()?.size ?: 0)
        assertTrue("nothing was staged", stagerCalls.isEmpty())
        assertTrue(controller.images.isEmpty())
    }

    // ---------------------------------------------------------------- fakes

    private companion object {
        const val FILES_AUTHORITY = "sh.roboco.test.files"
    }
}

/** Serves [displayName] for the Choose File tests. */
class DisplayNameProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME)
        val name = displayName ?: return MatrixCursor(columns) // no row
        return MatrixCursor(columns).apply { addRow(arrayOf<Any>(name)) }
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        var displayName: String? = null
    }
}
