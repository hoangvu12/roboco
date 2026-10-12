package sh.roboco.android.pairing

import android.net.Uri
import uniffi.roboco_core.PairedEngine

/**
 * The only debug-safe rendering of a [PairedEngine] (ticket 71's
 * redacted-logging rule): the generated `toString()` prints the
 * credential, so no log, template or error message may carry the record
 * itself. Never printed: the credential itself and any blob bytes.
 */
fun PairedEngine.redacted(): String =
    "PairedEngine(engineId=$engineId, host=${hostOf(baseUrl)}, deviceId=$deviceId, " +
        "credential=<redacted ${credential.length} chars>)"

private fun hostOf(baseUrl: String): String = Uri.parse(baseUrl).host ?: "?"
