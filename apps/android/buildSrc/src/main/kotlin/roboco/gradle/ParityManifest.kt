package roboco.gradle

import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

/**
 * The parity manifest's maintenance task (android-app#30, G2's tooling):
 * `ParityManifestTest.everyCommittedGoldenHasAnEntry` requires an entry for
 * **every** PNG under `app/src/test/screenshots`, so a freshly recorded set
 * needs one per golden, and every entry needs a human sign-off pinned to the
 * exact pixels. Two modes, one run:
 *
 * 1. **Stub** (always): every golden PNG without an entry gets one —
 *    `adaptation` "regression golden (no iOS reference)", no checklist
 *    items, sign-off fields null (G2 stays red for it until signed). Entries
 *    are appended in path order; existing entries are never reordered,
 *    rewritten or removed (an entry whose PNG is missing is reported).
 * 2. **Sign-off** (`--sign`): fills `reviewedSha256` (the PNG's SHA-256),
 *    `reviewer` and `date` for the named entries — after the reviewer has
 *    walked them on the parity board (`:app:parityReport`) against
 *    `apps/android/parity/CHECKLIST.md`. `--sign` takes a comma-separated
 *    list of golden paths (as in the manifest), `pending` (every recorded
 *    entry not signed yet) or `all` (every recorded entry — a re-record
 *    re-pins changed hashes; use only after re-walking them).
 *
 * ```
 * ./gradlew :app:parityManifest
 * ./gradlew :app:parityManifest --sign=pending --reviewer="Jane Doe" --date=2026-10-14
 * ./gradlew :app:parityManifest --sign=sessions/a_light.png,settings/b_dark.png --reviewer=jd
 * ```
 *
 * `ssimFloor` (the three G4 ratchets) stays a hand edit: score − 0.02 from
 * the board. The file is rewritten in its own layout (two-space entries,
 * inline checklist arrays) and line endings.
 */
abstract class ParityManifest : DefaultTask() {

    /** `apps/android/parity/manifest.json` — read and rewritten in place. */
    @get:Internal
    abstract val manifest: RegularFileProperty

    /** `app/src/test/screenshots` — the committed goldens. */
    @get:Internal
    abstract val goldensDir: DirectoryProperty

    @get:Input
    @get:Optional
    @get:Option(option = "sign", description = "Entries to sign off: comma-separated golden paths, 'pending' or 'all'.")
    abstract val sign: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "reviewer", description = "The sign-off's reviewer (required with --sign).")
    abstract val reviewer: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "date", description = "The sign-off date, ISO yyyy-mm-dd (default: today).")
    abstract val date: Property<String>

    init {
        // It edits a source file: never up to date, never cached.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun update() {
        val manifestFile = manifest.get().asFile
        val root = goldensDir.get().asFile
        val original = manifestFile.readText()
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val entries = ManifestJson.parse(original).toMutableList()

        val goldens = if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .sorted()
                .toList()
        } else {
            emptyList()
        }

        // 1. Stubs for the unlisted goldens.
        val listed = entries.map { it.golden }.toSet()
        val added = goldens.filter { it !in listed }
        for (golden in added) {
            entries += ManifestEntry(
                golden = golden,
                iosRef = null,
                adaptation = STUB_ADAPTATION,
                reviewedSha256 = null,
                reviewer = null,
                date = null,
                checklistItems = emptyList(),
                notes = STUB_NOTE,
                ssimFloor = null,
            )
        }

        // 2. Sign-off.
        val signed = mutableListOf<String>()
        val request = sign.orNull?.trim()?.takeIf { it.isNotEmpty() }
        if (request != null) {
            val who = reviewer.orNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw GradleException("--sign needs --reviewer (the person who walked the board)")
            val day = date.orNull?.trim()?.takeIf { it.isNotEmpty() } ?: LocalDate.now().toString()
            if (!ISO_DATE.matches(day)) throw GradleException("--date must be yyyy-mm-dd, was '$day'")
            val recorded = { e: ManifestEntry -> root.resolve(e.golden).isFile }
            val targets: Set<String> = when (request) {
                "pending" -> entries.filter { recorded(it) && it.reviewedSha256 == null }.map { it.golden }.toSet()
                "all" -> entries.filter(recorded).map { it.golden }.toSet()
                else -> request.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
            val known = entries.map { it.golden }.toSet()
            val unknown = targets.filter { it !in known }
            if (unknown.isNotEmpty()) throw GradleException("--sign names goldens with no manifest entry:\n" + unknown.joinToString("\n"))
            val missing = targets.filter { !root.resolve(it).isFile }
            if (missing.isNotEmpty()) throw GradleException("--sign names goldens that are not recorded:\n" + missing.joinToString("\n"))
            for (i in entries.indices) {
                val e = entries[i]
                if (e.golden in targets) {
                    entries[i] = e.copy(reviewedSha256 = sha256(root.resolve(e.golden)), reviewer = who, date = day)
                    signed += e.golden
                }
            }
        }

        val text = write(entries, newline)
        if (text != original) manifestFile.writeText(text)

        val orphans = entries.map { it.golden }.filter { !root.resolve(it).isFile }
        logger.lifecycle("parityManifest: ${added.size} stub(s) added, ${signed.size} entr(y/ies) signed off")
        if (added.isNotEmpty()) logger.lifecycle(added.joinToString("\n") { "  + $it" })
        if (signed.isNotEmpty()) logger.lifecycle(signed.joinToString("\n") { "  ✓ $it" })
        if (orphans.isNotEmpty()) {
            logger.warn("parityManifest: ${orphans.size} entr(y/ies) without a recorded golden:\n" + orphans.joinToString("\n") { "  ? $it" })
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val STUB_ADAPTATION = "regression golden (no iOS reference)"
        const val STUB_NOTE = "Stub added by :app:parityManifest — walk it on the parity board, then sign off."
        val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

        /** The manifest's own layout: two-space entries, four-space keys, inline checklist arrays. */
        fun write(entries: List<ManifestEntry>, nl: String): String {
            val sb = StringBuilder("[").append(nl)
            entries.forEachIndexed { i, e ->
                sb.append("  {").append(nl)
                val fields = listOf(
                    "golden" to str(e.golden),
                    "iosRef" to str(e.iosRef),
                    "adaptation" to str(e.adaptation),
                    "reviewedSha256" to str(e.reviewedSha256),
                    "reviewer" to str(e.reviewer),
                    "date" to str(e.date),
                    "checklistItems" to e.checklistItems.joinToString(", ", "[", "]") { str(it) },
                    "notes" to str(e.notes),
                    "ssimFloor" to (e.ssimFloor?.toString() ?: "null"),
                )
                fields.forEachIndexed { j, (k, v) ->
                    sb.append("    \"").append(k).append("\": ").append(v)
                    if (j < fields.size - 1) sb.append(',')
                    sb.append(nl)
                }
                sb.append("  }")
                if (i < entries.size - 1) sb.append(',')
                sb.append(nl)
            }
            return sb.append("]").append(nl).toString()
        }

        /** A JSON string (or null); non-ASCII stays verbatim, as the authored file has it. */
        fun str(value: String?): String {
            if (value == null) return "null"
            val sb = StringBuilder("\"")
            for (c in value) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append("\\u%04x".format(c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }
    }
}
