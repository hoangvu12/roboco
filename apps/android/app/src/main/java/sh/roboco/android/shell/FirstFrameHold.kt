package sh.roboco.android.shell

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver

/**
 * Keeps the platform splash up until [ready] returns true or [timeoutMs]
 * passes (android-ui-parity #35; 2000 ms is a Roboco choice — iOS loads
 * credentials synchronously, Android loads engines asynchronously and
 * must not flash the pairing root at a paired user).
 *
 * Main thread. Call after `setContent` in `MainActivity.onCreate`; the
 * listener removes itself on the first pre-draw it allows through.
 * `postInvalidateOnAnimation()` keeps pre-draw passes coming while the
 * hold is up, so the timeout is checked even if nothing else invalidates.
 */
fun Activity.holdFirstFrame(timeoutMs: Long = 2000, ready: () -> Boolean) {
    val content = findViewById<View>(android.R.id.content)
    val start = SystemClock.uptimeMillis()
    content.viewTreeObserver.addOnPreDrawListener(
        object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val go = ready() || SystemClock.uptimeMillis() - start >= timeoutMs
                if (go) {
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                } else {
                    content.postInvalidateOnAnimation()
                }
                return go
            }
        },
    )
}
