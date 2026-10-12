package sh.roboco.android

import android.app.Application
import sh.roboco.android.core.CoreLoader
import sh.roboco.android.core.CoreStatus
import sh.roboco.android.core.FontAssets

/**
 * The Roboco app shell: verifies the bundled fonts, loads the Rust mobile
 * core, and exposes the result to the activity.
 *
 * Property names are reserved for the parity tickets (android-ui-parity):
 * `engineStore`, `coreStartup`, `clientOrNull()` (#71), `prefs`,
 * `workspaceFeed`, `foldState`, `frontPage` (#40), `appEvents` (#42),
 * `drafts` (#84) — do not reuse them for something else.
 */
class RobocoApplication : Application() {

    lateinit var core: CoreStatus
        private set

    override fun onCreate() {
        super.onCreate()
        // #609's Fonts.init swallowed missing faces (review bug 8); a
        // missing font must fail startup loudly, naming the files.
        FontAssets.requireAll(assets)
        core = CoreLoader.load()
    }
}
