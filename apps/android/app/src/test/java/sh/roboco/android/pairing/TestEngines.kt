package sh.roboco.android.pairing

import uniffi.roboco_core.EngineTransport
import uniffi.roboco_core.PairedEngine
import java.util.UUID

/**
 * Test fixture for [PairedEngine] records (ticket 71's fixture shape):
 * deterministic ids/base URLs so failures name the engine, random
 * credentials so a leaked one is findable in logs and on disk.
 */
internal fun newEngine(
    engineId: String = "11111111-1111-4111-8111-111111111111",
    credential: String = "cred-${UUID.randomUUID()}",
    baseUrl: String = "http://10.0.2.2:27655",
    sessionId: String = "s1",
    label: String = "Pixel 9",
    pairedAtMs: Long = 1_760_000_000_000,
    deviceId: String? = null,
    name: String? = null,
    transport: EngineTransport = EngineTransport.Direct,
): PairedEngine = PairedEngine(
    engineId = engineId,
    baseUrl = baseUrl,
    credential = credential,
    sessionId = sessionId,
    label = label,
    pairedAtMs = pairedAtMs,
    deviceId = deviceId,
    name = name,
    transport = transport,
)

/** A Tailcat engine (android-next#02): secret-marked address and identity. */
internal fun newTailcatEngine(
    engineId: String = "33333333-3333-4333-8333-333333333333",
    credential: String = "cred-${UUID.randomUUID()}",
    address: String = "tcSECRETADDRESS${UUID.randomUUID().toString().replace("-", "")}",
    clientState: String = "{\"private_key\":\"SECRETSTATE-${UUID.randomUUID()}\"}",
): PairedEngine = newEngine(
    engineId = engineId,
    credential = credential,
    baseUrl = "tailcat:3f9a12bc",
    transport = EngineTransport.Tailcat(address = address, clientState = clientState),
)
