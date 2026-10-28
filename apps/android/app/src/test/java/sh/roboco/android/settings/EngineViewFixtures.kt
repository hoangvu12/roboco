package sh.roboco.android.settings

import uniffi.roboco_core.EngineDiagnostics
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView

/**
 * The Devices rows' `EngineView` fixture (android-ui-parity #102): the
 * fields the section reads ([engineId][EngineView.engineId],
 * [name][EngineView.name], [host][EngineView.host],
 * [state][EngineView.state], [lastError][EngineView.lastError]) driven
 * by the test, every other field a neutral value. Ticket 140 widened the
 * driven set with [baseUrl], [deviceId], [platform], [version],
 * [lastConnectedMs] and [notice] (the Engine detail page's value rows);
 * 102's call sites keep compiling through the defaults.
 */
internal fun engineView(
    id: String,
    name: String,
    state: EngineState,
    lastError: String? = null,
    host: String = "studio.local:27655",
    baseUrl: String = "https://$host",
    deviceId: String? = "device-$id",
    platform: String? = "windows",
    version: String? = "1.2.3",
    lastConnectedMs: Long? = null,
    notice: String? = null,
    linkPath: String? = null,
): EngineView = EngineView(
    engineId = id,
    name = name,
    host = host,
    baseUrl = baseUrl,
    deviceId = deviceId,
    platform = platform,
    version = version,
    state = state,
    lastError = lastError,
    retryAtMs = null,
    lastConnectedMs = lastConnectedMs,
    capabilities = emptyList(),
    notice = notice,
    fromCache = false,
    diagnostics = EngineDiagnostics(
        repairedRows = 0u,
        skippedRows = 0u,
        lastDecodeError = null,
        missingStreams = emptyList(),
        generation = 0uL,
    ),
    linkPath = linkPath,
)
