package sh.roboco.android.composer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The staging pipeline (android-ui-parity #87) — Robolectric with NATIVE
 * graphics (`ImageDecoder`, `Bitmap.compress`, `createScaledBitmap` run
 * real Skia), the same harness as ticket 20's `WallpaperStoreTest`: the
 * pure sizing/naming functions, the 2560 px / JPEG 86 / 24 MiB / 180 px
 * rules through a real 6000 × 4000 JPEG fixture, EXIF orientation 6, the
 * silent drops, and [ImageStaging.stageAll]'s "stop once the free slots
 * are filled, failures don't use a slot" order semantics.
 *
 * The fixtures are built in-test (`Bitmap.createBitmap` + fill +
 * `compress(JPEG, 100)`) into a temp dir and served through a registered
 * provider that counts every `openFile` — the recording resolver of the
 * ticket's acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ImageStagingTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val resolver
        get() = context.contentResolver

    @After
    fun clearProviderState() {
        AttachFixtureProvider.fixtures.clear()
        AttachFixtureProvider.opens.clear()
    }

    // ------------------------------------------------------- the pure maths

    @Test
    fun targetSizeDownscalesTheLongSideAndNeverUpscales() {
        assertEquals(IntSize(2560, 1707), ImageStaging.targetSize(6000, 4000))
        assertEquals(IntSize(1707, 2560), ImageStaging.targetSize(4000, 6000))
        assertNull("already within the bound: no target (full-size decode)", ImageStaging.targetSize(2560, 100))
        assertNull(ImageStaging.targetSize(1000, 800))
        // The max(1, …) guard: a 10 px side downsamples to 0 without it.
        assertEquals(IntSize(2560, 1), ImageStaging.targetSize(100000, 10))
    }

    @Test
    fun thumbSizeFitsTheLongSideAndNeverUpscales() {
        assertEquals(IntSize(180, 120), ImageStaging.thumbSize(2560, 1707))
        assertEquals(IntSize(100, 50), ImageStaging.thumbSize(100, 50))
        assertEquals(IntSize(180, 1), ImageStaging.thumbSize(100000, 10))
    }

    @Test
    fun baseNameMatchesEveryRowOfTheSpecTable() {
        assertEquals("IMG_1234", ImageStaging.baseName("IMG_1234.HEIC"))
        assertEquals("photo.tar", ImageStaging.baseName("photo.tar.png"))
        assertEquals("scan", ImageStaging.baseName("scan"))
        assertEquals(".hidden", ImageStaging.baseName(".hidden")) // a leading dot is not an extension
        assertNull(ImageStaging.baseName(""))
        assertNull(ImageStaging.baseName(null))
        assertNull(ImageStaging.baseName("   "))
    }

    @Test
    fun fileNameUsesTheBaseNameOrTheFirstEightHexOfTheId() {
        assertEquals("image-0a1b2c3d.jpg", ImageStaging.fileName(null, "0a1b2c3d-45e6-78f9-abcd-0123456789ab"))
        assertEquals("IMG_1234.jpg", ImageStaging.fileName("IMG_1234", "0a1b2c3d-45e6-78f9-abcd-0123456789ab"))
    }

    // ---------------------------------------------------------- the pipeline

    @Test
    fun aBigPhotoStagesToAJpegWithinTheLongSideBound() {
        val fixture = solidJpeg(6000, 4000, 0xFF808080.toInt(), "big")
        val staged = ImageStaging.stage(resolver, pickOf(fixture))!!
        assertNotNull(staged)

        // The staged bytes decode as 2560 × 1707 (scale = 2560/6000).
        val decoded = BitmapFactory.decodeByteArray(staged.bytes, 0, staged.bytes.size)
        assertEquals(2560, decoded.width)
        assertEquals(1707, decoded.height)
        decoded.recycle()

        assertEquals(180, staged.thumb.width)
        assertEquals(120, staged.thumb.height)
        assertTrue("name is image-<8 hex>.jpg: ${staged.name}", staged.name.matches(imageNameRegex))
        assertTrue("id is a lowercased uuid: ${staged.id}", staged.id.matches(idRegex))
    }

    @Test
    fun exifOrientationSixStagesUpright() {
        val fixture = solidJpeg(40, 20, 0xFF808080.toInt(), "rotated")
        ExifInterface(fixture.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            saveAttributes()
        }
        val staged = ImageStaging.stage(resolver, pickOf(fixture))!!
        val decoded = BitmapFactory.decodeByteArray(staged.bytes, 0, staged.bytes.size)
        assertEquals(20, decoded.width)
        assertEquals(40, decoded.height)
        decoded.recycle()
        // 20 × 40 is under the thumb bound: the bitmap itself is the thumb.
        assertEquals(20, staged.thumb.width)
        assertEquals(40, staged.thumb.height)
    }

    @Test
    fun aCorruptFileIsDroppedWithoutThrowing() {
        val junk = tmpFolder.newFile("junk.jpg")
        junk.writeText("this is not an image at all")
        val staged = ImageStaging.stage(resolver, pickOf(junk))
        assertNull("a failed decode returns null, never an exception", staged)
    }

    @Test
    fun anEncodeOverTheByteCapIsDroppedAndASmallOneKept() {
        val noise = noiseBitmap(512, 512)
        assertNull(
            "a stub cap the encode exceeds: the drop",
            ImageStaging.encode(noise, null, maxBytes = 1024),
        )

        val small = noiseBitmap(64, 64)
        val staged = ImageStaging.encode(small, "IMG_1")!!
        assertEquals("IMG_1.jpg", staged.name)
        assertTrue(staged.bytes.size <= ImageStaging.MaxBytes)
        // 64 × 64 is under 180: no thumbnail copy, the bitmap is the thumb.
        assertEquals(64, staged.thumb.width)
        assertEquals(64, staged.thumb.height)
    }

    @Test
    fun stageAllStopsOnceTheSlotsAreFilledAndFailuresUseNoSlot() {
        val bad = tmpFolder.newFile("bad.jpg").apply { writeText("junk") }
        val ok1 = solidJpeg(64, 48, 0xFFDC2626.toInt(), "ok1")
        val ok2 = solidJpeg(32, 24, 0xFF15803D.toInt(), "ok2")
        val ok3 = solidJpeg(16, 12, 0xFF1D4ED8.toInt(), "ok3")
        val badPick = pickOf(bad)
        val ok1Pick = pickOf(ok1)
        val ok2Pick = pickOf(ok2)
        val ok3Pick = pickOf(ok3)

        val staged = runBlocking {
            ImageStaging.stageAll(resolver, listOf(badPick, ok1Pick, ok2Pick, ok3Pick), 2)
        }

        assertEquals(2, staged.size)
        assertEquals("ok1 first, ok2 second", listOf(48, 24), staged.map { it.thumb.height })
        assertEquals("ok3 was never opened", 0, AttachFixtureProvider.opens.count { it == ok3Pick.uri })
        assertEquals("bad, ok1 and ok2 were each opened once", listOf(badPick.uri, ok1Pick.uri, ok2Pick.uri), AttachFixtureProvider.opens)
    }

    @Test
    fun stageAllWithZeroLimitOpensNothing() {
        val fixture = solidJpeg(64, 48, 0xFFDC2626.toInt(), "zero")
        val picks = listOf(pickOf(fixture))
        val staged = runBlocking { ImageStaging.stageAll(resolver, picks, 0) }
        assertTrue(staged.isEmpty())
        assertTrue("nothing was opened", AttachFixtureProvider.opens.isEmpty())
    }

    // ------------------------------------------------------------- fixtures

    private val imageNameRegex = Regex("^image-[0-9a-f]{8}\\.jpg$")
    private val idRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** A solid-colour JPEG at quality 100, served under the fixture authority. */
    private fun solidJpeg(width: Int, height: Int, color: Int, name: String): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val file = tmpFolder.newFile("$name.jpg")
        file.outputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)) { "fixture encode failed" }
        }
        bitmap.recycle()
        return file
    }

    /** A random-noise bitmap (large JPEGs) with an opaque alpha. */
    private fun noiseBitmap(width: Int, height: Int): Bitmap {
        val random = java.util.Random(7)
        val pixels = IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Registers [file] under the provider and returns its content pick. */
    private fun pickOf(file: File): PickedImage {
        val uri = Uri.parse("content://$AUTHORITY/${file.name}")
        if (AttachFixtureProvider.fixtures.isEmpty()) {
            Robolectric.setupContentProvider(AttachFixtureProvider::class.java, AUTHORITY)
        }
        AttachFixtureProvider.fixtures[uri] = file
        return PickedImage(uri)
    }

    private companion object {
        const val AUTHORITY = "sh.roboco.test.attach"
    }
}

/**
 * Serves the [fixtures] under [AUTHORITY], counting every [opens] — the
 * "recording resolver" of the ticket's `stageAll` acceptance. JPEGs are
 * whole-file `ParcelFileDescriptor`s, exactly what
 * `ImageDecoder.createSource(resolver, uri)` asks for through
 * `openTypedAssetFileDescriptor`.
 */
class AttachFixtureProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = fixtures[uri] ?: throw FileNotFoundException("no fixture registered for $uri")
        opens += uri
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * Windows JVM only: `ImageDecoder.createFromAssetFileDescriptor` hands
     * the descriptor's int fd to Robolectric's native decoder, and a
     * Windows `java.io.FileDescriptor` has none (files are handles, fd =
     * -1), so every typed-AFD decode fails there and staging returns null.
     * Refusing the typed AFD sends `ImageDecoder.ContentResolverSource`
     * down its own fallback — `openInputStream` + a stream decode
     * (`nCreate(InputStream, …)`), read through Java. `openInputStream`
     * still goes through [openFile], so [opens] counts each pick once. On
     * Linux the production fd path is exercised unchanged.
     *
     * Only ImageDecoder's own typed request (the `image` wildcard filter) is
     * refused: the fallback's `ContentResolver.openInputStream` is itself
     * `openAssetFileDescriptor(uri, "r")`, which for a content URI calls
     * `openTypedAssetFileDescriptor` with the any-type filter [AnyType]
     * (android-all 16 bytecode) — refusing that too failed the stream path
     * as well, so every stage returned null on Windows. That AFD is
     * whole-file (length -1), so `createInputStream` is a plain
     * `ParcelFileDescriptor.AutoCloseInputStream` (a `FileInputStream`,
     * which reads a Windows handle fine).
     */
    @Throws(FileNotFoundException::class)
    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor? {
        if (File.separatorChar == '\\' && mimeTypeFilter != AnyType) {
            throw FileNotFoundException("typed AFDs are not decodable on a Windows JVM: $uri")
        }
        return super.openTypedAssetFile(uri, mimeTypeFilter, opts)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)) // no row

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /** The filter `openAssetFileDescriptor(uri, "r")` passes for a content URI. */
        const val AnyType = "*/*"

        val fixtures = mutableMapOf<Uri, File>()
        val opens = mutableListOf<Uri>()
    }
}
