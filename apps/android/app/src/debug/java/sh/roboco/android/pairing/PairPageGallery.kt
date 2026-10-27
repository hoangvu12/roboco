package sh.roboco.android.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.roboco.android.R
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.SheetHost
import sh.roboco.android.shell.rememberSheetHostState
import uniffi.roboco_core.EngineRoomRef
import uniffi.roboco_core.EngineTransport
import uniffi.roboco_core.PairedEngine
import uniffi.roboco_core.PairException
import uniffi.roboco_core.PairingTarget
import uniffi.roboco_core.RoomException
import uniffi.roboco_core.RoomInviteTarget
import uniffi.roboco_core.RoomJoinResult
import uniffi.roboco_core.RoomMemberRecord
import uniffi.roboco_core.RoomRecord

/**
 * The "Pair page" page of the debug design gallery (android-ui-parity
 * #72), registered in ticket 22's `Gallery.sections` as
 * `GallerySection("Pair page", GalleryLayout.Fill) { PairPageGallerySection() }`.
 *
 * Two halves:
 *
 * - the **static frame**: [PairPageContent] in a fixed 393 × 852 phone
 *   frame, centred and scaled to fit the section body, with plain
 *   `BasicText` toggles (never Material controls) for the ticket's five
 *   preview states — empty + Paste, an `http://` link (target +
 *   cleartext note), an `https://` link (target only), Redeeming, the
 *   Refused error;
 * - the **live page**: a "Present in sheet" row that opens the real
 *   [PairPage] through a gallery-local [SheetHost] + [ProvidePairPage]
 *   with the in-memory [GalleryPairingSink], the real
 *   [AndroidPairClipboard], and [GalleryPairingBackend] (1500 ms pair,
 *   `Refused` when the URL contains "refused"). Paste a link, tap "Pair
 *   engine", watch the busy capsule, the footers and the refusals.
 *
 * `PairingTarget` literals feed the fixtures; the parse is the regex
 * stand-in, never the native library.
 */
@Composable
fun PairPageGallerySection() {
    val host = rememberSheetHostState()
    val sink = remember { GalleryPairingSink() }
    SheetHost(host) {
        ProvidePairPage(
            backend = GalleryPairingBackend,
            store = sink,
            qrScan = GalleryQrScan,
        ) {
            PairPageGalleryBody(sink)
        }
    }
}

@Composable
private fun PairPageGalleryBody(sink: GalleryPairingSink) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val pairPage = LocalPairPage.current
    var fixtureIndex by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            GalleryFixtures.forEachIndexed { index, fixture ->
                GalleryToggle(fixture.label, index == fixtureIndex) { fixtureIndex = index }
            }
        }
        BasicText(
            "stored engines: ${sink.engines.size} — a URL containing \"refused\" is refused; " +
                "\"Scan QR code\" delivers the example link and auto-submits",
            style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
            modifier = Modifier.padding(vertical = 8.dp),
        )
        BasicText(
            "Present in sheet",
            style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = { pairPage?.openPairPage() })
                .padding(vertical = 2.dp),
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val scale = minOf(1f, maxWidth / 393.dp, maxHeight / 852.dp)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                key(fixtureIndex) {
                    PairPageStaticFrame(
                        GalleryFixtures[fixtureIndex],
                        Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
            }
        }
    }
}

/** The static 393 × 852 frame with the current fixture (see the section KDoc). */
@Composable
private fun PairPageStaticFrame(fixture: PairGalleryFixture, modifier: Modifier = Modifier) {
    val field = remember(fixture) { TextFieldState(fixture.text) }
    Box(
        modifier
            .requiredSize(393.dp, 852.dp)
            .background(RobocoTheme.palette.background),
    ) {
        PairPageContent(
            text = field,
            target = fixture.target,
            phase = fixture.phase,
            showPaste = fixture.showPaste,
            showScan = true,
            onClose = {},
            onPaste = {},
            onScan = {},
            onSubmit = {},
        )
    }
}

/** One toggle: SemiBold `text` when selected, Regular `secondary` when not. */
@Composable
private fun GalleryToggle(label: String, selected: Boolean, onSelect: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        label,
        Modifier.clickable { onSelect() },
        style = uiStyle(
            if (selected) Face.SansSemibold else Face.Sans,
            typeScale.fixed(14f),
            if (selected) palette.text else palette.secondary,
        ),
    )
}

/** One of the five fixture states. */
private data class PairGalleryFixture(
    val label: String,
    val text: String,
    val target: PairingTarget?,
    val phase: PairPhase,
    val showPaste: Boolean,
)

private val GalleryFixtures = listOf(
    PairGalleryFixture(
        label = "Empty",
        text = "",
        target = null,
        phase = PairPhase.Idle,
        showPaste = true,
    ),
    PairGalleryFixture(
        label = "http",
        text = "http://192.168.1.10:27655/pair#token=abc",
        target = PairingTarget(baseUrl = "http://192.168.1.10:27655", host = "192.168.1.10:27655"),
        phase = PairPhase.Idle,
        showPaste = false,
    ),
    PairGalleryFixture(
        label = "https",
        text = "https://engine.example.ts.net/pair#token=abc",
        target = PairingTarget(baseUrl = "https://engine.example.ts.net", host = "engine.example.ts.net"),
        phase = PairPhase.Idle,
        showPaste = false,
    ),
    PairGalleryFixture(
        label = "Redeeming",
        text = "http://192.168.1.10:27655/pair#token=abc",
        target = PairingTarget(baseUrl = "http://192.168.1.10:27655", host = "192.168.1.10:27655"),
        phase = PairPhase.Redeeming,
        showPaste = false,
    ),
    PairGalleryFixture(
        label = "Error",
        text = "http://192.168.1.10:27655/pair#token=abc",
        target = PairingTarget(baseUrl = "http://192.168.1.10:27655", host = "192.168.1.10:27655"),
        phase = PairPhase.Error(PairMessage.Res(R.string.pair_error_refused)),
        showPaste = false,
    ),
)

/** The in-memory [PairedEngineSink] for the gallery's live sheet. */
private class GalleryPairingSink : PairedEngineSink {
    val engines = mutableStateListOf<PairedEngine>()
    override fun knownEngineIds(): Set<String> = engines.map { it.engineId }.toSet()
    override suspend fun upsert(engine: PairedEngine) {
        val index = engines.indexOfFirst { it.engineId == engine.engineId }
        if (index >= 0) engines[index] = engine else engines += engine
    }
    val rooms = mutableStateListOf<RoomRecord>()
    override suspend fun upsertRoom(room: RoomRecord) {
        val index = rooms.indexOfFirst { it.roomId == room.roomId }
        if (index >= 0) rooms[index] = room else rooms += room
    }
    override suspend fun remove(engineId: String) {
        engines.removeAll { it.engineId == engineId }
    }
}

/**
 * The gallery's [PairingBackend]: the regex parse stand-in (never the
 * native library — Robolectric screenshot fixtures must not depend on
 * it), a 1500 ms pair, `Refused` for a URL containing "refused",
 * success otherwise. Android-app#06 owns the real parser's tests.
 */
private object GalleryPairingBackend : PairingBackend {

    private val pattern = Regex("^(https?)://([^/?#@]+)(/.*)?/pair#token=[A-Za-z0-9_-]+$")

    override fun parse(input: String): PairingTarget? {
        val match = pattern.matchEntire(input.trim()) ?: return null
        val scheme = match.groupValues[1]
        val host = match.groupValues[2]
        val prefix = match.groupValues[3]
        return PairingTarget(baseUrl = "$scheme://$host$prefix", host = host)
    }

    override suspend fun pair(url: String, replaceEngineId: String?): PairedEngine {
        delay(1500)
        if (url.contains("refused")) throw PairException.Refused()
        val parsed = parse(url)
        return PairedEngine(
            engineId = "gallery-engine",
            baseUrl = parsed?.baseUrl ?: "http://engine.roboco.gallery",
            credential = "gallery-credential",
            sessionId = "s1",
            label = "Gallery engine",
            pairedAtMs = System.currentTimeMillis(),
            deviceId = null,
            name = "Gallery engine",
        )
    }

    override suspend fun forget(engineId: String) {}

    override fun parseRoom(input: String): RoomInviteTarget? =
        if (input.trim().startsWith("roboco-room:")) {
            RoomInviteTarget(name = "Gallery room", memberLabels = listOf("desktop", "server"))
        } else {
            null
        }

    override suspend fun joinRoom(invite: String): RoomJoinResult {
        delay(1500)
        if (invite.contains("refused")) throw RoomException.Refused()
        val room = RoomRecord(
            roomId = "gallery-room",
            label = "Gallery room",
            secret = "gallery-secret",
            members = listOf(
                RoomMemberRecord(label = "desktop", address = "http://engine.roboco.gallery"),
                RoomMemberRecord(label = "server", address = "tcgalleryaddress"),
            ),
        )
        return RoomJoinResult(
            room = room,
            engines = listOf(
                PairedEngine(
                    engineId = "gallery-room-desktop",
                    baseUrl = "http://engine.roboco.gallery",
                    credential = "gallery-credential",
                    sessionId = "s1",
                    label = "Gallery engine",
                    pairedAtMs = System.currentTimeMillis(),
                    deviceId = null,
                    name = "Gallery engine",
                    room = EngineRoomRef(roomId = "gallery-room", label = "Gallery room"),
                ),
                PairedEngine(
                    engineId = "gallery-room-server",
                    baseUrl = "tailcat:1a2b3c4d",
                    credential = "gallery-credential",
                    sessionId = "s2",
                    label = "Gallery engine",
                    pairedAtMs = System.currentTimeMillis(),
                    deviceId = null,
                    name = "Gallery server",
                    transport = EngineTransport.Tailcat(
                        address = "tcgalleryaddress",
                        clientState = "gallery-state",
                    ),
                    room = EngineRoomRef(roomId = "gallery-room", label = "Gallery room"),
                ),
            ),
            missing = listOf(),
        )
    }
}

/** The gallery's "scanner": hands back the example link at once. */
private val GalleryQrScan = QrScanRequest { onResult ->
    onResult("https://engine.example.ts.net/pair#token=gallery-qr-scan")
}
