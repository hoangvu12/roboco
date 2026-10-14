package sh.roboco.android.newsession

import androidx.compose.runtime.Immutable
import java.util.Locale
import kotlinx.coroutines.CancellationException
import sh.roboco.android.composer.ChipColors
import sh.roboco.android.design.Palette
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.FolderEntry

/**
 * The folder browser's pure helpers (android-ui-parity #112): line-for-line
 * ports of the web add-project palette's search and path logic —
 * `web/packages/app/src/lib/picker-search.ts` (`matchRank`,
 * `filterIndices`) and `web/packages/app/src/lib/add-space.ts`
 * (`pathUnder`, `manualPathQuery`, `filteredFolders`) — plus the rail's
 * chip model/selection/colours and the shared engine-error text.
 *
 * Pure: no Composable calls, no FFI calls, no state. Everything here is
 * unit-tested from [FolderSearchTest] and consumed by [FolderNavState]
 * (behaviour) and `FolderLocationsHeader` (colours).
 */

// ---------------------------------------------------------------------------
// picker-search.ts
// ---------------------------------------------------------------------------

/**
 * web `matchRank`: 0 for a prefix match, 1 for a substring match (or a
 * blank query — everything matches), `null` for no match. Case-insensitive
 * over the **trimmed** query and the whole label.
 */
fun matchRank(query: String, label: String): Int? {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return 1
    val hay = label.lowercase(Locale.ROOT)
    return when {
        hay.startsWith(needle) -> 0
        hay.contains(needle) -> 1
        else -> null
    }
}

/**
 * web `filterIndices`: the indices of the matching labels, prefix matches
 * before substring matches, ties stable on the input order.
 */
fun filterIndices(query: String, labels: List<String>): List<Int> =
    labels.indices
        .mapNotNull { i -> matchRank(query, labels[i])?.let { r -> r to i } }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .map { it.second }

// ---------------------------------------------------------------------------
// add-space.ts
// ---------------------------------------------------------------------------

/**
 * web `pathUnder`: is [path] inside [base]? Segment-aware (`/media/a` is
 * under `/media`, `/media/ab` is not), a root base (`/`, or `""` after
 * trimming) covers every path, and either separator counts, so `D:\Random`
 * is under `D:\`.
 */
fun pathUnder(path: String, base: String): Boolean {
    val trimmed = base.trimEnd('/', '\\')
    if (trimmed.isEmpty()) return true
    if (!path.startsWith(trimmed)) return false
    val rest = path.substring(trimmed.length)
    return rest.isEmpty() || rest[0] == '/' || rest[0] == '\\'
}

/**
 * web `manualPathQuery`: does the **trimmed** text read like a typed path?
 * `/…`, `~…`, `\…` or a `:` as the second character (any drive letter, and
 * web keeps the quirk that `1:` also counts while `ab:/x` does not).
 */
fun manualPathQuery(text: String): Boolean {
    val t = text.trim()
    return t.startsWith("/") || t.startsWith("~") || t.startsWith("\\") || t.getOrNull(1) == ':'
}

/**
 * web `filteredFolders`: directories only; `.`-names are dropped unless the
 * **untrimmed** query starts with `.`; the rest keep their engine order,
 * ranked prefix-first (see [filterIndices]).
 */
fun <T> filteredFolders(
    entries: List<T>,
    query: String,
    name: (T) -> String,
    isDir: (T) -> Boolean,
): List<T> {
    val dirs = entries.filter { isDir(it) && (query.startsWith(".") || !name(it).startsWith(".")) }
    return filterIndices(query, dirs.map(name)).map { dirs[it] }
}

/** [filteredFolders] over the FFI's [FolderEntry]s. */
fun filteredFolders(entries: List<FolderEntry>, query: String): List<FolderEntry> =
    filteredFolders(entries, query, { it.name }, { it.isDir })

/**
 * What 110's list shows for a query. A **path-shaped** query never filters
 * the rows (the path is typed for the engine, not the list) and always
 * allows the `..` row; a blank plain query allows it; a non-blank plain
 * query hides it (the web filter list has no parent row and `..` matches
 * no query). `noMatch` is true only for a non-blank plain query with no
 * surviving row.
 */
data class FolderRows<T>(val parentAllowed: Boolean, val folders: List<T>, val noMatch: Boolean)

fun <T> folderRows(entries: List<T>, query: String, name: (T) -> String, isDir: (T) -> Boolean): FolderRows<T> =
    if (manualPathQuery(query)) {
        FolderRows(parentAllowed = true, folders = filteredFolders(entries, "", name, isDir), noMatch = false)
    } else {
        filteredFolders(entries, query, name, isDir).let {
            FolderRows(parentAllowed = query.isBlank(), folders = it, noMatch = it.isEmpty() && query.isNotBlank())
        }
    }

// ---------------------------------------------------------------------------
// The locations rail
// ---------------------------------------------------------------------------

/**
 * One rail chip. `path == null` is **Home** — the engine user's home, the
 * root the last successful pathless `listFolders` reported. Drives carry
 * their absolute mount point, host style (`C:\`, `/`, `/mnt/data`).
 */
@Immutable
data class LocationChipModel(val name: String, val path: String?)

/** Home (no path) then the engine's drives, in engine order (web `locationRows` without the query filter). */
fun locationChips(drives: List<Pair<String, String>>): List<LocationChipModel> =
    listOf(LocationChipModel("Home", null)) + drives.map { (n, p) -> LocationChipModel(n, p) }

/**
 * The index of the chip whose root holds [path] most **specifically**: among
 * the chips whose root is non-null and `pathUnder(path, root)`, the one with
 * the longest trimmed root (ties go to the earlier chip, so Home wins a
 * tie). `System` (`/`) and `C:` (`C:\`) cover their whole disk, so without
 * the longest-root rule Home and System would both match `/home/<user>`.
 * `path == null` (no listing, or a listing from another engine) selects
 * nothing, and a Home chip with an unknown home never matches either.
 */
fun selectedLocation(path: String?, home: String?, chips: List<LocationChipModel>): Int? {
    if (path == null) return null
    var best: Int? = null
    var bestLen = -1
    chips.forEachIndexed { i, c ->
        val root = c.path ?: home ?: return@forEachIndexed
        if (!pathUnder(path, root)) return@forEachIndexed
        val len = root.trimEnd('/', '\\').length
        if (len > bestLen) {
            best = i
            bestLen = len
        }
    }
    return best
}

/**
 * The selected chip's paint override for ticket 59's [ChipColors]:
 * selected → `accentSoft` fill with `accent` ink (`tertiary` ink while
 * disabled); unselected → `null`, ticket 59's own `controlFill`/`text`
 * defaults. Pure over the palette (the ticket's colour table).
 */
fun locationChipColors(palette: Palette, selected: Boolean, enabled: Boolean): ChipColors? =
    if (!selected) {
        null
    } else {
        ChipColors(
            fill = palette.accentSoft,
            ink = if (enabled) palette.accent else palette.tertiary,
        )
    }

// ---------------------------------------------------------------------------
// Engine error text
// ---------------------------------------------------------------------------

/**
 * The status text for a failed probe/create. `CoreException`s surface the
 * engine's own `reason` verbatim (through 111's [folderErrorMessage], which
 * also maps `Closed` to the disconnect copy — the generated `message`
 * override formats it `reason=…`, so `.reason` is read instead); anything
 * else surfaces the platform `message`, or [fallback] when there is none.
 * [CancellationException] is always rethrown.
 */
fun coreErrorText(e: Throwable, fallback: String): String = when (e) {
    is CancellationException -> throw e
    is CoreException -> folderErrorMessage(e)
    else -> e.message ?: fallback
}
