package sh.roboco.android.shell

/**
 * The phone shell's three tabs (android-ui-parity #26), the port of
 * `ios:Shell/MainTabController.swift:24-32`: `sessions` ("Sessions", the
 * `tab-chat` glyph), `more` ("Settings", `tab-settings`) and the
 * `UISearchTab`. iOS order and iOS identifiers, verbatim — the UI test taps
 * `app.tabBars.buttons["Search"]` (`iosui:SessionFlowTests.swift:151`), and
 * `showSettings()` selects `more`.
 */
enum class TabId(val identifier: String) {
    Sessions("sessions"),
    Settings("more"),
    Search("search"),
}

/**
 * The tab titles, verbatim (`MainTabController.swift:23,26,28`). Search's
 * title is an accessibility label only: the search button draws no label,
 * just the magnifier glyph.
 */
val TabId.label: String
    get() = when (this) {
        TabId.Sessions -> "Sessions"
        TabId.Settings -> "Settings"
        TabId.Search -> "Search"
    }
