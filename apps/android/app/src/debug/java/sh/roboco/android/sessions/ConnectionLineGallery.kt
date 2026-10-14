package sh.roboco.android.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import uniffi.roboco_core.Connectivity
import uniffi.roboco_core.EngineDiagnostics
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView

/**
 * The "Connection line" fixtures (android-ui-parity #50) — every row of
 * the ticket's examples table, built as plain [ConnectionLineUi]s (no
 * FFI, nothing from the health model), plus the two Home headers the
 * wifi-off glyph and its spinner comparison need.
 *
 * Debug source set on purpose (the same visibility as ticket 39's
 * `SectionHeaderFixtures`): the design gallery and the Robolectric /
 * Roborazzi tests (`testDebugUnitTest`) both compile against it.
 */
internal object ConnectionLineFixtures {

    /** "1 engine 'MacBook Pro', Reconnecting". */
    val reconnecting = ConnectionLineUi(
        listOf(ConnectionLineEntry(ConnectionGlyph.Spinner, "Reconnecting…")),
    )

    /** "1 engine, Connecting". */
    val connecting = ConnectionLineUi(
        listOf(ConnectionLineEntry(ConnectionGlyph.Spinner, "Connecting…")),
    )

    /** "1 engine, Parked(REVOKED)". */
    val revoked = ConnectionLineUi(
        listOf(ConnectionLineEntry(ConnectionGlyph.DangerDot, "Session revoked")),
    )

    /** "1 engine, Parked(IDENTITY_CHANGED)". */
    val identityChanged = ConnectionLineUi(
        listOf(ConnectionLineEntry(ConnectionGlyph.DangerDot, "Engine changed")),
    )

    /** "1 engine 'MacBook Pro', Parked(INCOMPATIBLE)": the long sentence fades out, no "…". */
    val incompatible = ConnectionLineUi(
        listOf(
            ConnectionLineEntry(
                ConnectionGlyph.DangerDot,
                "MacBook Pro needs a newer Roboco app. Update the app to connect.",
            ),
        ),
    )

    /** "Studio (Connected), Laptop (Reconnecting)": the name-prefixed line. */
    val laptop = ConnectionLineUi(
        listOf(ConnectionLineEntry(ConnectionGlyph.Spinner, "Laptop — Reconnecting…")),
    )

    /** "Studio Parked(REVOKED), Laptop Reconnecting, Mini Connected". */
    val mixed = ConnectionLineUi(
        listOf(
            ConnectionLineEntry(ConnectionGlyph.DangerDot, "Studio — Session revoked"),
            ConnectionLineEntry(ConnectionGlyph.Spinner, "Laptop — Reconnecting…"),
        ),
    )

    /** "A, B, C, D, E all Reconnecting": three lines + the overflow row. */
    val fiveEngines = ConnectionLineUi(
        listOf(
            ConnectionLineEntry(ConnectionGlyph.Spinner, "A — Reconnecting…"),
            ConnectionLineEntry(ConnectionGlyph.Spinner, "B — Reconnecting…"),
            ConnectionLineEntry(ConnectionGlyph.Spinner, "C — Reconnecting…"),
            ConnectionLineEntry(null, "+2 more"),
        ),
    )

    /** "device offline, 2 engines": the single warning-dot line. */
    val offline = ConnectionLineUi(
        listOf(ConnectionLineEntry(ConnectionGlyph.WarningDot, "Offline")),
    )

    val all: List<ConnectionLineUi> = listOf(
        reconnecting, connecting, revoked, identityChanged, incompatible,
        laptop, mixed, fiveEngines, offline,
    )

    /** The offline Home group: the wifi-off glyph in `tertiary`. */
    val offlineHomeHeader = SectionHeaderState(
        "home:mbp", "Home · MacBook Pro", 1, collapsed = false, live = SectionLive.Offline,
    )

    /** The same header with its spinner, for the side-by-side comparison. */
    val spinnerHomeHeader = SectionHeaderState(
        "home:mbp", "Home · MacBook Pro", 1, collapsed = false, live = SectionLive.Spinner,
    )
}

/** The `EngineView` the live simulate row flips (every field the FFI record needs). */
private fun fakeEngine(
    id: String,
    name: String,
    state: EngineState,
    lastError: String? = null,
): EngineView = EngineView(
    engineId = id,
    name = name,
    host = "host-$id",
    baseUrl = "http://127.0.0.1:1",
    deviceId = null,
    platform = null,
    version = null,
    state = state,
    lastError = lastError,
    retryAtMs = null,
    lastConnectedMs = null,
    capabilities = emptyList(),
    notice = null,
    fromCache = false,
    diagnostics = EngineDiagnostics(
        repairedRows = 0u,
        skippedRows = 0u,
        lastDecodeError = null,
        missingStreams = emptyList(),
        generation = 0uL,
    ),
)

/**
 * The "Connection line" page of the design gallery (android-ui-parity
 * #50): every examples-table fixture under a mono caption, the two Home
 * headers (wifi-off vs spinner), and the live "Simulate" block — an
 * [EngineHealthModel] over two `MutableStateFlow`s whose "Engine down"
 * flip shows the real 4 s show-slow / hide-fast grace and the 500 ms
 * entrance fade (tapping again heals the engine and the line vanishes
 * at once). The gallery's Force-reduce-motion toggle removes the fade.
 *
 * Registered in `Gallery.sections` as
 * `GallerySection("Connection line", screenshotHeightDp = 900) { ConnectionLineGallerySection() }`.
 */
@Composable
fun ConnectionLineGallerySection() {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val captionStyle = uiStyle(Face.Mono, ts.fixed(11f), palette.tertiary)
    Column(Modifier.background(palette.background)) {
        ConnectionLineFixtures.all.forEachIndexed { i, fixture ->
            BasicText("fixture ${i + 1}", style = captionStyle)
            ConnectionLine(fixture, onTap = {})
        }
        BasicText("Home group · offline vs spinner", style = captionStyle)
        SectionHeaderRow(ConnectionLineFixtures.offlineHomeHeader, highlight = { 0f })
        SectionHeaderRow(ConnectionLineFixtures.spinnerHomeHeader, highlight = { 0f })

        BasicText("simulate (real 4 s grace)", style = captionStyle)
        SimulateConnectionLine(captionStyle)
    }
}

/**
 * The live half: one engine over a [MutableStateFlow], one health model
 * on the gallery's scope and the production clock, and the "Engine
 * down" / "Engine up" flip. No FFI — the engine is a plain record, and
 * the model's flows are the MutableStateFlows it owns.
 */
@Composable
private fun SimulateConnectionLine(captionStyle: TextStyle) {
    val scope = rememberCoroutineScope()
    val engines = remember { MutableStateFlow(listOf(fakeEngine("sim", "MacBook Pro", EngineState.Connected))) }
    val conn = remember { MutableStateFlow<Connectivity?>(null) }
    val health = remember { EngineHealthModel(engines, conn, scope) }
    val ui by health.ui.collectAsState()
    var down by remember { mutableStateOf(false) }
    // A null line (every engine healthy) emits nothing at all — the same
    // rule as the list item.
    ui.line?.let { line ->
        ConnectionLine(line, onTap = {})
    }
    BasicText(
        if (down) "Engine up" else "Engine down",
        style = captionStyle,
        modifier = Modifier
            .clickable(interactionSource = null, indication = null) {
                down = !down
                engines.value = listOf(
                    fakeEngine(
                        "sim",
                        "MacBook Pro",
                        if (down) EngineState.Reconnecting else EngineState.Connected,
                    ),
                )
            }
            .padding(top = 8.dp, bottom = 8.dp),
    )
}
