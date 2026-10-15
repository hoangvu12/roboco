package sh.roboco.android.pairing

import android.net.Uri
import uniffi.roboco_core.EngineTransport
import uniffi.roboco_core.PairedEngine

/**
 * The only debug-safe rendering of a [PairedEngine] (ticket 71's
 * redacted-logging rule): the generated `toString()` prints the
 * credential — and, for a Tailcat engine (android-next#02), the secret
 * address and client identity — so no log, template or error message may
 * carry the record itself. Never printed: the credential, the Tailcat
 * address or state, and any blob bytes. A Tailcat engine shows its
 * `tailcat:<8 hex>` fingerprint as its host.
 */
fun PairedEngine.redacted(): String =
    "PairedEngine(engineId=$engineId, host=${hostOf(baseUrl)}, deviceId=$deviceId, " +
        "transport=${if (transport is EngineTransport.Tailcat) "tailcat" else "direct"}, " +
        "credential=<redacted ${credential.length} chars>)"

private fun hostOf(baseUrl: String): String =
    if (baseUrl.startsWith(TAILCAT_SCHEME)) baseUrl else Uri.parse(baseUrl).host ?: "?"

/** A Tailcat engine's `baseUrl` is `tailcat:<8 hex>`, a non-secret fingerprint. */
const val TAILCAT_SCHEME = "tailcat:"

/** The Address row for a Tailcat engine: `Tailcat · 3f9a12bc`; `null` for a direct one. */
fun tailcatAddressLabel(baseUrl: String): String? =
    if (baseUrl.startsWith(TAILCAT_SCHEME)) "Tailcat \u00B7 ${baseUrl.removePrefix(TAILCAT_SCHEME)}" else null
