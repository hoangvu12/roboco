package sh.roboco.android.pairing

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import sh.roboco.android.MainActivity

/**
 * The `text/plain` share target (android-ui-parity #139) — a no-UI
 * trampoline (`Theme.NoDisplay`, never in Recents). The system share
 * sheet starts a share target inside the **sender's** task: putting the
 * SEND filter on `MainActivity` would build a second instance there,
 * while the process-wide deep-link hub hands the link to the first
 * instance's still-registered handler — the page would open in a task
 * the user cannot see. So this activity owns only the filter and
 * re-launches `MainActivity` in the app's own task with
 * `NEW_TASK | CLEAR_TOP | SINGLE_TOP`, carrying just the surviving
 * candidates (never the whole shared text: it bounds the Binder extra
 * and the FFI parses later).
 *
 * No candidates → a plain `ACTION_MAIN`/`LAUNCHER` intent with
 * `NEW_TASK | RESET_TASK_IF_NEEDED`: exactly a launcher-icon tap, the
 * app opens normally and nothing else happens.
 *
 * Everything happens in `onCreate` — a `Theme.NoDisplay` activity that
 * reaches `onResume` without finishing crashes. Only pure Kotlin runs
 * here ([PairingLinks.shareCandidates]); the FFI validation happens in
 * `MainActivity`'s SEND branch, off the main thread.
 */
class PairShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // runCatching: a hostile parcel can throw BadParcelableException.
        val text = runCatching {
            intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        }.getOrNull().orEmpty()
        val candidates = PairingLinks.shareCandidates(text)
        val target = if (candidates.isNotEmpty()) {
            Intent(this, MainActivity::class.java).apply {
                setAction(Intent.ACTION_SEND)
                setType("text/plain")
                putExtra(Intent.EXTRA_TEXT, candidates.joinToString("\n"))
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                setAction(Intent.ACTION_MAIN)
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                )
            }
        }
        startActivity(target)
        finish()
    }
}
