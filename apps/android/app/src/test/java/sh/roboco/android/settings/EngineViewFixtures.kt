package sh.roboco.android.settings

import uniffi.roboco_core.EngineDiagnostics
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView

/**
 * The Devices rows' `EngineView` fixture (android-ui-parity #102): the
 * fields the section reads ([engineId][EngineView.engineId],
 * [name][EngineView.name], [host][EngineView.host],
 * [state][EngineView.state], [lastError][EngineView.lastError]) driven
 * by the test, every other field a neutral value (the section never
 * looks at them — platform, version and last-connected belong to the
 * Engine detail page, ticket 140).
 */
internal fun engineView(
    id: String,
    name: String,
    state: EngineState,
    lastError: String? = null,
    host: String = "studio.local:27655",
): EngineView = EngineView(
    engineId = id,
    name = name,
    host = host,
    baseUrl = "https://$host",
    deviceId = "device-$id",
    platform = "windows",
    version = "1.2.3",
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
