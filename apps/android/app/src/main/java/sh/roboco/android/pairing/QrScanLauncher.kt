package sh.roboco.android.pairing

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import sh.roboco.android.R
import sh.roboco.android.design.LocalReduceMotion

/**
 * The "Scan QR code" launch contract (android-ui-parity #138): opens
 * [QrScanActivity] with the current reduced-motion flag (04), returns the
 * decoded pairing URL (`null` when cancelled or nothing scanned). Must run
 * under a `ComponentActivity` — [rememberQrScanRequest] guarantees it by
 * living in composition.
 */
class ScanPairingQr : ActivityResultContract<Boolean, String?>() {

    override fun createIntent(context: Context, input: Boolean): Intent =
        Intent(context, QrScanActivity::class.java)
            .putExtra(QrScanActivity.EXTRA_REDUCE_MOTION, input)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) {
            intent?.getStringExtra(QrScanActivity.EXTRA_URL)
        } else {
            null
        }
}

/**
 * The real ticket 72 [QrScanRequest]: launches the scanner with the open
 * transition (`qr_scanner_enter` + `qr_hold`, nothing under reduced
 * motion) and delivers the decoded URL to [QrScanRequest.scan]'s callback,
 * which the Pair page fills and submits at once (`acceptScanned`).
 *
 * One scan at a time: a second `scan` while one is open is ignored (the
 * holder keeps the first callback). If the main activity is recreated
 * while the scanner is up, the new holder has no callback and the result
 * is dropped — acceptable, the Pair sheet does not survive recreation
 * either. A failed launch (no activity — never on this APK) clears the
 * holder and logs; the URL never appears in a log line.
 */
@Composable
fun rememberQrScanRequest(): QrScanRequest {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val holder = remember { ScanCallbackHolder() }
    val launcher = rememberLauncherForActivityResult(ScanPairingQr()) { url ->
        val callback = holder.callback
        holder.callback = null
        if (url != null) callback?.invoke(url)
    }
    val reduceState = rememberUpdatedState(reduce)
    return remember(launcher) {
        QrScanRequest { onResult ->
            if (holder.callback != null) return@QrScanRequest
            holder.callback = onResult
            val r = reduceState.value
            val options = ActivityOptionsCompat.makeCustomAnimation(
                context,
                if (r) 0 else R.anim.qr_scanner_enter,
                if (r) 0 else R.anim.qr_hold,
            )
            try {
                launcher.launch(r, options)
            } catch (e: ActivityNotFoundException) {
                holder.callback = null
                Log.w(TAG, "scanner launch failed")
            }
        }
    }
}

/** The one pending scan callback; a plain object so `remember` keeps it. */
private class ScanCallbackHolder {
    var callback: ((String) -> Unit)? = null
}

private const val TAG = "QrScanner"
