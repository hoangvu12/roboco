package sh.roboco.android.design

/**
 * The toast's placement math (ticket 14): pure functions over **dp** values,
 * so [ToastPlacementTest] can pin them on the plain JVM. Every value ports
 * one line of `ios:Design/Toast.swift`:
 *
 *  - the gap 12 (`Toast.swift:19`);
 *  - the anchor: `min(safe-area bottom, chrome top)` (`Toast.swift:29-45`);
 *  - the rest y: `anchor − gap − capsule height` (`Toast.swift:19,22`);
 *  - the width cap: `window − 48`, no minimum (`Toast.swift:23`).
 *
 * All inputs and outputs are dp floats measured from the top of the root
 * window (the app is edge-to-edge, so `boundsInRoot` == window
 * coordinates). `Float.NaN` stands in for `Dp.Unspecified` (no chrome).
 */

/** The gap between the capsule's bottom edge and the anchor top. `Toast.swift:19` */
const val ToastGapDp = 12f

/**
 * The anchor top: the safe-area bottom, or the bottom chrome's top when
 * chrome is on screen — whichever is **higher** (the smaller y).
 * @param chromeTop the chrome top in dp; `Float.NaN` = no chrome on screen.
 */
fun toastAnchor(safeBottom: Float, chromeTop: Float): Float =
    if (chromeTop.isNaN()) safeBottom else minOf(safeBottom, chromeTop)

/** The capsule's rest y (its **top** edge, dp): `anchor − 12 − capsuleHeight`. `Toast.swift:19,22` */
fun toastRestY(safeBottom: Float, chromeTop: Float, capsuleHeight: Float): Float =
    toastAnchor(safeBottom, chromeTop) - ToastGapDp - capsuleHeight

/** The capsule's max width: `window − 48` (hugs content below that, no minimum). `Toast.swift:23` */
fun toastMaxWidth(windowWidth: Float): Float = maxOf(0f, windowWidth - 48f)
