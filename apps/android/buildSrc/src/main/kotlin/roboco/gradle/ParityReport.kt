package roboco.gradle

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * G5 of the iOS parity gate (android-app#30): writes, for every golden in
 * `apps/android/parity/manifest.json` that pins an `iosRef`, the
 * side-by-side comparison `[Android golden | iOS reference | abs-diff
 * heatmap]` as `build/parity/<golden>.png`, plus an `index.html` that
 * lays the whole board out for the reviewer's walk
 * (`apps/android/parity/CHECKLIST.md`).
 *
 * Both sides are normalized the same way the SSIM ratchet (G4) normalizes
 * them: the iOS reference is cropped (status bar 0–54 pt, home indicator
 * bottom 34 pt) and scaled to the Android panel's width; the Android
 * golden (393 or 402 dp at 3×) is scaled by 1/3. The two are laid out
 * beside a 6 px gutter. The heatmap is the per-channel absolute
 * difference, amplified 4× and tinted (black = equal), so small drifts
 * stay visible.
 *
 * The task is a report, not a gate: a missing golden renders a
 * placeholder cell naming what is absent (the recording pass has not
 * run), and the task still succeeds — G1–G4 are the gates. The one
 * exception is a manifest `iosRef` that resolves to no file under
 * [iosRoot]: the board is still written, then the task fails naming it.
 */
abstract class ParityReport : DefaultTask() {

    /** `apps/android/parity/manifest.json`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val manifest: RegularFileProperty

    /** The committed goldens: `apps/android/app/src/test/screenshots/`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val goldens: ConfigurableFileCollection

    /**
     * The root [goldens] lives under (`app/src/test/screenshots/`): a
     * manifest `golden` is a path relative to it (`sessions/x_light.png`).
     */
    @get:Internal
    abstract val goldensRoot: DirectoryProperty

    /** The committed iOS references: `apps/android/parity/ios/`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val iosRefs: ConfigurableFileCollection

    /**
     * The root [iosRefs] lives under (`apps/android/parity/ios/`): a
     * manifest `iosRef` is a path relative to it (`pr874/1-off.png`).
     */
    @get:Internal
    abstract val iosRoot: DirectoryProperty

    /** `app/build/parity/`. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun report() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        val entries = ManifestJson.parse(manifest.get().asFile.readText())
        // Goldens by their exact path relative to the screenshots root, like
        // the references below: a bare name or `endsWith` match could pair
        // `a/x.png` with `b/x.png` (and `endsWith` depends on the host's
        // separator).
        val goldensBase = goldensRoot.get().asFile
        val goldensByPath = goldens.files.associateBy { it.relativeTo(goldensBase).invariantSeparatorsPath }
        // References by their exact path relative to parity/ios. A bare
        // `File.name == ref` match never hit (refs carry their folder), so
        // every board showed "reference missing" and no iOS side (verify run
        // 38628538796). An exact relative path also keeps `a/1-off.png` from
        // matching `b/1-off.png`.
        val iosBase = iosRoot.get().asFile
        val refsByPath = iosRefs.files.associateBy { it.relativeTo(iosBase).invariantSeparatorsPath }
        val unresolved = mutableListOf<String>()
        val rows = entries.map { entry ->
            val goldenFile = goldensByPath[entry.golden]
            val refFile = entry.iosRef?.let { ref ->
                refsByPath[ref].also {
                    if (it == null) unresolved += "${entry.golden}: iosRef '$ref' resolves to no file under $iosBase"
                }
            }
            writeRow(out, entry, goldenFile, refFile)
            Row(entry.golden, entry.iosRef, goldenFile != null, refFile != null, entry.reviewedSha256 != null)
        }
        writeIndex(out, rows)
        // Loud, after the board is written (it still uploads for the
        // diagnosis): an unresolved iosRef is a manifest or path bug, never
        // a "reference missing" cell to walk past.
        if (unresolved.isNotEmpty()) {
            logger.error("parityReport: ${unresolved.size} iosRef(s) unresolved:\n" + unresolved.joinToString("\n"))
            throw GradleException("parityReport: manifest iosRef(s) resolve to no file:\n" + unresolved.joinToString("\n"))
        }
    }

    /** One `[android | ios | diff]` PNG per entry (or the placeholder cell). */
    private fun writeRow(out: File, entry: ManifestEntry, golden: File?, ref: File?) {
        val target = out.resolve("${entry.golden.removeSuffix(".png")}.png")
        val android: BufferedImage? = golden?.let(ImageIO::read)
        val ios: BufferedImage? = ref?.let(ImageIO::read)
        if (android == null && ios == null) {
            placeholder(out, target, entry, "neither the golden nor the iOS reference exists")
            return
        }
        val left = android?.let { scale(it, 1f / 3f) }
        // The reference scaled to the Android panel's width (the golden at
        // 1/3), not a fixed 393/460: the mobile-polish references are
        // 1206 px, and 393/460 drew them ~1030 px wide beside a 402 px
        // panel, the diff then comparing mis-scaled images.
        val right = ios?.let {
            val cropped = crop(ios)
            scale(cropped, (left?.width ?: 393).toFloat() / cropped.width)
        }
        val height = maxOf(left?.height ?: 0, right?.height ?: 0, 1)
        val width = (left?.width ?: 0) + (right?.width ?: 0) + 6
        val image = BufferedImage(width + 4, height + 4, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            left?.let { g.drawImage(it, 2, 2, null) }
            right?.let { g.drawImage(it, 2 + (left?.width ?: 0) + 6, 2, null) }
        } finally {
            g.dispose()
        }
        // The diff needs both sides; without one it stays a plain compare.
        if (left != null && right != null) {
            val diff = diffHeatmap(left, right)
            val combined = BufferedImage(width + 4, height + 4, BufferedImage.TYPE_INT_ARGB)
            val g2 = combined.createGraphics()
            try {
                g2.drawImage(image, 0, 0, null)
                g2.drawImage(diff, 2 + (left.width) + 6, 2, null)
            } finally {
                g2.dispose()
            }
            writePng(combined, target)
        } else {
            writePng(image, target)
        }
    }

    /**
     * Golden names carry their subdirectory (`sessions/…png`), so the
     * target's parent may not exist yet - ImageIO then fails with "Can't
     * create an ImageOutputStream!". A false return (no PNG writer) fails too.
     */
    private fun writePng(image: BufferedImage, target: File) {
        target.parentFile?.mkdirs()
        check(ImageIO.write(image, "png", target)) { "no PNG writer for $target" }
    }

    /** The iOS crop: drop the status bar (0–54 pt) and the home indicator (bottom 34 pt). */
    private fun crop(ios: BufferedImage): BufferedImage {
        val pxPerPt = ios.width / 460f * (460f / 402f) // the references are 460 px ≙ 402 pt
        val top = (54 * pxPerPt).toInt().coerceIn(0, ios.height / 2)
        val bottom = (34 * pxPerPt).toInt().coerceIn(0, ios.height / 2)
        return ios.getSubimage(0, top, ios.width, ios.height - top - bottom)
    }

    private fun scale(source: BufferedImage, factor: Float): BufferedImage {
        val w = (source.width * factor).toInt().coerceAtLeast(1)
        val h = (source.height * factor).toInt().coerceAtLeast(1)
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.drawImage(source, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return image
    }

    /** Per-channel |a − b|, amplified 4× and mapped into the heat ramp. */
    private fun diffHeatmap(a: BufferedImage, b: BufferedImage): BufferedImage {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = a.getRGB(x, y)
                val q = b.getRGB(x, y)
                val dr = (((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)).let { kotlin.math.abs(it) }
                val dg = (((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)).let { kotlin.math.abs(it) }
                val db = ((p and 0xFF) - (q and 0xFF)).let { kotlin.math.abs(it) }
                val heat = ((dr + dg + db) * 4).coerceAtMost(255)
                // black → red → yellow ramp, alpha full.
                val r = heat
                val green = ((heat - 170).coerceAtLeast(0)) * 3
                image.setRGB(x, y, (0xFF shl 24) or (r shl 16) or ((green and 0xFF) shl 8))
            }
        }
        return image
    }

    private fun placeholder(out: File, target: File, entry: ManifestEntry, why: String) {
        val image = BufferedImage(360, 80, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = java.awt.Color(0x30, 0x30, 0x34)
            g.fillRect(0, 0, 360, 80)
            g.color = java.awt.Color(0xE0, 0xE0, 0xE4)
            g.drawString(entry.golden, 12, 34)
            g.drawString(why, 12, 58)
        } finally {
            g.dispose()
        }
        writePng(image, target)
    }

    private fun writeIndex(out: File, rows: List<Row>) {
        val cells = rows.joinToString("\n") { row ->
            val file = "${row.golden.removeSuffix(".png")}.png"
            val reference = when {
                row.hasRef -> "present"
                row.iosRef == null -> "none (adaptation)"
                else -> "NOT FOUND"
            }
            """
            |<section>
            |  <h2>${row.golden}</h2>
            |  <p>iosRef: ${row.iosRef ?: "— (Roboco adaptation)"} ·
            |     golden ${if (row.hasGolden) "present" else "NOT RECORDED"} ·
            |     reference $reference ·
            |     sign-off ${if (row.signed) "pinned" else "pending"}</p>
            |  <img src="$file" alt="${row.golden}">
            |</section>
            """.trimMargin()
        }
        out.resolve("index.html").writeText(
            """
            |<!DOCTYPE html>
            |<html><head><title>Roboco iOS parity report</title>
            |<style>
            |body { background: #1c1c1e; color: #e0e0e4; font: 14px -apple-system, sans-serif; margin: 24px; }
            |section { margin: 24px 0; }
            |img { max-width: 100%; border: 1px solid #48484c; }
            |h2 { font-size: 16px; }
            |</style></head><body>
            |<h1>Roboco iOS parity report</h1>
            |<p>[Android golden | iOS reference | abs-diff heatmap], both normalized like the SSIM ratchet:
            |iOS cropped (54 pt top / 34 pt bottom) and scaled by 393/460; Android scaled by 1/3.</p>
            |$cells
            |</body></html>
            """.trimMargin(),
        )
    }

    private data class Row(
        val golden: String,
        val iosRef: String?,
        val hasGolden: Boolean,
        val hasRef: Boolean,
        val signed: Boolean,
    )

    private companion object {
        val GUTTER = 6
    }
}

/** The manifest's entry (the G2 shape; see `apps/android/parity/manifest.json`). */
internal data class ManifestEntry(
    val golden: String,
    val iosRef: String?,
    val adaptation: String?,
    val reviewedSha256: String?,
    val reviewer: String?,
    val date: String?,
    val checklistItems: List<String>,
    val notes: String?,
    val ssimFloor: Double?,
)

/**
 * A minimal JSON object-array parser for the parity manifest — buildSrc
 * carries no JSON dependency, and the manifest is an authored,
 * well-formed file (no escapes beyond `\` `"` `/` and the control
 * chars). Anything else fails loudly, which is the right behaviour for a
 * gate's input.
 */
internal object ManifestJson {

    fun parse(text: String): List<ManifestEntry> {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.expectEnd() // skips trailing whitespace itself
        val array = value as? List<*> ?: error("manifest root must be an array")
        return array.map { item ->
            val obj = item as? Map<*, *> ?: error("manifest entries must be objects")
            fun string(key: String): String? = (obj[key] as? String)
            val checklist = (obj["checklistItems"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            val floor = when (val f = obj["ssimFloor"]) {
                is Double -> f
                is Long -> f.toDouble()
                is Int -> f.toDouble()
                else -> null
            }
            ManifestEntry(
                golden = string("golden") ?: error("manifest entry without a golden"),
                iosRef = string("iosRef"),
                adaptation = string("adaptation"),
                reviewedSha256 = string("reviewedSha256"),
                reviewer = string("reviewer"),
                date = string("date"),
                checklistItems = checklist,
                notes = string("notes"),
                ssimFloor = floor,
            )
        }
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWhitespace()
            return when (val c = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> parseNumber()
            }
        }

        fun expectEnd() {
            skipWhitespace()
            check(pos == text.length) { "trailing characters at $pos" }
        }

        private fun literal(word: String, value: Any?): Any? {
            check(text.regionMatches(pos, word, 0, word.length)) { "bad literal at $pos" }
            pos += word.length
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> error("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> error("expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = text.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> error("bad escape \\$e at $pos")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            while (pos < text.length && "+-0123456789.eE".indexOf(text[pos]) >= 0) pos++
            val raw = text.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: error("bad number at $start")
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
        }

        private fun peek(): Char {
            check(pos < text.length) { "unexpected end of manifest" }
            return text[pos]
        }

        private fun next(): Char {
            val c = peek()
            pos++
            return c
        }

        private fun expect(c: Char) {
            check(peek() == c) { "expected '$c' at $pos" }
            pos++
        }
    }
}
